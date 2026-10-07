package com.pilot.voiceassistant.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.hal.MicHAL;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class CaptureLoopTest {

    private static final class TestMic implements MicHAL {
        boolean openSucceeds = true;
        int readResult = AudioConfig.CHUNK_BYTES;
        int starts;
        int stops;

        @Override
        public boolean startRecording() {
            starts++;
            return openSucceeds;
        }

        @Override
        public int readRawAudioChunk(byte[] buffer) {
            return readResult;
        }

        @Override
        public void stopRecording() {
            stops++;
        }

        @Override
        public void release() {
        }
    }

    private final TestMic mic = new TestMic();
    private final List<byte[]> chunks = new ArrayList<>();
    private final List<String> errors = new ArrayList<>();
    private CaptureLoop loop;

    @Test
    public void forwardsChunksUntilStoppedThenClosesTheMicrophone() {
        loop = new CaptureLoop(mic, chunk -> {
            chunks.add(chunk);
            if (chunks.size() == 3) {
                loop.stop();
            }
        }, errors::add);

        loop.run();

        assertEquals(3, chunks.size());
        assertEquals(AudioConfig.CHUNK_BYTES, chunks.get(0).length);
        assertEquals(1, mic.starts);
        assertEquals(1, mic.stops);
        assertTrue(errors.isEmpty());
    }

    @Test
    public void shortReadIsForwardedAtItsRealLength() {
        mic.readResult = 100;
        loop = new CaptureLoop(mic, chunk -> {
            chunks.add(chunk);
            loop.stop();
        }, errors::add);

        loop.run();

        assertEquals(100, chunks.get(0).length);
    }

    @Test
    public void stoppedBeforeItRunsNeverOpensTheMicrophone() {
        loop = new CaptureLoop(mic, chunks::add, errors::add);
        loop.stop();

        loop.run();

        assertEquals(0, mic.starts);
        assertEquals(0, mic.stops);
    }

    @Test
    public void openFailureIsReported() {
        mic.openSucceeds = false;
        loop = new CaptureLoop(mic, chunks::add, errors::add);

        loop.run();

        assertEquals(1, errors.size());
        assertTrue(chunks.isEmpty());
        assertEquals(1, mic.stops);
    }

    @Test
    public void readFailureIsReportedAndClosesTheMicrophone() {
        mic.readResult = -3;
        loop = new CaptureLoop(mic, chunks::add, errors::add);

        loop.run();

        assertEquals(1, errors.size());
        assertTrue(chunks.isEmpty());
        assertEquals(1, mic.stops);
    }
}
