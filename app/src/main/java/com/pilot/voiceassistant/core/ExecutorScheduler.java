package com.pilot.voiceassistant.core;

import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/** {@link Scheduler} backed by one dedicated thread. */
public final class ExecutorScheduler implements Scheduler {

    public interface FailureHandler {
        void onTaskFailed(RuntimeException failure);
    }

    private static final Cancellable NOT_SCHEDULED = () -> { };

    private final ScheduledExecutorService executor;
    private final FailureHandler failureHandler;

    public ExecutorScheduler(String threadName, FailureHandler failureHandler) {
        this.executor = Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, threadName));
        this.failureHandler = failureHandler;
    }

    @Override
    public void post(Runnable task) {
        try {
            executor.execute(guarded(task));
        } catch (RejectedExecutionException ignored) {
            // Shut down: late callbacks from engines are dropped.
        }
    }

    @Override
    public Cancellable postDelayed(Runnable task, long delayMs) {
        try {
            ScheduledFuture<?> future =
                    executor.schedule(guarded(task), delayMs, TimeUnit.MILLISECONDS);
            return () -> future.cancel(false);
        } catch (RejectedExecutionException ignored) {
            return NOT_SCHEDULED;
        }
    }

    /** Stops accepting tasks. Tasks already posted still run; pending delayed tasks do not. */
    public void shutdown() {
        executor.shutdown();
    }

    public boolean awaitTermination(long timeoutMs) throws InterruptedException {
        return executor.awaitTermination(timeoutMs, TimeUnit.MILLISECONDS);
    }

    private Runnable guarded(Runnable task) {
        return () -> {
            try {
                task.run();
            } catch (RuntimeException e) {
                failureHandler.onTaskFailed(e);
            }
        };
    }
}
