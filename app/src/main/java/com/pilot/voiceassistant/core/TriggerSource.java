package com.pilot.voiceassistant.core;

public enum TriggerSource {
    UI_BUTTON,
    HARDWARE_KEY,
    WAKE_WORD;

    /** Buttons are deliberate user actions; the wake word can also be a false detection. */
    public boolean isButton() {
        return this != WAKE_WORD;
    }
}
