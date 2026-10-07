package com.pilot.voiceassistant.eal;

import java.util.List;

/**
 * LLM Engine Abstract Layer: sends the conversation to an LLM provider and streams the response
 * text back. Callbacks may arrive on any thread.
 */
public interface LLMEAL {

    interface LLMCallback {
        void onTextChunk(String text);

        void onComplete();

        void onError(String reason);
    }

    /** {@code conversation} ends with the user message to answer. */
    void generateResponse(List<Message> conversation, LLMCallback callback);

    /** Stops the generation without delivering any further callback. */
    void cancelGeneration();

    void release();
}
