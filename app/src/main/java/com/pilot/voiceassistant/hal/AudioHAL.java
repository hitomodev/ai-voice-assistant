package com.pilot.voiceassistant.hal;

/**
 * Abstract driver for speaker output and audio focus, in the format defined by
 * {@link com.pilot.voiceassistant.audio.AudioConfig}.
 */
public interface AudioHAL {

    interface PlaybackListener {
        /** The last buffer queued before {@link #endOfStream()} has been played. */
        void onPlaybackComplete();

        /** Another audio source took focus away. */
        void onAudioFocusLost();
    }

    /** Listener callbacks may arrive on any thread. */
    void setPlaybackListener(PlaybackListener listener);

    /** Returns true if focus was granted. */
    boolean requestAudioFocus();

    void abandonAudioFocus();

    /**
     * Plays the short tone that tells the user the assistant is listening, and returns
     * immediately. It is independent of the response stream.
     */
    void playListeningCue();

    /** Queues a buffer for playback and returns immediately. */
    void playAudioBuffer(byte[] pcmData);

    /**
     * Marks the end of the current response. {@link PlaybackListener#onPlaybackComplete()} fires
     * once everything queued so far has been played.
     */
    void endOfStream();

    /**
     * Stops immediately and drops queued audio. No completion callback is delivered for audio
     * that was queued before this call.
     */
    void stopPlayback();

    void release();
}
