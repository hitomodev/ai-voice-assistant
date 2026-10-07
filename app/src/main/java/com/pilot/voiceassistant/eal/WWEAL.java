package com.pilot.voiceassistant.eal;

/**
 * Wakeup Word Engine Abstract Layer: analyzes PCM frames to detect the trigger phrase.
 *
 * <p>{@link #initialize()}, {@link #reset()}, {@link #processFrame(byte[])} and
 * {@link #release()} are always called from the same capture thread, and the listener is called
 * on that thread.
 */
public interface WWEAL {

    interface Listener {
        void onWakeWordDetected();

        /** The engine cannot detect anything any more. */
        void onError(String reason);
    }

    void initialize();

    void setWakeWordListener(Listener listener);

    /** Forgets the audio heard so far. Called before each new run of frames. */
    void reset();

    /** Frames may have any length. */
    void processFrame(byte[] pcmData);

    void release();
}
