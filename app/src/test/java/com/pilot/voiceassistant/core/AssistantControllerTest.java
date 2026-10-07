package com.pilot.voiceassistant.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.pilot.voiceassistant.eal.LLMEAL;
import com.pilot.voiceassistant.eal.Message;
import com.pilot.voiceassistant.eal.STTEAL;
import com.pilot.voiceassistant.eal.TTSEAL;
import com.pilot.voiceassistant.eal.WWEAL;
import com.pilot.voiceassistant.hal.AudioHAL;
import com.pilot.voiceassistant.hal.MicHAL;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** Drives the controller with scripted engines on a deterministic scheduler. */
public class AssistantControllerTest {

    private static final class ScriptedStt implements STTEAL {
        STTCallback callback;
        int starts;
        int cancels;
        boolean released;

        @Override
        public void startTranscription(STTCallback callback) {
            this.callback = callback;
            starts++;
        }

        @Override
        public void feedAudioChunk(byte[] pcmData) {
        }

        @Override
        public void finishTranscription() {
        }

        @Override
        public void cancel() {
            cancels++;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class ScriptedLlm implements LLMEAL {
        LLMCallback callback;
        List<Message> conversation;
        int cancels;
        boolean released;

        @Override
        public void generateResponse(List<Message> conversation, LLMCallback callback) {
            this.conversation = conversation;
            this.callback = callback;
        }

        @Override
        public void cancelGeneration() {
            cancels++;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class ScriptedTts implements TTSEAL {
        TTSCallback callback;
        final List<String> fed = new ArrayList<>();
        int finishes;
        int stops;
        boolean released;

        @Override
        public void startSynthesis(TTSCallback callback) {
            this.callback = callback;
            fed.clear();
        }

        @Override
        public void feedText(String textChunk) {
            fed.add(textChunk);
        }

        @Override
        public void finishInput() {
            finishes++;
        }

        @Override
        public void stopSynthesis() {
            stops++;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class ScriptedWakeWord implements WWEAL {
        Listener listener;
        boolean initialized;
        int resets;
        boolean released;

        @Override
        public void initialize() {
            initialized = true;
        }

        @Override
        public void setWakeWordListener(Listener listener) {
            this.listener = listener;
        }

        @Override
        public void reset() {
            resets++;
        }

        @Override
        public void processFrame(byte[] pcmData) {
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class RecordingAudio implements AudioHAL {
        PlaybackListener listener;
        boolean grantFocus = true;
        boolean focusHeld;
        int cues;
        final List<byte[]> played = new ArrayList<>();
        int endOfStreams;
        int stops;
        boolean released;

        @Override
        public void setPlaybackListener(PlaybackListener listener) {
            this.listener = listener;
        }

        @Override
        public boolean requestAudioFocus() {
            focusHeld = grantFocus;
            return grantFocus;
        }

        @Override
        public void abandonAudioFocus() {
            focusHeld = false;
        }

        @Override
        public void playListeningCue() {
            cues++;
        }

        @Override
        public void playAudioBuffer(byte[] pcmData) {
            played.add(pcmData);
        }

        @Override
        public void endOfStream() {
            endOfStreams++;
        }

        @Override
        public void stopPlayback() {
            stops++;
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class CountingMic implements MicHAL {
        int starts;
        boolean released;

        @Override
        public boolean startRecording() {
            starts++;
            return false;
        }

        @Override
        public int readRawAudioChunk(byte[] buffer) {
            return -1;
        }

        @Override
        public void stopRecording() {
        }

        @Override
        public void release() {
            released = true;
        }
    }

    private static final class RecordingListener implements AssistantController.Listener {
        final List<AssistantState> states = new ArrayList<>();
        final List<TriggerSource> turnSources = new ArrayList<>();
        final List<String> partials = new ArrayList<>();
        final List<String> transcripts = new ArrayList<>();
        final List<String> errors = new ArrayList<>();
        String response = "";

        @Override
        public void onStateChanged(AssistantState state) {
            states.add(state);
        }

        @Override
        public void onTurnStarted(TriggerSource source) {
            turnSources.add(source);
        }

        @Override
        public void onPartialTranscript(String textSoFar) {
            partials.add(textSoFar);
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

        AssistantState current() {
            return states.isEmpty() ? AssistantState.STANDBY : states.get(states.size() - 1);
        }
    }

    private static final byte[] AUDIO = new byte[320];

    private final FakeScheduler scheduler = new FakeScheduler();
    private final List<Runnable> captureTasks = new ArrayList<>();
    private final CountingMic mic = new CountingMic();
    private final RecordingAudio audio = new RecordingAudio();
    private final ScriptedStt stt = new ScriptedStt();
    private final ScriptedLlm llm = new ScriptedLlm();
    private final ScriptedTts tts = new ScriptedTts();
    private final ScriptedWakeWord wakeWord = new ScriptedWakeWord();
    private final RecordingListener listener = new RecordingListener();
    private AssistantController controller;

    @Before
    public void setUp() {
        controller = new AssistantController(mic, audio, null, stt, llm, tts, scheduler,
                captureTasks::add, listener);
    }

    /** Replaces the controller with one that has a wake word engine, already started. */
    private void startWithWakeWord() {
        controller = new AssistantController(mic, audio, wakeWord, stt, llm, tts, scheduler,
                captureTasks::add, listener);
        controller.startWakeWord();
        scheduler.runAll();
    }

    private void runLastCaptureTask() {
        captureTasks.get(captureTasks.size() - 1).run();
        scheduler.runAll();
    }

    private boolean wakeWordErrorsAreAbsent() {
        for (String error : listener.errors) {
            if (error.startsWith("wake word")) {
                return false;
            }
        }
        return true;
    }

    private void trigger(TriggerSource source) {
        controller.onTrigger(source);
        scheduler.runAll();
    }

    private void hear(String transcript) {
        stt.callback.onEndOfSpeech();
        stt.callback.onFinalTranscript(transcript);
        scheduler.runAll();
    }

    private void llmSays(String text) {
        llm.callback.onTextChunk(text);
        scheduler.runAll();
    }

    private void llmCompletes() {
        llm.callback.onComplete();
        scheduler.runAll();
    }

    private void ttsEmitsAudio() {
        tts.callback.onAudioBuffer(AUDIO);
        scheduler.runAll();
    }

    private void ttsCompletes() {
        tts.callback.onComplete();
        scheduler.runAll();
    }

    private void playbackCompletes() {
        audio.listener.onPlaybackComplete();
        scheduler.runAll();
    }

    private void goToThinking(String transcript) {
        trigger(TriggerSource.UI_BUTTON);
        hear(transcript);
        assertEquals(AssistantState.THINKING, listener.current());
    }

    private void goToSpeaking(String transcript, String firstResponseText) {
        goToThinking(transcript);
        llmSays(firstResponseText);
        ttsEmitsAudio();
        assertEquals(AssistantState.SPEAKING, listener.current());
    }

    private static List<String> texts(List<Message> conversation) {
        List<String> result = new ArrayList<>();
        for (Message message : conversation) {
            result.add(message.toString());
        }
        return result;
    }

    @Test
    public void fullTurn_runsThroughEveryStateAndStreamsSentencesToTts() {
        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.LISTENING, listener.current());
        assertEquals(1, stt.starts);
        assertEquals(1, captureTasks.size());

        hear("hello");
        assertEquals(AssistantState.THINKING, listener.current());
        assertEquals(Collections.singletonList("hello"), listener.transcripts);
        assertEquals(Collections.singletonList("USER: hello"), texts(llm.conversation));

        llmSays("Hi there. How");
        assertEquals(Collections.singletonList("Hi there."), tts.fed);
        assertEquals(AssistantState.THINKING, listener.current());

        ttsEmitsAudio();
        assertEquals(AssistantState.SPEAKING, listener.current());
        assertTrue(audio.focusHeld);
        assertEquals(1, audio.played.size());

        llmSays(" are you?");
        llmCompletes();
        assertEquals(Arrays.asList("Hi there.", "How are you?"), tts.fed);
        assertEquals(1, tts.finishes);
        assertEquals("Hi there. How are you?", listener.response);

        ttsEmitsAudio();
        ttsCompletes();
        assertEquals(2, audio.played.size());
        assertEquals(1, audio.endOfStreams);
        assertEquals(AssistantState.SPEAKING, listener.current());

        playbackCompletes();
        assertEquals(AssistantState.STANDBY, listener.current());
        assertFalse(audio.focusHeld);
        assertEquals(Arrays.asList(AssistantState.LISTENING, AssistantState.THINKING,
                AssistantState.SPEAKING, AssistantState.STANDBY), listener.states);
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void secondTurn_sendsTheConversationHistory() {
        goToSpeaking("hello", "Hi there.");
        llmCompletes();
        ttsCompletes();
        playbackCompletes();

        goToThinking("and you?");

        assertEquals(Arrays.asList("USER: hello", "ASSISTANT: Hi there.", "USER: and you?"),
                texts(llm.conversation));
    }

    @Test
    public void endOfSpeech_stopsTheCaptureLoop() {
        trigger(TriggerSource.UI_BUTTON);
        stt.callback.onEndOfSpeech();
        scheduler.runAll();

        captureTasks.get(0).run();

        assertEquals("a stopped loop must not open the microphone", 0, mic.starts);
    }

    @Test
    public void listening_takesAudioFocusAndPlaysTheCue() {
        trigger(TriggerSource.WAKE_WORD);
        assertEquals(AssistantState.LISTENING, listener.current());
        assertTrue(audio.focusHeld);
        assertEquals(1, audio.cues);

        hear("hello");
        assertTrue("focus is kept while thinking", audio.focusHeld);
    }

    @Test
    public void listening_cancelledReleasesAudioFocus() {
        trigger(TriggerSource.UI_BUTTON);
        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.STANDBY, listener.current());
        assertFalse(audio.focusHeld);
    }

    @Test
    public void listening_goesOnWhenAudioFocusIsDenied() {
        audio.grantFocus = false;
        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.LISTENING, listener.current());
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void noSpeechTimeout_returnsToStandbyWithoutError() {
        trigger(TriggerSource.UI_BUTTON);

        scheduler.advance(AssistantController.LISTENING_TIMEOUT_MS - 1);
        assertEquals(AssistantState.LISTENING, listener.current());

        scheduler.advance(1);
        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(1, stt.cancels);
        assertTrue(listener.errors.isEmpty());

        captureTasks.get(0).run();
        assertEquals(0, mic.starts);
    }

    @Test
    public void listeningTimeout_doesNotFireOnceTheTranscriptArrived() {
        goToThinking("hello");

        scheduler.advance(AssistantController.LISTENING_TIMEOUT_MS * 2);

        assertEquals(AssistantState.THINKING, listener.current());
    }

    @Test
    public void listeningTimeout_ofACancelledTurnDoesNotEndTheNextTurn() {
        trigger(TriggerSource.UI_BUTTON);
        scheduler.advance(AssistantController.LISTENING_TIMEOUT_MS - 1000);
        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.STANDBY, listener.current());

        trigger(TriggerSource.UI_BUTTON);
        scheduler.advance(2000);

        assertEquals(AssistantState.LISTENING, listener.current());
    }

    @Test
    public void buttonWhileListening_cancelsAndDropsTheLateTranscript() {
        trigger(TriggerSource.UI_BUTTON);
        STTEAL.STTCallback firstTurn = stt.callback;

        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(1, stt.cancels);

        // The cancelled turn's transcript arrives after a new turn has started.
        trigger(TriggerSource.UI_BUTTON);
        firstTurn.onFinalTranscript("stale");
        scheduler.runAll();

        assertEquals(AssistantState.LISTENING, listener.current());
        assertTrue(listener.transcripts.isEmpty());
    }

    @Test
    public void emptyTranscript_returnsToStandby() {
        trigger(TriggerSource.UI_BUTTON);
        hear("  ");

        assertEquals(AssistantState.STANDBY, listener.current());
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void bargeInWhileThinking_cancelsAndDropsStaleCallbacks() {
        goToThinking("first");
        LLMEAL.LLMCallback oldLlm = llm.callback;
        TTSEAL.TTSCallback oldTts = tts.callback;

        trigger(TriggerSource.WAKE_WORD);
        assertEquals(AssistantState.LISTENING, listener.current());
        assertEquals(1, llm.cancels);
        assertEquals(1, tts.stops);
        assertEquals(2, stt.starts);

        oldLlm.onTextChunk("Stale answer. ");
        oldLlm.onComplete();
        oldTts.onAudioBuffer(AUDIO);
        oldTts.onComplete();
        scheduler.runAll();

        assertEquals(AssistantState.LISTENING, listener.current());
        assertTrue(audio.played.isEmpty());
        assertEquals(0, audio.endOfStreams);
        assertEquals("", listener.response);
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void bargeInWhileSpeaking_stopsPlaybackAndKeepsThePartialAnswerInHistory() {
        goToSpeaking("first", "Partial answer. ");
        TTSEAL.TTSCallback oldTts = tts.callback;

        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.LISTENING, listener.current());
        assertEquals(1, audio.stops);
        assertTrue("the new turn holds focus again", audio.focusHeld);
        assertEquals(1, llm.cancels);

        oldTts.onAudioBuffer(AUDIO);
        scheduler.runAll();
        assertEquals("stale audio must not be played", 1, audio.played.size());

        hear("second");
        assertEquals(Arrays.asList("USER: first", "ASSISTANT: Partial answer.", "USER: second"),
                texts(llm.conversation));
    }

    @Test
    public void stalePlaybackComplete_doesNotEndTheNextResponse() {
        goToSpeaking("first", "Answer one. ");
        // The user barges in, and the first response reports completion before that is handled.
        controller.onTrigger(TriggerSource.UI_BUTTON);
        audio.listener.onPlaybackComplete();
        scheduler.runAll();
        assertEquals(AssistantState.LISTENING, listener.current());

        hear("second");
        llmSays("Answer two. ");
        ttsEmitsAudio();
        assertEquals(AssistantState.SPEAKING, listener.current());
        assertTrue(audio.focusHeld);
    }

    @Test
    public void wakeWordWhileSpeaking_isIgnored() {
        goToSpeaking("hello", "Hi there. ");

        trigger(TriggerSource.WAKE_WORD);

        assertEquals(AssistantState.SPEAKING, listener.current());
        assertEquals(0, audio.stops);
    }

    @Test
    public void sttError_returnsToStandbyAndReports() {
        trigger(TriggerSource.UI_BUTTON);
        stt.callback.onError("no network");
        scheduler.runAll();

        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(Collections.singletonList("STT: no network"), listener.errors);
    }

    @Test
    public void llmError_returnsToStandbyAndForgetsTheUnansweredMessage() {
        goToThinking("first");
        llm.callback.onError("timeout");
        scheduler.runAll();

        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(Collections.singletonList("LLM: timeout"), listener.errors);
        assertEquals(1, tts.stops);

        goToThinking("second");
        assertEquals(Collections.singletonList("USER: second"), texts(llm.conversation));
    }

    @Test
    public void ttsError_returnsToStandbyAndReports() {
        goToSpeaking("hello", "Hi there. ");
        tts.callback.onError("voice missing");
        scheduler.runAll();

        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(Collections.singletonList("TTS: voice missing"), listener.errors);
        assertEquals(1, audio.stops);
        assertFalse(audio.focusHeld);
    }

    @Test
    public void audioFocusDenied_returnsToStandbyWithoutPlaying() {
        audio.grantFocus = false;
        goToThinking("hello");
        llmSays("Hi there. ");
        ttsEmitsAudio();

        assertEquals(AssistantState.STANDBY, listener.current());
        assertTrue(audio.played.isEmpty());
        assertEquals(Collections.singletonList("audio focus denied"), listener.errors);
        assertEquals(1, llm.cancels);
    }

    @Test
    public void audioFocusLostWhileSpeaking_stopsPlayback() {
        goToSpeaking("hello", "Hi there. ");
        audio.listener.onAudioFocusLost();
        scheduler.runAll();

        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(1, audio.stops);
        assertEquals(Collections.singletonList("audio focus lost"), listener.errors);
    }

    @Test
    public void emptyResponse_returnsToStandbyAndReports() {
        goToThinking("hello");
        llmCompletes();
        assertEquals(1, tts.finishes);
        ttsCompletes();

        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(Collections.singletonList("empty response"), listener.errors);
    }

    @Test
    public void historyIsCappedAndStillStartsWithAUserMessage() {
        int turns = AssistantController.MAX_HISTORY_MESSAGES;
        for (int i = 0; i < turns; i++) {
            goToSpeaking("question " + i, "Answer " + i + ". ");
            llmCompletes();
            ttsCompletes();
            playbackCompletes();
        }

        goToThinking("last");

        assertEquals(AssistantController.MAX_HISTORY_MESSAGES + 1, llm.conversation.size());
        assertEquals(Message.Role.USER, llm.conversation.get(0).getRole());
    }

    @Test
    public void release_stopsTheTurnReleasesEverythingAndIgnoresLaterTriggers() {
        goToSpeaking("hello", "Hi there. ");
        int capturesBefore = captureTasks.size();

        controller.release();
        scheduler.runAll();

        assertEquals(1, audio.stops);
        assertTrue(stt.released);
        assertTrue(llm.released);
        assertTrue(tts.released);
        assertTrue(audio.released);
        assertEquals(capturesBefore + 1, captureTasks.size());
        captureTasks.get(capturesBefore).run();
        assertTrue(mic.released);

        int startsBefore = stt.starts;
        trigger(TriggerSource.UI_BUTTON);
        assertEquals(startsBefore, stt.starts);
    }

    @Test
    public void buttonTurn_reportsItsSource() {
        trigger(TriggerSource.UI_BUTTON);

        assertEquals(Collections.singletonList(TriggerSource.UI_BUTTON), listener.turnSources);
    }

    @Test
    public void withoutStartWakeWord_theMicrophoneIsIdleInStandby() {
        controller = new AssistantController(mic, audio, wakeWord, stt, llm, tts, scheduler,
                captureTasks::add, listener);
        scheduler.runAll();

        assertTrue(captureTasks.isEmpty());
    }

    @Test
    public void startWakeWord_initializesTheEngineAndOpensTheMicrophone() {
        startWithWakeWord();
        controller.startWakeWord();
        scheduler.runAll();

        assertEquals("a second start changes nothing", 2, captureTasks.size());
        captureTasks.get(0).run();
        assertTrue(wakeWord.initialized);
        captureTasks.get(1).run();
        assertEquals(1, wakeWord.resets);
        assertEquals(1, mic.starts);
    }

    @Test
    public void wakeWord_startsATurnAndHandsOverTheMicrophone() {
        startWithWakeWord();
        int standbyTask = captureTasks.size() - 1;

        wakeWord.listener.onWakeWordDetected();
        scheduler.runAll();

        assertEquals(AssistantState.LISTENING, listener.current());
        assertEquals(Collections.singletonList(TriggerSource.WAKE_WORD), listener.turnSources);
        assertEquals(1, stt.starts);
        assertEquals("the turn takes over the standby capture", standbyTask + 1,
                captureTasks.size());
        captureTasks.get(standbyTask).run();
        assertEquals(1, mic.starts);
        assertTrue("its failure now belongs to the turn", wakeWordErrorsAreAbsent());
        scheduler.runAll();
        assertEquals(AssistantState.STANDBY, listener.current());
        assertEquals(Collections.singletonList("microphone could not be opened"),
                listener.errors);
    }

    @Test
    public void partialTranscript_isReportedAndRestartsTheNoSpeechTimeout() {
        trigger(TriggerSource.UI_BUTTON);
        scheduler.advance(AssistantController.LISTENING_TIMEOUT_MS - 1);

        stt.callback.onPartialTranscript("what is");
        scheduler.runAll();
        scheduler.advance(AssistantController.LISTENING_TIMEOUT_MS - 1);

        assertEquals(Collections.singletonList("what is"), listener.partials);
        assertEquals(AssistantState.LISTENING, listener.current());
        scheduler.advance(1);
        assertEquals(AssistantState.STANDBY, listener.current());
    }

    @Test
    public void wakeWordWhileListening_isIgnored() {
        startWithWakeWord();
        trigger(TriggerSource.UI_BUTTON);

        wakeWord.listener.onWakeWordDetected();
        scheduler.runAll();

        assertEquals(AssistantState.LISTENING, listener.current());
        assertEquals(1, stt.starts);
    }

    @Test
    public void backInStandby_theWakeWordListensAgain() {
        startWithWakeWord();
        trigger(TriggerSource.UI_BUTTON);
        int tasksWhileListening = captureTasks.size();

        trigger(TriggerSource.UI_BUTTON);
        assertEquals(AssistantState.STANDBY, listener.current());

        assertEquals(tasksWhileListening + 1, captureTasks.size());
        runLastCaptureTask();
        assertEquals(1, wakeWord.resets);
        assertEquals(1, mic.starts);
    }

    @Test
    public void standbyMicrophoneFailure_isReportedAndRetriedAfterTheNextTurn() {
        startWithWakeWord();
        runLastCaptureTask();
        assertEquals(Collections.singletonList("wake word: microphone could not be opened"),
                listener.errors);

        trigger(TriggerSource.UI_BUTTON);
        trigger(TriggerSource.UI_BUTTON);
        runLastCaptureTask();

        assertEquals(2, mic.starts);
    }

    @Test
    public void staleStandbyMicrophoneFailure_isNotReported() {
        startWithWakeWord();
        Runnable standbyTask = captureTasks.get(captureTasks.size() - 1);
        // The microphone fails to open at the moment a button starts a turn.
        controller.onTrigger(TriggerSource.UI_BUTTON);
        standbyTask.run();
        scheduler.runAll();

        assertEquals(AssistantState.LISTENING, listener.current());
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void wakeWordEngineError_isReportedAndSwitchesTheWakeWordOff() {
        startWithWakeWord();
        Runnable standbyTask = captureTasks.get(captureTasks.size() - 1);

        wakeWord.listener.onError("model missing");
        scheduler.runAll();

        assertEquals(Collections.singletonList("wake word: model missing"), listener.errors);
        standbyTask.run();
        assertEquals(0, mic.starts);

        trigger(TriggerSource.UI_BUTTON);
        int tasksWhileListening = captureTasks.size();
        trigger(TriggerSource.UI_BUTTON);
        assertEquals("no standby capture without an engine", tasksWhileListening,
                captureTasks.size());
    }

    @Test
    public void release_stopsStandbyCaptureAndReleasesTheWakeWordEngine() {
        startWithWakeWord();
        Runnable standbyTask = captureTasks.get(captureTasks.size() - 1);
        int tasksBefore = captureTasks.size();

        controller.release();
        scheduler.runAll();

        standbyTask.run();
        assertEquals(0, mic.starts);
        for (Runnable task : captureTasks.subList(tasksBefore, captureTasks.size())) {
            task.run();
        }
        assertTrue(wakeWord.released);
        assertTrue(mic.released);
    }
}
