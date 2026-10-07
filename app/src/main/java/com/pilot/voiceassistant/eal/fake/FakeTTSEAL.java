package com.pilot.voiceassistant.eal.fake;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.TTSEAL;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Stand-in TTS engine. Each text chunk becomes a tone whose length follows the text length, with
 * a different pitch per chunk so the sentence boundaries are audible.
 */
public final class FakeTTSEAL implements TTSEAL {

    private static final int MS_PER_CHARACTER = 60;
    private static final int MAX_TONE_MS = 4000;
    private static final int BUFFER_MS = 100;
    private static final int FADE_MS = 10;
    private static final double AMPLITUDE = 0.2;
    private static final double[] PITCHES_HZ = {440.0, 554.4, 659.3};

    private final ExecutorService executor =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "fake-tts"));

    private volatile int generation;
    private TTSCallback callback;
    private int chunkIndex;

    @Override
    public synchronized void startSynthesis(TTSCallback callback) {
        generation++;
        this.callback = callback;
        this.chunkIndex = 0;
    }

    @Override
    public synchronized void feedText(String textChunk) {
        if (callback == null) {
            return;
        }
        final int myGeneration = generation;
        final TTSCallback target = callback;
        final double pitchHz = PITCHES_HZ[chunkIndex++ % PITCHES_HZ.length];
        final int toneMs = Math.min(MAX_TONE_MS, textChunk.length() * MS_PER_CHARACTER);
        executor.execute(() -> synthesize(myGeneration, target, pitchHz, toneMs));
    }

    @Override
    public synchronized void finishInput() {
        if (callback == null) {
            return;
        }
        final int myGeneration = generation;
        final TTSCallback target = callback;
        callback = null;
        executor.execute(() -> {
            if (myGeneration == generation) {
                target.onComplete();
            }
        });
    }

    @Override
    public synchronized void stopSynthesis() {
        generation++;
        callback = null;
    }

    @Override
    public void release() {
        stopSynthesis();
        executor.shutdownNow();
    }

    private void synthesize(int myGeneration, TTSCallback target, double pitchHz, int toneMs) {
        int totalSamples = AudioConfig.PLAYBACK_SAMPLE_RATE_HZ * toneMs / 1000;
        int samplesPerBuffer = AudioConfig.PLAYBACK_SAMPLE_RATE_HZ * BUFFER_MS / 1000;
        int fadeSamples = AudioConfig.PLAYBACK_SAMPLE_RATE_HZ * FADE_MS / 1000;
        for (int start = 0; start < totalSamples; start += samplesPerBuffer) {
            if (myGeneration != generation) {
                return;
            }
            int count = Math.min(samplesPerBuffer, totalSamples - start);
            byte[] pcm = new byte[count * AudioConfig.BYTES_PER_SAMPLE];
            for (int i = 0; i < count; i++) {
                int n = start + i;
                double fade = Math.min(1.0,
                        Math.min(n, totalSamples - 1 - n) / (double) fadeSamples);
                double value = Math.sin(2 * Math.PI * pitchHz * n / AudioConfig.PLAYBACK_SAMPLE_RATE_HZ);
                short sample = (short) (value * fade * AMPLITUDE * Short.MAX_VALUE);
                pcm[2 * i] = (byte) (sample & 0xff);
                pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
            }
            target.onAudioBuffer(pcm);
        }
    }
}
