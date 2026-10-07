package com.pilot.voiceassistant.core;

import java.util.ArrayList;
import java.util.List;

/**
 * Groups streamed LLM text into sentences for TTS. A sentence ends at '.', '!' or '?' followed
 * by whitespace, or at a line break.
 */
final class SentenceChunker {

    private final StringBuilder pending = new StringBuilder();

    /** Adds streamed text and returns the sentences it completed, in order. */
    List<String> append(String text) {
        pending.append(text);
        List<String> sentences = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < pending.length(); i++) {
            char c = pending.charAt(i);
            boolean lineBreak = c == '\n';
            boolean terminator = (c == '.' || c == '!' || c == '?')
                    && i + 1 < pending.length()
                    && Character.isWhitespace(pending.charAt(i + 1));
            if (lineBreak || terminator) {
                addIfNotBlank(sentences, pending.substring(start, i + 1));
                start = i + 1;
            }
        }
        pending.delete(0, start);
        return sentences;
    }

    /** Returns the text left over at the end of the response, or an empty string. */
    String flush() {
        String rest = pending.toString().trim();
        pending.setLength(0);
        return rest;
    }

    private static void addIfNotBlank(List<String> sentences, String sentence) {
        String trimmed = sentence.trim();
        if (!trimmed.isEmpty()) {
            sentences.add(trimmed);
        }
    }
}
