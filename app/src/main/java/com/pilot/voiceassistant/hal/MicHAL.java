package com.pilot.voiceassistant.hal;

/**
 * Abstract driver for raw PCM microphone capture, in the format defined by
 * {@link com.pilot.voiceassistant.audio.AudioConfig}.
 *
 * <p>{@link #startRecording()}, {@link #readRawAudioChunk(byte[])} and {@link #stopRecording()}
 * are always called from the same capture thread.
 */
public interface MicHAL {

    /** Opens the microphone. Returns false if it could not be opened. */
    boolean startRecording();

    /**
     * Blocks until audio is available and copies it into {@code buffer}.
     *
     * @return the number of bytes written, or a negative value on failure
     */
    int readRawAudioChunk(byte[] buffer);

    /** Closes the microphone. Safe to call when not recording. */
    void stopRecording();

    void release();
}
