package com.pilot.voiceassistant.core;

/** Single entry point through which every trigger source reports an activation. */
public interface TriggerListener {

    /** May be called from any thread. */
    void onTrigger(TriggerSource source);
}
