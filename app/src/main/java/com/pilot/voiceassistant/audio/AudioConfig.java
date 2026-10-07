package com.pilot.voiceassistant.audio;

/**
 * The PCM format used on every hop of the pipeline: 16-bit signed little-endian mono. The
 * microphone side and the speaker side each have their own sample rate.
 */
public final class AudioConfig {

    /** Microphone, wake word and STT. */
    public static final int SAMPLE_RATE_HZ = 16000;
    /** TTS and speaker: the rate of the TTS voice. */
    public static final int PLAYBACK_SAMPLE_RATE_HZ = 22050;
    public static final int CHANNEL_COUNT = 1;
    public static final int BYTES_PER_SAMPLE = 2;
    public static final int BYTES_PER_SECOND = SAMPLE_RATE_HZ * CHANNEL_COUNT * BYTES_PER_SAMPLE;

    /** Size of one microphone chunk handed to the engines. */
    public static final int CHUNK_MS = 20;
    public static final int CHUNK_BYTES = BYTES_PER_SECOND * CHUNK_MS / 1000;

    private AudioConfig() {
    }
}
