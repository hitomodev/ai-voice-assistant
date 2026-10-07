package com.pilot.voiceassistant.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class SentenceChunkerTest {

    private final SentenceChunker chunker = new SentenceChunker();

    @Test
    public void sentenceIsHeldUntilItsTerminatorIsFollowedByWhitespace() {
        assertTrue(chunker.append("Hello").isEmpty());
        assertTrue(chunker.append(" world.").isEmpty());
        assertEquals(Collections.singletonList("Hello world."), chunker.append(" Next"));
        assertEquals("Next", chunker.flush());
    }

    @Test
    public void severalSentencesInOneChunk() {
        assertEquals(Arrays.asList("One.", "Two!", "Three?"),
                chunker.append("One. Two! Three? Four"));
        assertEquals("Four", chunker.flush());
    }

    @Test
    public void decimalPointDoesNotSplit() {
        assertTrue(chunker.append("It is 3.5 degrees").isEmpty());
        assertEquals("It is 3.5 degrees", chunker.flush());
    }

    @Test
    public void lineBreakSplits() {
        assertEquals(Collections.singletonList("First line"), chunker.append("First line\nsecond"));
        assertEquals("second", chunker.flush());
    }

    @Test
    public void blankPiecesAreDropped() {
        assertTrue(chunker.append(" \n \n").isEmpty());
        assertEquals("", chunker.flush());
    }

    @Test
    public void flushEmptiesTheBuffer() {
        chunker.append("Leftover");
        assertEquals("Leftover", chunker.flush());
        assertEquals("", chunker.flush());
    }
}
