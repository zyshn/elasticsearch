/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.persistent;

import org.apache.logging.log4j.message.ParameterizedMessage;
import org.apache.logging.log4j.util.Supplier;
import org.elasticsearch.action.ActionListener;
import org.elasticsearch.action.admin.cluster.node.tasks.cancel.CancelTasksRequest;
import org.elasticsearch.cluster.ClusterChangedEvent;
import org.elasticsearch.cluster.ClusterStateListener;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.io.stream.StreamInput;
import org.elasticsearch.common.io.stream.StreamOutput;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.AbstractRunnable;
import org.elasticsearch.common.xcontent.XContentBuilder;
import org.elasticsearch.tasks.Task;
import org.elasticsearch.tasks.TaskCancelledException;
import org.elasticsearch.tasks.TaskManager;
import org.elasticsearch.threadpool.ThreadPool;
import org.elasticsearch.transport.TransportResponse.Empty;
import org.elasticsearch.persistent.PersistentTasksService.PersistentTaskOperationListener;
import org.elasticsearch.persistent.PersistentTasksCustomMetaData.PersistentTask;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import static java.util.Objects.requireNonNull;

/**
 * This component is responsible for coordination of execution of persistent tasks on individual nodes. It runs on all
 * non-transport client nodes in the cluster and monitors cluster state changes to detect started commands.
 */
public class PersistentTasksNodeService extends AbstractComponent implements ClusterStateListener {
    private final Map<PersistentTaskId, AllocatedPersistentTask> runningTasks = new HashMap<>();
    private final PersistentTasksService persistentTasksService;
    private final PersistentTasksExecutorRegistry persistentTasksExecutorRegistry;
    private final TaskManager taskManager;
    private final ThreadPool threadPool;
    private final NodePersistentTasksExecutor nodePersistentTasksExecutor;


    public PersistentTasksNodeService(Settings settings,
                                      PersistentTasksService persistentTasksService,
                                      PersistentTasksExecutorRegistry persistentTasksExecutorRegistry,
                                      TaskManager taskManager, ThreadPool threadPool,
                                      NodePersistentTasksExecutor nodePersistentTasksExecutor) {
        super(settings);
        this.persistentTasksService = persistentTasksService;
        this.persistentTasksExecutorRegistry = persistentTasksExecutorRegistry;
        this.taskManager = taskManager;
        this.threadPool = threadPool;
        this.nodePersistentTasksExecutor = nodePersistentTasksExecutor;
    }

    @Override
    public void clusterChanged(ClusterChangedEvent event) {
        PersistentTasksCustomMetaData tasks = event.state().getMetaData().custom(PersistentTasksCustomMetaData.TYPE);
        PersistentTasksCustomMetaData previousTasks = event.previousState().getMetaData().custom(PersistentTasksCustomMetaData.TYPE);

        if (Objects.equals(tasks, previousTasks) == false || event.nodesChanged()) {
            // We have some changes let's check if they are related to our node
            String localNodeId = event.state().getNodes().getLocalNodeId();
            Set<PersistentTaskId> notVisitedTasks = new HashSet<>(runningTasks.keySet());
            if (tasks != null) {
                for (PersistentTask<?> taskInProgress : tasks.tasks()) {
                    if (localNodeId.equals(taskInProgress.getExecutorNode())) {
                        PersistentTaskId persistentTaskId = new PersistentTaskId(taskInProgress.getId(), taskInProgress.getAllocationId());
                        AllocatedPersistentTask persistentTask = runningTasks.get(persistentTaskId);
                        if (persistentTask == null) {
                            // New task - let's start it
                            startTask(taskInProgress);
                        } else {
                            // The task is still running
                            notVisitedTasks.remove(persistentTaskId);
                            if (persistentTask.getState() == AllocatedPersistentTask.State.FAILED_NOTIFICATION) {
                                // We tried to notify the master about this task before but the notification failed and
                                // the master doesn't seem to know about it - retry notification
                                restartCompletionNotification(persistentTask);
                            }
                        }
                    }
                }
            }

            for (PersistentTaskId id : notVisitedTasks) {
                AllocatedPersistentTask task = runningTasks.get(id);
                if (task.getState() == AllocatedPersistentTask.State.NOTIFIED || task.getState() == AllocatedPersistentTask.State.FAILED) {
                    // Result was sent to the caller and the caller acknowledged acceptance of the result
                    finishTask(id);
                } else if (task.getState() == AllocatedPersistentTask.State.FAILED_NOTIFICATION) {
                    // We tried to send result to master, but it failed and master doesn't know about this task
                    // this shouldn't really happen, unless this node is severally out of sync with the master
                    logger.warn("failed to notify master about task {}", task.getPersistentTaskId());
                    finishTask(id);
                } else {
                    // task is running locally, but master doesn't know about it - that means that the persistent task was removed
                    // cancel the task without notifying master
                    cancelTask(id);
                }
            }

        }

    }

    private <Request extends PersistentTaskRequest> void startTask(PersistentTask<Request> taskInProgress) {
        PersistentTasksExecutor<Request> action = persistentTasksExecutorRegistry.getPersistentTaskExecutorSafe(taskInProgress.getTaskName());
        AllocatedPersistentTask task = (AllocatedPersistentTask) taskManager.register("persistent", taskInProgress.getTaskName() + "[c]",
                taskInProgress.getRequest());
        boolean processed = false;
        try {
            task.init(persistentTasksService, taskInProgress.getId(), taskInProgress.getAllocationId());
            PersistentTaskListener listener = new PersistentTaskListener(task);
            try {
                runningTasks.put(new PersistentTaskId(taskInProgress.getId(), taskInProgress.getAllocationId()), task);
                nodePersistentTasksExecutor.executeTask(taskInProgress.getRequest(), task, action, listener);
            } catch (Exception e) {
                // Submit task failure
                listener.onFailure(e);
            }
            processed = true;
        } finally {
            if (processed == false) {
                // something went wrong - unregistering task
                taskManager.unregister(task);
            }
        }
    }

    private void finishTask(PersistentTaskId persistentTaskId) {
        AllocatedPersistentTask task = runningTasks.remove(persistentTaskId);
        if (task != null) {
            taskManager.unregister(task);
        }
    }

    private void cancelTask(PersistentTaskId persistentTaskId) {
        AllocatedPersistentTask task = runningTasks.remove(persistentTaskId);
        if (task != null) {
            if (task.markAsCancelled()) {
                persistentTasksService.sendCancellation(task.getId(), new PersistentTaskOperationListener() {
                    @Override
                    public void onResponse(long taskId) {
                        logger.trace("Persistent task with id {} was cancelled", taskId);

                    }

                    @Override
                    public void onFailure(Exception e) {
                        // There is really nothing we can do in case of failure here
                        logger.warn((Supplier<?>) () -> new ParameterizedMessage("failed to cancel task {}", task.getPersistentTaskId()), e);
                    }
                });
            }
        }
    }


    private void restartCompletionNotification(AllocatedPersistentTask task) {
        logger.trace("resending notification for task {}", task.getPersistentTaskId());
        if (task.getState() == AllocatedPersistentTask.State.CANCELLED) {
            taskManager.unregister(task);
        } else {
            if (task.restartCompletionNotification()) {
                // Need to fork otherwise: java.lang.AssertionError: should not be called by a cluster state applier.
                // reason [the applied cluster state is not yet available])
                PublishedResponseListener listener = new PublishedResponseListener(task);
                try {
                    threadPool.executor(ThreadPool.Names.GENERIC).execute(new AbstractRunnable() {
                        @Override
                        public void onFailure(Exception e) {
                            listener.onFailure(e);
                        }

                        @Override
                        protected void doRun() throws Exception {
                            persistentTasksService.sendCompletionNotification(task.getPersistentTaskId(), task.getFailure(), listener);
                        }
                    });
                } catch (Exception e) {
                    listener.onFailure(e);
                }
            } else {
                logger.warn("attempt to resend notification for task {} in the {} state", task.getPersistentTaskId(), task.getState());
            }
        }
    }

    private void startCompletionNotification(AllocatedPersistentTask task, Exception e) {
        if (task.getState() == AllocatedPersistentTask.State.CANCELLED) {
            taskManager.unregister(task);
        } else {
            logger.trace("sending notification for failed task {}", task.getPersistentTaskId());
            if (task.startNotification(e)) {
                persistentTasksService.sendCompletionNotification(task.getPersistentTaskId(), e, new PublishedResponseListener(task));
            } else {
                logger.warn("attempt to send notification for task {} in the {} state", task.getPersistentTaskId(), task.getState());
            }
        }
    }

    private class PersistentTaskListener implements ActionListener<Empty> {
        private final AllocatedPersistentTask task;

        PersistentTaskListener(final AllocatedPersistentTask task) {
            this.task = task;
        }

        @Override
        public void onResponse(Empty response) {
            startCompletionNotification(task, null);
        }

        @Override
        public void onFailure(Exception e) {
            if (task.isCancelled()) {
                // The task was explicitly cancelled - no need to restart it, just log the exception if it's not TaskCancelledException
                if (e instanceof TaskCancelledException == false) {
                    logger.warn((Supplier<?>) () -> new ParameterizedMessage(
                            "cancelled task {} failed with an exception, cancellation reason [{}]",
                            task.getPersistentTaskId(), task.getReasonCancelled()), e);
                }
                if (CancelTasksRequest.DEFAULT_REASON.equals(task.getReasonCancelled())) {
                    startCompletionNotification(task, null);
                } else {
                    startCompletionNotification(task, e);
                }
            } else {
                startCompletionNotification(task, e);
            }
        }
    }

    private class PublishedResponseListener implements PersistentTaskOperationListener {
        private final AllocatedPersistentTask task;

        PublishedResponseListener(final AllocatedPersistentTask task) {
            this.task = task;
        }


        @Override
        public void onResponse(long taskId) {
            logger.trace("notification for task {} was successful", task.getPersistentTaskId());
            if (task.markAsNotified() == false) {
                logger.warn("attempt to mark task {} in the {} state as NOTIFIED", task.getPersistentTaskId(), task.getState());
            }
            taskManager.unregister(task);
        }

        @Override
        public void onFailure(Exception e) {
            logger.warn((Supplier<?>) () -> new ParameterizedMessage("notification for task {} failed - retrying", task.getPersistentTaskId()), e);
            if (task.notificationFailed() == false) {
                logger.warn("attempt to mark restart notification for task {} in the {} state failed", task.getPersistentTaskId(), task.getState());
            }
        }
    }

    private static class PersistentTaskId {
        private final long id;
        private final long allocationId;

        PersistentTaskId(long id, long allocationId) {
            this.id = id;
            this.allocationId = allocationId;
        }


        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            PersistentTaskId that = (PersistentTaskId) o;
            return id == that.id &&
                    allocationId == that.allocationId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(id, allocationId);
        }
    }

    public static class Status implements Task.Status {
        public static final String NAME = "persistent_executor";

        private final AllocatedPersistentTask.State state;

        public Status(AllocatedPersistentTask.State state) {
            this.state = requireNonNull(state, "State cannot be null");
        }

        public Status(StreamInput in) throws IOException {
            state = AllocatedPersistentTask.State.valueOf(in.readString());
        }

        @Override
        public String getWriteableName() {
            return NAME;
        }

        @Override
        public XContentBuilder toXContent(XContentBuilder builder, Params params) throws IOException {
            builder.startObject();
            builder.field("state", state.toString());
            builder.endObject();
            return builder;
        }

        @Override
        public void writeTo(StreamOutput out) throws IOException {
            out.writeString(state.toString());
        }

        @Override
        public String toString() {
            return Strings.toString(this);
        }

        public AllocatedPersistentTask.State getState() {
            return state;
        }

        @Override
        public boolean isFragment() {
            return false;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Status status = (Status) o;
            return state == status.state;
        }

        @Override
        public int hashCode() {
            return Objects.hash(state);
        }
    }

}