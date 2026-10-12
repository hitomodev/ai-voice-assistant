package com.pilot.voiceassistant.core;

import com.pilot.voiceassistant.eal.LiveEAL;
import com.pilot.voiceassistant.eal.WWEAL;
import com.pilot.voiceassistant.hal.AudioHAL;
import com.pilot.voiceassistant.hal.MicHAL;
import com.pilot.voiceassistant.tools.Tool;

import java.util.List;
import java.util.concurrent.Executor;

/**
 * Coordinates the assistant that talks through a live conversation engine. The wake word or a
 * button opens a conversation; it then goes back and forth without the wake word, and ends after
 * {@link #SILENCE_TIMEOUT_MS} without speech or on a button.
 *
 * <pre>
 * STANDBY ──trigger──► LISTENING ──answer starts──► SPEAKING
 *    ▲                  │    ▲ ▲                       │
 *    └──silence, button─┘    │ └────answer played──────┘
 *                            └──► THINKING (a tool runs) ──► SPEAKING
 * </pre>
 *
 * <p>The microphone is only sent to the engine in {@code LISTENING}, so the engine never hears
 * its own answer. In {@code STANDBY} the microphone feeds the wake word engine.
 *
 * <p>All state lives on the control thread ({@link Scheduler}). Engine and hardware callbacks are
 * posted to it together with the session they belong to, and dropped if that session is over.
 */
public final class LiveAssistantController implements TriggerListener {

    /** Receives updates on the control thread. */
    public interface Listener {
        void onStateChanged(AssistantState state);

        /** A conversation started; called after the change to {@code LISTENING}. */
        void onSessionStarted(TriggerSource source);

        /** What the user said so far in the current exchange. */
        void onUserText(String textSoFar);

        /** What the assistant answered so far in the current exchange. */
        void onResponseText(String textSoFar);

        /** A tool was run for the engine. */
        void onToolRun(String name, String result);

        void onError(String reason);
    }

    static final long SILENCE_TIMEOUT_MS = 10_000;
    static final long CONNECT_TIMEOUT_MS = 15_000;
    /** After a tool result, the engine has this long to start its answer. */
    static final long ANSWER_TIMEOUT_MS = 15_000;

    private static final String UNKNOWN_TOOL_RESULT = "This function does not exist.";
    private static final String FAILED_TOOL_RESULT = "The function failed.";

    private final MicHAL mic;
    private final AudioHAL audio;
    private final WWEAL wakeWord;
    private final LiveEAL live;
    private final List<Tool> tools;
    private final Scheduler scheduler;
    private final Executor captureExecutor;
    private final Listener listener;

    private AssistantState state = AssistantState.STANDBY;
    private final StringBuilder userText = new StringBuilder();
    private final StringBuilder responseText = new StringBuilder();
    /** The next words belong to a new exchange: the texts of the one before are cleared. */
    private boolean exchangeOver;
    private CaptureLoop captureLoop;
    private Scheduler.Cancellable timeout;
    private boolean wakeWordRunning;
    private CaptureLoop standbyLoop;
    /** Identifies the current standby capture, so errors of an earlier one are dropped. */
    private long standbyRun;
    private boolean released;

    /** Written on the control thread only; read by callbacks on any thread. */
    private volatile long sessionId;
    /** Whether microphone chunks go to the engine. Read on the capture thread. */
    private volatile boolean micOpen;

    /**
     * @param wakeWord may be null: the microphone is then idle in {@code STANDBY}
     * @param captureExecutor runs capture loops one at a time on a thread of its own
     */
    public LiveAssistantController(MicHAL mic, AudioHAL audio, WWEAL wakeWord, LiveEAL live,
            List<Tool> tools, Scheduler scheduler, Executor captureExecutor, Listener listener) {
        this.mic = mic;
        this.audio = audio;
        this.wakeWord = wakeWord;
        this.live = live;
        this.tools = tools;
        this.scheduler = scheduler;
        this.captureExecutor = captureExecutor;
        this.listener = listener;

        if (wakeWord != null) {
            wakeWord.setWakeWordListener(new WWEAL.Listener() {
                @Override
                public void onWakeWordDetected() {
                    onTrigger(TriggerSource.WAKE_WORD);
                }

                @Override
                public void onError(String reason) {
                    scheduler.post(() -> handleWakeWordError(reason));
                }
            });
        }

        audio.setPlaybackListener(new AudioHAL.PlaybackListener() {
            @Override
            public void onPlaybackComplete() {
                postForSession(sessionId, LiveAssistantController.this::handlePlaybackComplete);
            }

            @Override
            public void onAudioFocusLost() {
                postForSession(sessionId, () -> endSession("audio focus lost"));
            }
        });
    }

    /**
     * Starts wake word detection: from now on the microphone feeds the wake word engine whenever
     * the assistant is in {@code STANDBY}. Call it once the microphone may be opened. Does
     * nothing without a wake word engine, or when already started.
     */
    public void startWakeWord() {
        scheduler.post(() -> {
            if (released || wakeWord == null || wakeWordRunning) {
                return;
            }
            wakeWordRunning = true;
            captureExecutor.execute(wakeWord::initialize);
            if (state == AssistantState.STANDBY) {
                startStandbyCapture();
            }
        });
    }

    /** In {@code STANDBY} a trigger opens a conversation; during one, a button ends it. */
    @Override
    public void onTrigger(TriggerSource source) {
        scheduler.post(() -> {
            if (released) {
                return;
            }
            if (state == AssistantState.STANDBY) {
                startSession(source);
            } else if (source.isButton()) {
                endSession(null);
            }
        });
    }

    /** Ends a conversation in progress and releases the engines and hardware. */
    public void release() {
        scheduler.post(() -> {
            if (released) {
                return;
            }
            released = true;
            stopStandbyCapture();
            endSession(null);
            live.release();
            audio.release();
            // Queued behind the capture loop, so the microphone is closed before it is released.
            if (wakeWord != null) {
                captureExecutor.execute(wakeWord::release);
            }
            captureExecutor.execute(mic::release);
        });
    }

    // ---- Conversation (control thread) ----

    private void startSession(TriggerSource source) {
        final long session = ++sessionId;
        setState(AssistantState.LISTENING);

        // Focus is held for the whole conversation, so that other audio is ducked or paused.
        audio.requestAudioFocus();
        audio.playListeningCue();
        exchangeOver = true;
        micOpen = true;

        live.connect(new LiveEAL.Listener() {
            @Override
            public void onReady() {
                postForSession(session, LiveAssistantController.this::handleReady);
            }

            @Override
            public void onInputTranscript(String text) {
                postForSession(session, () -> handleInputTranscript(text));
            }

            @Override
            public void onOutputTranscript(String text) {
                postForSession(session, () -> handleOutputTranscript(text));
            }

            @Override
            public void onAudio(byte[] pcmData) {
                postForSession(session, () -> handleAudio(pcmData));
            }

            @Override
            public void onToolCall(String id, String name) {
                postForSession(session, () -> handleToolCall(id, name));
            }

            @Override
            public void onTurnComplete() {
                postForSession(session, LiveAssistantController.this::handleTurnComplete);
            }

            @Override
            public void onInterrupted() {
                postForSession(session, LiveAssistantController.this::handleInterrupted);
            }

            @Override
            public void onClosed(String reason) {
                postForSession(session, () -> endSession(reason));
            }
        });

        CaptureLoop.Sink toEngine = pcmData -> {
            if (micOpen && session == sessionId) {
                live.sendAudio(pcmData);
            }
        };
        CaptureLoop.ErrorHandler onCaptureError =
                reason -> postForSession(session, () -> endSession(reason));
        // The standby capture is taken over with the microphone open, so that what the user
        // says right after the wake word is not lost; the engine keeps it until it is connected.
        CaptureLoop standby = standbyLoop;
        standbyLoop = null;
        standbyRun++;
        if (standby != null && standby.redirect(toEngine, onCaptureError)) {
            captureLoop = standby;
        } else {
            captureLoop = new CaptureLoop(mic, toEngine, onCaptureError);
            captureExecutor.execute(captureLoop);
        }

        armTimeout(CONNECT_TIMEOUT_MS, () -> endSession("the live engine did not answer"));
        listener.onSessionStarted(source);
    }

    /** Back to {@code STANDBY}, from any other state. Reports {@code error} unless it is null. */
    private void endSession(String error) {
        if (state == AssistantState.STANDBY) {
            return;
        }
        sessionId++;
        micOpen = false;
        cancelTimeout();
        if (captureLoop != null) {
            captureLoop.stop();
            captureLoop = null;
        }
        live.close();
        audio.stopPlayback();
        audio.abandonAudioFocus();
        setState(AssistantState.STANDBY);
        if (error != null) {
            listener.onError(error);
        }
    }

    private void handleReady() {
        armSilenceTimeout();
    }

    private void handleInputTranscript(String text) {
        if (state == AssistantState.LISTENING) {
            // The user is talking, so the silence timeout starts over.
            armSilenceTimeout();
        }
        startExchangeIfOver();
        userText.append(text);
        listener.onUserText(userText.toString().trim());
    }

    private void handleOutputTranscript(String text) {
        startExchangeIfOver();
        responseText.append(text);
        listener.onResponseText(responseText.toString().trim());
    }

    private void handleAudio(byte[] pcmData) {
        startExchangeIfOver();
        if (state != AssistantState.SPEAKING) {
            closeMic();
            cancelTimeout();
            setState(AssistantState.SPEAKING);
        }
        audio.playAudioBuffer(pcmData);
    }

    /** The tools are quick, so they run here; the engine answers once it has the result. */
    private void handleToolCall(String id, String name) {
        if (state == AssistantState.LISTENING) {
            closeMic();
            setState(AssistantState.THINKING);
        }
        String result = runTool(name);
        listener.onToolRun(name, result);
        live.sendToolResult(id, name, result);
        if (state == AssistantState.THINKING) {
            armTimeout(ANSWER_TIMEOUT_MS, this::resumeListening);
        }
    }

    private String runTool(String name) {
        for (Tool tool : tools) {
            if (tool.getName().equals(name)) {
                try {
                    return tool.run();
                } catch (RuntimeException e) {
                    return FAILED_TOOL_RESULT;
                }
            }
        }
        return UNKNOWN_TOOL_RESULT;
    }

    private void handleTurnComplete() {
        if (state == AssistantState.SPEAKING) {
            // The answer is complete; the user's turn starts when it has been played.
            audio.endOfStream();
        } else if (state == AssistantState.THINKING) {
            resumeListening();
        }
    }

    private void handlePlaybackComplete() {
        if (state == AssistantState.SPEAKING) {
            resumeListening();
        }
    }

    private void handleInterrupted() {
        if (state == AssistantState.SPEAKING) {
            audio.stopPlayback();
            resumeListening();
        }
    }

    /** The user's turn again: the cue tells them so. */
    private void resumeListening() {
        setState(AssistantState.LISTENING);
        exchangeOver = true;
        audio.playListeningCue();
        micOpen = true;
        armSilenceTimeout();
    }

    private void closeMic() {
        if (micOpen) {
            micOpen = false;
            live.endAudioStream();
        }
    }

    private void startExchangeIfOver() {
        if (!exchangeOver) {
            return;
        }
        exchangeOver = false;
        userText.setLength(0);
        responseText.setLength(0);
        listener.onUserText("");
        listener.onResponseText("");
    }

    private void setState(AssistantState newState) {
        state = newState;
        listener.onStateChanged(newState);
        if (newState == AssistantState.STANDBY) {
            startStandbyCapture();
        }
    }

    // ---- Wake word (control thread) ----

    /**
     * Queued behind the capture loop of the conversation that just ended, so it opens the
     * microphone after that one closed it.
     */
    private void startStandbyCapture() {
        if (!wakeWordRunning || released) {
            return;
        }
        stopStandbyCapture();
        final long run = standbyRun;
        CaptureLoop loop = new CaptureLoop(mic, wakeWord::processFrame,
                reason -> scheduler.post(() -> handleStandbyCaptureError(run, reason)));
        standbyLoop = loop;
        captureExecutor.execute(() -> {
            wakeWord.reset();
            loop.run();
        });
    }

    private void stopStandbyCapture() {
        standbyRun++;
        if (standbyLoop != null) {
            standbyLoop.stop();
            standbyLoop = null;
        }
    }

    /** The wake word stays off until the next return to {@code STANDBY}; buttons still work. */
    private void handleStandbyCaptureError(long run, String reason) {
        if (run != standbyRun || released) {
            return;
        }
        stopStandbyCapture();
        listener.onError("wake word: " + reason);
    }

    /** The engine is broken, so the wake word is switched off; buttons still work. */
    private void handleWakeWordError(String reason) {
        if (!wakeWordRunning || released) {
            return;
        }
        wakeWordRunning = false;
        stopStandbyCapture();
        listener.onError("wake word: " + reason);
    }

    // ---- Timeouts and sessions (control thread) ----

    /** Ends the conversation if no speech is heard for {@link #SILENCE_TIMEOUT_MS} from now. */
    private void armSilenceTimeout() {
        armTimeout(SILENCE_TIMEOUT_MS, () -> endSession(null));
    }

    /** There is one timeout at a time: arming one replaces the one before. */
    private void armTimeout(long delayMs, Runnable task) {
        cancelTimeout();
        timeout = scheduler.postDelayed(forSession(sessionId, task), delayMs);
    }

    private void cancelTimeout() {
        if (timeout != null) {
            timeout.cancel();
            timeout = null;
        }
    }

    private void postForSession(long session, Runnable task) {
        scheduler.post(forSession(session, task));
    }

    private Runnable forSession(long session, Runnable task) {
        return () -> {
            if (session == sessionId && !released) {
                task.run();
            }
        };
    }
}
