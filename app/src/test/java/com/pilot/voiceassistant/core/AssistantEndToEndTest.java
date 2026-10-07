package com.pilot.voiceassistant.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.pilot.voiceassistant.eal.fake.FakeLLMEAL;
import com.pilot.voiceassistant.eal.fake.FakeSTTEAL;
import com.pilot.voiceassistant.eal.fake.FakeTTSEAL;
import com.pilot.voiceassistant.hal.AudioHAL;
import com.pilot.voiceassistant.hal.MicHAL;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the same wiring as the service (real threads, the stand-in engines) with the Android
 * audio hardware replaced by JVM doubles.
 */
public class AssistantEndToEndTest {

    private static final long WAIT_MS = 10_000;

    /** Delivers silence as fast as it is asked, like a microphone in a quiet room. */
    private static final class SilentMic implements MicHAL {
        final AtomicInteger starts = new AtomicInteger();
        final AtomicInteger stops = new AtomicInteger();

        @Override
        public boolean startRecording() {
            starts.incrementAndGet();
            return true;
        }

        @Override
        public int readRawAudioChunk(byte[] buffer) {
            try {
                Thread.sleep(1);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return -1;
            }
            Arrays.fill(buffer, (byte) 0);
            return buffer.length;
        }

        @Override
        public void stopRecording() {
            stops.incrementAndGet();
        }

        @Override
        public void release() {
        }
    }

    /** Counts what it is asked to play and completes as soon as the stream ends. */
    private static final class InstantAudio implements AudioHAL {
        final AtomicLong playedBytes = new AtomicLong();
        final AtomicInteger stops = new AtomicInteger();
        volatile PlaybackListener listener;

        @Override
        public void setPlaybackListener(PlaybackListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean requestAudioFocus() {
            return true;
        }

        @Override
        public void abandonAudioFocus() {
        }

        @Override
        public void playListeningCue() {
        }

        @Override
        public void playAudioBuffer(byte[] pcmData) {
            playedBytes.addAndGet(pcmData.length);
        }

        @Override
        public void endOfStream() {
            listener.onPlaybackComplete();
        }

        @Override
        public void stopPlayback() {
            stops.incrementAndGet();
        }

        @Override
        public void release() {
        }
    }

    private final SilentMic mic = new SilentMic();
    private final InstantAudio audio = new InstantAudio();
    private final BlockingQueue<AssistantState> states = new LinkedBlockingQueue<>();
    private final List<String> transcripts = Collections.synchronizedList(new ArrayList<>());
    private final List<String> errors = Collections.synchronizedList(new ArrayList<>());
    private final List<Throwable> failures = Collections.synchronizedList(new ArrayList<>());
    private volatile String response = "";

    private ExecutorScheduler scheduler;
    private ExecutorService captureExecutor;
    private AssistantController controller;

    @Before
    public void setUp() {
        scheduler = new ExecutorScheduler("test-control", failures::add);
        captureExecutor = Executors.newSingleThreadExecutor();
        controller = new AssistantController(mic, audio, null,
                new FakeSTTEAL("hello assistant", 200), new FakeLLMEAL(20), new FakeTTSEAL(),
                scheduler, captureExecutor, new AssistantController.Listener() {
                    @Override
                    public void onStateChanged(AssistantState state) {
                        states.add(state);
                    }

                    @Override
                    public void onTurnStarted(TriggerSource source) {
                    }

                    @Override
                    public void onPartialTranscript(String textSoFar) {
                    }

                    @Override
                    public void onTranscript(String transcript) {
                        transcripts.add(transcript);
                    }

                    @Override
                    public void onResponseText(String responseSoFar) {
                        response = responseSoFar;
                    }

                    @Override
                    public void onError(String reason) {
                        errors.add(reason);
                    }
                });
    }

    @After
    public void tearDown() throws InterruptedException {
        shutDown();
    }

    private void shutDown() throws InterruptedException {
        controller.release();
        scheduler.shutdown();
        assertTrue(scheduler.awaitTermination(WAIT_MS));
        captureExecutor.shutdown();
        assertTrue(captureExecutor.awaitTermination(WAIT_MS, TimeUnit.MILLISECONDS));
    }

    private void expectStates(AssistantState... expected) throws InterruptedException {
        for (AssistantState state : expected) {
            assertEquals(state, states.poll(WAIT_MS, TimeUnit.MILLISECONDS));
        }
    }

    @Test
    public void oneTurn_fromButtonToPlaybackComplete() throws InterruptedException {
        controller.onTrigger(TriggerSource.UI_BUTTON);

        expectStates(AssistantState.LISTENING, AssistantState.THINKING,
                AssistantState.SPEAKING, AssistantState.STANDBY);

        assertEquals(Collections.singletonList("hello assistant"), transcripts);
        assertTrue(response, response.startsWith("You said: hello assistant This is turn 1 "));
        assertTrue(response, response.endsWith("phase three."));
        assertTrue(audio.playedBytes.get() > 0);
        assertTrue(errors.toString(), errors.isEmpty());

        shutDown();
        assertEquals(1, mic.starts.get());
        assertEquals(1, mic.stops.get());
        assertTrue(failures.toString(), failures.isEmpty());
        assertTrue(states.toString(), states.isEmpty());
    }

    @Test
    public void bargeInWhileSpeaking_runsASecondTurnWithHistory() throws InterruptedException {
        controller.onTrigger(TriggerSource.UI_BUTTON);
        expectStates(AssistantState.LISTENING, AssistantState.THINKING, AssistantState.SPEAKING);

        controller.onTrigger(TriggerSource.UI_BUTTON);
        expectStates(AssistantState.LISTENING, AssistantState.THINKING,
                AssistantState.SPEAKING, AssistantState.STANDBY);

        assertEquals(Arrays.asList("hello assistant", "hello assistant"), transcripts);
        assertTrue(response, response.contains("This is turn 2 "));
        assertEquals(1, audio.stops.get());
        assertTrue(errors.toString(), errors.isEmpty());

        shutDown();
        assertEquals(2, mic.starts.get());
        assertEquals(2, mic.stops.get());
        assertTrue(failures.toString(), failures.isEmpty());
        assertTrue(states.toString(), states.isEmpty());
    }

    @Test
    public void twoTurnsInARow_reuseTheMicrophoneCleanly() throws InterruptedException {
        for (int turn = 1; turn <= 2; turn++) {
            controller.onTrigger(TriggerSource.UI_BUTTON);
            expectStates(AssistantState.LISTENING, AssistantState.THINKING,
                    AssistantState.SPEAKING, AssistantState.STANDBY);
            assertTrue(response, response.contains("This is turn " + turn + " "));
        }

        shutDown();
        assertEquals(2, mic.starts.get());
        assertEquals(2, mic.stops.get());
        assertTrue(errors.toString(), errors.isEmpty());
        assertTrue(failures.toString(), failures.isEmpty());
    }
}
