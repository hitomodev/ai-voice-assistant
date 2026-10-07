package com.pilot.voiceassistant.core;

import com.pilot.voiceassistant.eal.LLMEAL;
import com.pilot.voiceassistant.eal.Message;
import com.pilot.voiceassistant.eal.STTEAL;
import com.pilot.voiceassistant.eal.TTSEAL;
import com.pilot.voiceassistant.eal.WWEAL;
import com.pilot.voiceassistant.hal.AudioHAL;
import com.pilot.voiceassistant.hal.MicHAL;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;

/**
 * Coordinates one assistant: it executes the actions the state machine decides and moves data
 * between the microphone, the engines and the speaker. It has no Android dependencies.
 *
 * <p>All state lives on the control thread ({@link Scheduler}). Engine and hardware callbacks are
 * posted to it together with the session they belong to, and dropped if that session is over.
 */
public final class AssistantController implements TriggerListener, AssistantStateMachine.Actions {

    /** Receives updates on the control thread. */
    public interface Listener {
        void onStateChanged(AssistantState state);

        /** A turn started; called after the change to {@code LISTENING}. */
        void onTurnStarted(TriggerSource source);

        /** The words heard so far while {@code LISTENING}; they may still change. */
        void onPartialTranscript(String textSoFar);

        void onTranscript(String transcript);

        /** The response text generated so far in the current turn. */
        void onResponseText(String responseSoFar);

        void onError(String reason);
    }

    static final long LISTENING_TIMEOUT_MS = 8000;
    static final int MAX_HISTORY_MESSAGES = 20;

    private final MicHAL mic;
    private final AudioHAL audio;
    private final WWEAL wakeWord;
    private final STTEAL stt;
    private final LLMEAL llm;
    private final TTSEAL tts;
    private final Scheduler scheduler;
    private final Executor captureExecutor;
    private final Listener listener;
    private final AssistantStateMachine stateMachine;

    private final List<Message> conversation = new ArrayList<>();
    private final StringBuilder responseText = new StringBuilder();
    private SentenceChunker chunker = new SentenceChunker();
    private boolean responseCommitted = true;
    private CaptureLoop captureLoop;
    private Scheduler.Cancellable listeningTimeout;
    private TriggerSource lastTrigger = TriggerSource.UI_BUTTON;
    private boolean wakeWordRunning;
    private CaptureLoop standbyLoop;
    /** Identifies the current standby capture, so errors of an earlier one are dropped. */
    private long standbyRun;
    private boolean released;

    /** Written on the control thread only; read by callbacks on any thread. */
    private volatile long sessionId;

    /**
     * @param wakeWord may be null: the microphone is then idle in {@code STANDBY}
     * @param captureExecutor runs capture loops one at a time on a thread of its own
     */
    public AssistantController(MicHAL mic, AudioHAL audio, WWEAL wakeWord, STTEAL stt, LLMEAL llm,
            TTSEAL tts, Scheduler scheduler, Executor captureExecutor, Listener listener) {
        this.mic = mic;
        this.audio = audio;
        this.wakeWord = wakeWord;
        this.stt = stt;
        this.llm = llm;
        this.tts = tts;
        this.scheduler = scheduler;
        this.captureExecutor = captureExecutor;
        this.listener = listener;
        this.stateMachine = new AssistantStateMachine(this, this::handleStateChanged);

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
                postForSession(sessionId, stateMachine::onPlaybackComplete);
            }

            @Override
            public void onAudioFocusLost() {
                postForSession(sessionId, stateMachine::onAudioFocusLost);
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
            if (stateMachine.getState() == AssistantState.STANDBY) {
                startStandbyCapture();
            }
        });
    }

    @Override
    public void onTrigger(TriggerSource source) {
        scheduler.post(() -> {
            if (!released) {
                lastTrigger = source;
                stateMachine.onTrigger(source);
            }
        });
    }

    /** Stops any turn in progress and releases the engines and hardware. */
    public void release() {
        scheduler.post(() -> {
            if (released) {
                return;
            }
            released = true;
            stopStandbyCapture();
            switch (stateMachine.getState()) {
                case LISTENING:
                    cancelListening();
                    break;
                case THINKING:
                case SPEAKING:
                    cancelResponse();
                    break;
                default:
                    break;
            }
            stt.release();
            llm.release();
            tts.release();
            audio.release();
            // Queued behind the capture loop, so the microphone is closed before it is released.
            if (wakeWord != null) {
                captureExecutor.execute(wakeWord::release);
            }
            captureExecutor.execute(mic::release);
        });
    }

    // ---- AssistantStateMachine.Actions (control thread) ----

    @Override
    public void startListening() {
        final long session = ++sessionId;

        // Focus is held for the whole turn, so that other audio is ducked or paused while the
        // user speaks. A refusal is not an error until there is a response to play.
        audio.requestAudioFocus();
        audio.playListeningCue();

        stt.startTranscription(new STTEAL.STTCallback() {
            @Override
            public void onPartialTranscript(String text) {
                postForSession(session, () -> handlePartialTranscript(text));
            }

            @Override
            public void onEndOfSpeech() {
                postForSession(session, AssistantController.this::stopCapture);
            }

            @Override
            public void onFinalTranscript(String text) {
                postForSession(session, () -> stateMachine.onFinalTranscript(text));
            }

            @Override
            public void onError(String reason) {
                postForSession(session, () -> stateMachine.onError("STT: " + reason));
            }
        });

        CaptureLoop.ErrorHandler onCaptureError =
                reason -> postForSession(session, () -> stateMachine.onError(reason));
        // The standby capture is taken over with the microphone open, so that what the user
        // says right after the wake word is not lost while the microphone reopens.
        CaptureLoop standby = standbyLoop;
        standbyLoop = null;
        standbyRun++;
        if (standby != null && standby.redirect(stt::feedAudioChunk, onCaptureError)) {
            captureLoop = standby;
        } else {
            captureLoop = new CaptureLoop(mic, stt::feedAudioChunk, onCaptureError);
            captureExecutor.execute(captureLoop);
        }

        armListeningTimeout();
        listener.onTurnStarted(lastTrigger);
    }

    @Override
    public void cancelListening() {
        stopCapture();
        cancelListeningTimeout();
        stt.cancel();
        audio.abandonAudioFocus();
        endSession();
    }

    @Override
    public void startThinking(String transcript) {
        final long session = sessionId;
        stopCapture();
        cancelListeningTimeout();

        conversation.add(Message.user(transcript));
        responseText.setLength(0);
        chunker = new SentenceChunker();
        responseCommitted = false;
        listener.onTranscript(transcript);
        listener.onResponseText("");

        tts.startSynthesis(new TTSEAL.TTSCallback() {
            @Override
            public void onAudioBuffer(byte[] pcmData) {
                postForSession(session, () -> handleAudioBuffer(pcmData));
            }

            @Override
            public void onComplete() {
                postForSession(session, AssistantController.this::handleSynthesisComplete);
            }

            @Override
            public void onError(String reason) {
                postForSession(session, () -> stateMachine.onError("TTS: " + reason));
            }
        });

        llm.generateResponse(new ArrayList<>(conversation), new LLMEAL.LLMCallback() {
            @Override
            public void onTextChunk(String text) {
                postForSession(session, () -> handleResponseText(text));
            }

            @Override
            public void onComplete() {
                postForSession(session, AssistantController.this::handleResponseComplete);
            }

            @Override
            public void onError(String reason) {
                postForSession(session, () -> stateMachine.onError("LLM: " + reason));
            }
        });
    }

    @Override
    public void startSpeaking() {
        if (!audio.requestAudioFocus()) {
            stateMachine.onError("audio focus denied");
        }
    }

    @Override
    public void cancelResponse() {
        llm.cancelGeneration();
        tts.stopSynthesis();
        audio.stopPlayback();
        audio.abandonAudioFocus();
        commitResponse();
        endSession();
    }

    @Override
    public void finishSpeaking() {
        audio.abandonAudioFocus();
        endSession();
    }

    @Override
    public void reportError(String reason) {
        listener.onError(reason);
    }

    // ---- Wake word (control thread) ----

    /**
     * The microphone feeds the wake word engine in {@code STANDBY}. A turn takes that capture
     * over in {@link #startListening()}.
     */
    private void handleStateChanged(AssistantState state) {
        listener.onStateChanged(state);
        if (state == AssistantState.STANDBY) {
            startStandbyCapture();
        }
    }

    /**
     * Queued behind the capture loop of the turn that just ended, so it opens the microphone
     * after that one closed it.
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

    // ---- Pipeline steps (control thread, current session only) ----

    private void handlePartialTranscript(String text) {
        if (stateMachine.getState() != AssistantState.LISTENING) {
            return;
        }
        // The user is talking, so the no-speech timeout starts over.
        armListeningTimeout();
        listener.onPartialTranscript(text);
    }

    private void handleResponseText(String text) {
        responseText.append(text);
        listener.onResponseText(responseText.toString());
        for (String sentence : chunker.append(text)) {
            tts.feedText(sentence);
        }
    }

    private void handleResponseComplete() {
        String rest = chunker.flush();
        if (!rest.isEmpty()) {
            tts.feedText(rest);
        }
        tts.finishInput();
        commitResponse();
    }

    private void handleAudioBuffer(byte[] pcmData) {
        if (stateMachine.getState() == AssistantState.THINKING) {
            stateMachine.onFirstAudioReady();
        }
        if (stateMachine.getState() == AssistantState.SPEAKING) {
            audio.playAudioBuffer(pcmData);
        }
    }

    private void handleSynthesisComplete() {
        if (stateMachine.getState() == AssistantState.SPEAKING) {
            audio.endOfStream();
        } else {
            // TTS finished without producing audio, so there is nothing to say.
            stateMachine.onError("empty response");
        }
    }

    /**
     * Closes the turn in the history exactly once: keeps what the assistant said, even if it was
     * interrupted, or drops the user message when there was no answer at all.
     */
    private void commitResponse() {
        if (responseCommitted) {
            return;
        }
        responseCommitted = true;
        String text = responseText.toString().trim();
        if (!text.isEmpty()) {
            conversation.add(Message.assistant(text));
        } else if (!conversation.isEmpty()) {
            conversation.remove(conversation.size() - 1);
        }
        while (conversation.size() > MAX_HISTORY_MESSAGES) {
            conversation.remove(0);
            conversation.remove(0);
        }
    }

    private void stopCapture() {
        if (captureLoop != null) {
            captureLoop.stop();
            captureLoop = null;
        }
    }

    /** Ends the turn if no speech is heard for {@link #LISTENING_TIMEOUT_MS} from now. */
    private void armListeningTimeout() {
        cancelListeningTimeout();
        listeningTimeout = scheduler.postDelayed(
                forSession(sessionId, stateMachine::onNoSpeechTimeout), LISTENING_TIMEOUT_MS);
    }

    private void cancelListeningTimeout() {
        if (listeningTimeout != null) {
            listeningTimeout.cancel();
            listeningTimeout = null;
        }
    }

    /** Makes every callback still in flight for the current session stale. */
    private void endSession() {
        sessionId++;
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
