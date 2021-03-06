/*
 * Licensed to CRATE Technology GmbH ("Crate") under one or more contributor
 * license agreements.  See the NOTICE file distributed with this work for
 * additional information regarding copyright ownership.  Crate licenses
 * this file to you under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.  You may
 * obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.  See the
 * License for the specific language governing permissions and limitations
 * under the License.
 *
 * However, if you have executed another commercial license agreement
 * with Crate these terms will supersede the license and you may use the
 * software solely pursuant to the terms of the relevant commercial agreement.
 */

package io.crate.jobs;

import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.*;
import io.crate.concurrent.CountdownFutureCallback;
import io.crate.exceptions.ContextMissingException;
import io.crate.operation.collect.StatsTables;
import org.elasticsearch.ElasticsearchException;
import org.elasticsearch.cluster.ClusterService;
import org.elasticsearch.common.component.AbstractLifecycleComponent;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Singleton;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.common.util.concurrent.ConcurrentCollections;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.*;
import java.util.concurrent.ConcurrentMap;
import java.util.stream.Stream;

@Singleton
public class JobContextService extends AbstractLifecycleComponent<JobContextService> {

    private final ClusterService clusterService;
    private final StatsTables statsTables;
    private final ConcurrentMap<UUID, JobExecutionContext> activeContexts =
        ConcurrentCollections.newConcurrentMapWithAggressiveConcurrency();

    private final List<KillAllListener> killAllListeners = Collections.synchronizedList(new ArrayList<KillAllListener>());

    @Inject
    public JobContextService(Settings settings, ClusterService clusterService, StatsTables statsTables) {
        super(settings);
        this.clusterService = clusterService;
        this.statsTables = statsTables;
    }

    @Override
    protected void doStart() throws ElasticsearchException {
    }

    @Override
    protected void doStop() throws ElasticsearchException {
        for (JobExecutionContext context : activeContexts.values()) {
            context.kill();
        }
    }

    public void addListener(KillAllListener listener) {
        killAllListeners.add(listener);
    }

    @Override
    protected void doClose() throws ElasticsearchException {
    }

    public JobExecutionContext getContext(UUID jobId) {
        JobExecutionContext context = activeContexts.get(jobId);
        if (context == null) {
            throw new ContextMissingException(ContextMissingException.ContextType.JOB_EXECUTION_CONTEXT, jobId);
        }
        return context;
    }

    public Stream<UUID> getJobIdsByCoordinatorNode(final String coordinatorNodeId) {
        return activeContexts.values()
            .stream()
            .filter(jobExecutionContext -> jobExecutionContext.coordinatorNodeId().equals(coordinatorNodeId))
            .map(JobExecutionContext::jobId);
    }

    public Stream<UUID> getJobIdsByParticipatingNodes(final String nodeId) {
        return activeContexts.values().stream()
            .filter(i -> i.participatingNodes().contains(nodeId))
            .map(JobExecutionContext::jobId);
    }

    @Nullable
    public JobExecutionContext getContextOrNull(UUID jobId) {
        return activeContexts.get(jobId);
    }

    public JobExecutionContext.Builder newBuilder(UUID jobId) {
        return new JobExecutionContext.Builder(jobId, clusterService.localNode().getId(), Collections.emptyList(), statsTables);
    }

    public JobExecutionContext.Builder newBuilder(UUID jobId, String coordinatorNodeId) {
        return new JobExecutionContext.Builder(jobId, coordinatorNodeId, Collections.emptyList(), statsTables);
    }

    public JobExecutionContext.Builder newBuilder(UUID jobId, String coordinatorNodeId, Collection<String> participatingNodes) {
        return new JobExecutionContext.Builder(jobId, coordinatorNodeId, participatingNodes, statsTables);
    }

    public JobExecutionContext createContext(JobExecutionContext.Builder contextBuilder) throws Exception {
        if (contextBuilder.isEmpty()) {
            throw new IllegalArgumentException("JobExecutionContext.Builder must at least contain 1 SubExecutionContext");
        }
        final UUID jobId = contextBuilder.jobId();
        JobExecutionContext newContext = contextBuilder.build();
        Futures.addCallback(newContext.completionFuture(), new JobContextCallback(jobId));
        JobExecutionContext existing = activeContexts.putIfAbsent(jobId, newContext);
        if (existing != null) {
            throw new IllegalArgumentException(
                String.format(Locale.ENGLISH, "context for job %s already exists:%n%s", jobId, existing));
        }
        if (logger.isTraceEnabled()) {
            logger.trace("JobExecutionContext created for job {},  activeContexts: {}",
                jobId, activeContexts.size());
        }
        return newContext;
    }


    /**
     * kills all contexts which are active at the time of the call of this method.
     *
     * @return a future holding the number of contexts kill was called on, the future is finished when all contexts
     * are completed and never fails.
     */
    public ListenableFuture<Integer> killAll() {
        long now = System.nanoTime();
        for (KillAllListener killAllListener : killAllListeners) {
            try {
                killAllListener.killAllJobs(now);
            } catch (Throwable t) {
                logger.error("Failed to call killAllJobs on listener {}", t, killAllListener);
            }
        }
        Collection<UUID> toKill = ImmutableList.copyOf(activeContexts.keySet());
        if (toKill.isEmpty()) {
            return Futures.immediateFuture(0);
        }
        return killContexts(toKill);
    }

    private ListenableFuture<Integer> killContexts(Collection<UUID> toKill) {
        assert !toKill.isEmpty(): "toKill must not be empty";
        int numKilled = 0;
        CountdownFutureCallback countDownFuture = new CountdownFutureCallback(toKill.size());
        for (UUID jobId : toKill) {
            JobExecutionContext ctx = activeContexts.get(jobId);
            if (ctx != null) {
                Futures.addCallback(ctx.completionFuture(), countDownFuture);
                ctx.kill();
                numKilled++;
            } else {
                // no kill but we need to count down
                countDownFuture.onSuccess(null);
            }
        }
        final SettableFuture<Integer> result = SettableFuture.create();
        final int finalNumKilled = numKilled;
        countDownFuture.addListener(new Runnable() {
            @Override
            public void run() {
                result.set(finalNumKilled);
            }
        }, MoreExecutors.directExecutor());
        return result;
    }

    public ListenableFuture<Integer> killJobs(Collection<UUID> toKill) {
        for (KillAllListener killAllListener : killAllListeners) {
            for (UUID job : toKill) {
                try {
                    killAllListener.killJob(job);
                } catch (Throwable t) {
                    logger.error("Failed to call killJob on listener {}", t, killAllListener);
                }
            }
        }
        return killContexts(toKill);
    }

    private class JobContextCallback implements FutureCallback<Object> {

        private UUID jobId;

        JobContextCallback(UUID jobId) {
            this.jobId = jobId;
        }

        private void remove(@Nullable Throwable throwable) {
            activeContexts.remove(jobId);
            if (logger.isTraceEnabled()) {
                logger.trace("JobExecutionContext closed for job {} removed it -" +
                             " {} executionContexts remaining",
                    throwable, jobId, activeContexts.size());
            }
        }

        @Override
        public void onSuccess(@Nullable Object result) {
            remove(null);
        }

        @Override
        public void onFailure(@Nonnull Throwable throwable) {
            remove(throwable);
        }
    }

}
