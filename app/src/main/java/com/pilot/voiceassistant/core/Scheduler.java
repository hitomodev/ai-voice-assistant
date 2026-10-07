package com.pilot.voiceassistant.core;

/** Runs tasks one at a time, in order, on the control thread. */
public interface Scheduler {

    interface Cancellable {
        void cancel();
    }

    void post(Runnable task);

    Cancellable postDelayed(Runnable task, long delayMs);
}
