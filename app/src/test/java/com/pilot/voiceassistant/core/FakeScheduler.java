package com.pilot.voiceassistant.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.Iterator;
import java.util.List;

/** Deterministic {@link Scheduler}: tasks run only when the test asks, on the test thread. */
final class FakeScheduler implements Scheduler {

    private static final class Delayed {
        final Runnable task;
        final long dueMs;
        boolean cancelled;

        Delayed(Runnable task, long dueMs) {
            this.task = task;
            this.dueMs = dueMs;
        }
    }

    private final Deque<Runnable> queue = new ArrayDeque<>();
    private final List<Delayed> delayed = new ArrayList<>();
    private long nowMs;

    @Override
    public void post(Runnable task) {
        queue.add(task);
    }

    @Override
    public Cancellable postDelayed(Runnable task, long delayMs) {
        Delayed entry = new Delayed(task, nowMs + delayMs);
        delayed.add(entry);
        return () -> entry.cancelled = true;
    }

    /** Runs posted tasks, including the ones they post, until none are left. */
    void runAll() {
        while (!queue.isEmpty()) {
            queue.poll().run();
        }
    }

    /** Moves the clock forward and runs everything that became due. */
    void advance(long ms) {
        nowMs += ms;
        for (Iterator<Delayed> it = delayed.iterator(); it.hasNext(); ) {
            Delayed entry = it.next();
            if (entry.dueMs <= nowMs) {
                it.remove();
                if (!entry.cancelled) {
                    queue.add(entry.task);
                }
            }
        }
        runAll();
    }
}
