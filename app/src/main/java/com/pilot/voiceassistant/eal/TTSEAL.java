package com.pilot.voiceassistant.eal;

/**
 * Text-to-Speech Engine Abstract Layer: converts text to PCM audio, accepting text incrementally.
 * Callbacks may arrive on any thread.
 */
public interface TTSEAL {

    interface TTSCallback {
        void onAudioBuffer(byte[] pcmData);

        /** All text fed before {@link #finishInput()} has been synthesized. */
        void onComplete();

        void onError(String reason);
    }

    void startSynthesis(TTSCallback callback);

    void feedText(String textChunk);

    /** No more text will be fed for this synthesis. */
    void finishInput();

    /** Stops the synthesis without delivering any further callback. */
    void stopSynthesis();

    void release();
}
