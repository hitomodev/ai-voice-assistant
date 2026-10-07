package com.pilot.voiceassistant.eal.fake;

import com.pilot.voiceassistant.eal.LLMEAL;
import com.pilot.voiceassistant.eal.Message;

import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Stand-in LLM engine. It streams a canned reply word by word. The reply quotes the last user
 * message and the turn number, so the transcript and the conversation history are visibly wired
 * through.
 */
public final class FakeLLMEAL implements LLMEAL {

    private static final long DEFAULT_WORD_DELAY_MS = 80;

    private final long wordDelayMs;
    private final ScheduledExecutorService executor =
            Executors.newSingleThreadScheduledExecutor(r -> new Thread(r, "fake-llm"));

    private volatile int generation;

    public FakeLLMEAL() {
        this(DEFAULT_WORD_DELAY_MS);
    }

    public FakeLLMEAL(long wordDelayMs) {
        this.wordDelayMs = wordDelayMs;
    }

    @Override
    public void generateResponse(List<Message> conversation, LLMCallback callback) {
        final int myGeneration = ++generation;
        final String[] words = buildReply(conversation).split(" ");
        for (int i = 0; i < words.length; i++) {
            final String chunk = i == 0 ? words[i] : " " + words[i];
            executor.schedule(() -> {
                if (myGeneration == generation) {
                    callback.onTextChunk(chunk);
                }
            }, wordDelayMs * (i + 1), TimeUnit.MILLISECONDS);
        }
        executor.schedule(() -> {
            if (myGeneration == generation) {
                callback.onComplete();
            }
        }, wordDelayMs * (words.length + 1), TimeUnit.MILLISECONDS);
    }

    static String buildReply(List<Message> conversation) {
        int userTurns = 0;
        String lastUserText = "";
        for (Message message : conversation) {
            if (message.getRole() == Message.Role.USER) {
                userTurns++;
                lastUserText = message.getText();
            }
        }
        return "You said: " + lastUserText + " This is turn " + userTurns
                + " of the pilot assistant. The real engines arrive in phase three.";
    }

    @Override
    public void cancelGeneration() {
        generation++;
    }

    @Override
    public void release() {
        cancelGeneration();
        executor.shutdownNow();
    }
}
