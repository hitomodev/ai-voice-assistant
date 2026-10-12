package com.pilot.voiceassistant.eal;

/**
 * Live conversation Engine Abstract Layer: one engine that hears the user and answers with
 * speech, in place of the STT, LLM and TTS engines. Audio in is in the microphone format of
 * {@link com.pilot.voiceassistant.audio.AudioConfig}; audio out is 16-bit mono PCM at the
 * engine's own sample rate.
 *
 * <p>There is one conversation at a time, from {@link #connect(Listener)} to {@link #close()} or
 * {@link Listener#onClosed(String)}. Every method may be called from any thread.
 */
public interface LiveEAL {

    /** Callbacks may arrive on any thread. None arrives after {@link #close()}. */
    interface Listener {
        /** The conversation is open: the engine now hears the audio that is sent. */
        void onReady();

        /** The next words the engine heard from the user. */
        void onInputTranscript(String text);

        /** The next words of the engine's spoken answer. */
        void onOutputTranscript(String text);

        /** The next piece of the engine's spoken answer. */
        void onAudio(byte[] pcmData);

        /** The engine asks for a tool to be run; answer with {@link #sendToolResult}. */
        void onToolCall(String id, String name);

        /** The engine finished its answer; everything it had to say has been delivered. */
        void onTurnComplete();

        /** The engine dropped the rest of its answer because the user spoke. */
        void onInterrupted();

        /** The conversation ended without {@link #close()} being called. */
        void onClosed(String reason);
    }

    /** Opens a conversation, closing the one before. Audio sent before it is ready is kept. */
    void connect(Listener listener);

    void sendAudio(byte[] pcmData);

    /** Tells the engine that no audio follows for now; sending audio again resumes the stream. */
    void endAudioStream();

    /** Answers {@link Listener#onToolCall}: {@code result} is what the tool did, in words. */
    void sendToolResult(String id, String name, String result);

    /** Ends the conversation. Safe to call when there is none. */
    void close();

    void release();
}
