package com.pilot.voiceassistant.eal.fake;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.STTEAL;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class FakeSTTEALTest {

    private final List<String> events = new ArrayList<>();
    private final STTEAL.STTCallback callback = new STTEAL.STTCallback() {
        @Override
        public void onEndOfSpeech() {
            events.add("endOfSpeech");
        }

        @Override
        public void onFinalTranscript(String text) {
            events.add("transcript:" + text);
        }

        @Override
        public void onError(String reason) {
            events.add("error:" + reason);
        }
    };

    // 100 ms utterance = 5 chunks of 20 ms.
    private final FakeSTTEAL stt = new FakeSTTEAL("hello", 100);
    private final byte[] chunk = new byte[AudioConfig.CHUNK_BYTES];

    @Test
    public void transcriptArrivesOnceEnoughAudioWasFed() {
        stt.startTranscription(callback);
        for (int i = 0; i < 4; i++) {
            stt.feedAudioChunk(chunk);
        }
        assertTrue(events.isEmpty());

        stt.feedAudioChunk(chunk);
        assertEquals(Arrays.asList("endOfSpeech", "transcript:hello"), events);

        stt.feedAudioChunk(chunk);
        assertEquals(2, events.size());
    }

    @Test
    public void audioIsIgnoredWhenNoTranscriptionIsActive() {
        for (int i = 0; i < 10; i++) {
            stt.feedAudioChunk(chunk);
        }
        assertTrue(events.isEmpty());
    }

    @Test
    public void cancelSuppressesTheTranscript() {
        stt.startTranscription(callback);
        stt.feedAudioChunk(chunk);
        stt.cancel();
        for (int i = 0; i < 10; i++) {
            stt.feedAudioChunk(chunk);
        }
        assertTrue(events.isEmpty());
    }

    @Test
    public void manualFinishDeliversTheTranscriptEarly() {
        stt.startTranscription(callback);
        stt.feedAudioChunk(chunk);
        stt.finishTranscription();
        assertEquals(Arrays.asList("endOfSpeech", "transcript:hello"), events);
    }

    @Test
    public void manualFinishWithoutAudioDeliversAnEmptyTranscript() {
        stt.startTranscription(callback);
        stt.finishTranscription();
        assertEquals(Arrays.asList("endOfSpeech", "transcript:"), events);
    }
}
