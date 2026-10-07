package com.pilot.voiceassistant.eal.fake;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.STTEAL;

/**
 * Stand-in STT engine. It does not recognize speech: once it has received a fixed amount of
 * microphone audio it reports end of speech and a canned transcript. Because it is driven by the
 * audio it is fed, no transcript is produced when the microphone path is broken.
 */
public final class FakeSTTEAL implements STTEAL {

    public static final String DEFAULT_TRANSCRIPT = "What is the weather today?";
    private static final long DEFAULT_UTTERANCE_MS = 2500;

    private final String transcript;
    private final long utteranceBytes;

    private STTCallback callback;
    private long receivedBytes;

    public FakeSTTEAL() {
        this(DEFAULT_TRANSCRIPT, DEFAULT_UTTERANCE_MS);
    }

    public FakeSTTEAL(String transcript, long utteranceMs) {
        this.transcript = transcript;
        this.utteranceBytes = AudioConfig.BYTES_PER_SECOND * utteranceMs / 1000;
    }

    @Override
    public synchronized void startTranscription(STTCallback callback) {
        this.callback = callback;
        this.receivedBytes = 0;
    }

    @Override
    public void feedAudioChunk(byte[] pcmData) {
        STTCallback done;
        synchronized (this) {
            if (callback == null) {
                return;
            }
            receivedBytes += pcmData.length;
            if (receivedBytes < utteranceBytes) {
                return;
            }
            done = callback;
            callback = null;
        }
        done.onEndOfSpeech();
        done.onFinalTranscript(transcript);
    }

    @Override
    public void finishTranscription() {
        STTCallback done;
        boolean heardAudio;
        synchronized (this) {
            if (callback == null) {
                return;
            }
            done = callback;
            callback = null;
            heardAudio = receivedBytes > 0;
        }
        done.onEndOfSpeech();
        done.onFinalTranscript(heardAudio ? transcript : "");
    }

    @Override
    public synchronized void cancel() {
        callback = null;
    }

    @Override
    public void release() {
        cancel();
    }
}
