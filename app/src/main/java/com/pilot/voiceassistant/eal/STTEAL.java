package com.pilot.voiceassistant.eal;

/**
 * Speech-to-Text Engine Abstract Layer: converts streamed audio into a transcript and detects end
 * of speech.
 *
 * <p>{@link #feedAudioChunk(byte[])} is called from the capture thread; the other methods are
 * called from the control thread. Audio fed while no transcription is active must be ignored.
 * Callbacks may arrive on any thread.
 */
public interface STTEAL {

    interface STTCallback {
        /** The words recognized so far in the utterance; they may still change. Optional. */
        default void onPartialTranscript(String text) {
        }

        /** The user stopped talking. The final transcript follows. */
        void onEndOfSpeech();

        void onFinalTranscript(String text);

        void onError(String reason);
    }

    void startTranscription(STTCallback callback);

    void feedAudioChunk(byte[] pcmData);

    /** Manual stop: ends the utterance now and delivers the transcript of what was heard. */
    void finishTranscription();

    /** Ends the transcription without delivering any further callback. */
    void cancel();

    void release();
}
