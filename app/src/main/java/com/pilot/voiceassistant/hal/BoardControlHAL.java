package com.pilot.voiceassistant.hal;

/**
 * Abstract driver for the board functions the assistant can control. Every method returns false
 * if the board cannot do what was asked. Methods may be called from any thread.
 */
public interface BoardControlHAL {

    enum MediaCommand {
        PLAY,
        PAUSE,
        NEXT,
        PREVIOUS
    }

    /** Raises the media volume by one step. */
    boolean volumeUp();

    /** Lowers the media volume by one step. */
    boolean volumeDown();

    boolean setMuted(boolean muted);

    /** Sends a playback command to the active media player. */
    boolean media(MediaCommand command);

    boolean setRadioOn(boolean on);

    String currentTime();
}
