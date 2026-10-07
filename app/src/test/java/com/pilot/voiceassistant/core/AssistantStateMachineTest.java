package com.pilot.voiceassistant.core;

import static org.junit.Assert.assertEquals;

import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** One test per row of the transition table in the architecture document, Section 5. */
public class AssistantStateMachineTest {

    private final List<String> log = new ArrayList<>();
    private AssistantStateMachine machine;

    @Before
    public void setUp() {
        machine = new AssistantStateMachine(new AssistantStateMachine.Actions() {
            @Override
            public void startListening() {
                log.add("startListening");
            }

            @Override
            public void cancelListening() {
                log.add("cancelListening");
            }

            @Override
            public void startThinking(String transcript) {
                log.add("startThinking:" + transcript);
            }

            @Override
            public void startSpeaking() {
                log.add("startSpeaking");
            }

            @Override
            public void cancelResponse() {
                log.add("cancelResponse");
            }

            @Override
            public void finishSpeaking() {
                log.add("finishSpeaking");
            }

            @Override
            public void reportError(String reason) {
                log.add("error:" + reason);
            }
        }, state -> log.add(state.name()));
    }

    private void goToListening() {
        machine.onTrigger(TriggerSource.UI_BUTTON);
        log.clear();
    }

    private void goToThinking() {
        goToListening();
        machine.onFinalTranscript("hello");
        log.clear();
    }

    private void goToSpeaking() {
        goToThinking();
        machine.onFirstAudioReady();
        log.clear();
    }

    private void assertLog(String... expected) {
        assertEquals(Arrays.asList(expected), log);
    }

    @Test
    public void standby_anyTrigger_startsListening() {
        for (TriggerSource source : TriggerSource.values()) {
            setUp();
            log.clear();
            machine.onTrigger(source);
            assertLog("LISTENING", "startListening");
            assertEquals(AssistantState.LISTENING, machine.getState());
        }
    }

    @Test
    public void listening_finalTranscript_startsThinkingWithTrimmedText() {
        goToListening();
        machine.onFinalTranscript("  hello  ");
        assertLog("THINKING", "startThinking:hello");
    }

    @Test
    public void listening_emptyTranscript_returnsToStandby() {
        goToListening();
        machine.onFinalTranscript("   ");
        assertLog("STANDBY", "cancelListening");

        goToListening();
        machine.onFinalTranscript(null);
        assertLog("STANDBY", "cancelListening");
    }

    @Test
    public void listening_noSpeechTimeout_returnsToStandby() {
        goToListening();
        machine.onNoSpeechTimeout();
        assertLog("STANDBY", "cancelListening");
    }

    @Test
    public void listening_buttonTrigger_cancelsTheTurn() {
        goToListening();
        machine.onTrigger(TriggerSource.UI_BUTTON);
        assertLog("STANDBY", "cancelListening");

        goToListening();
        machine.onTrigger(TriggerSource.HARDWARE_KEY);
        assertLog("STANDBY", "cancelListening");
    }

    @Test
    public void listening_wakeWordTrigger_isIgnored() {
        goToListening();
        machine.onTrigger(TriggerSource.WAKE_WORD);
        assertLog();
        assertEquals(AssistantState.LISTENING, machine.getState());
    }

    @Test
    public void thinking_firstAudio_startsSpeaking() {
        goToThinking();
        machine.onFirstAudioReady();
        assertLog("SPEAKING", "startSpeaking");
    }

    @Test
    public void thinking_anyTrigger_bargesIn() {
        for (TriggerSource source : TriggerSource.values()) {
            setUp();
            goToThinking();
            machine.onTrigger(source);
            assertLog("LISTENING", "cancelResponse", "startListening");
        }
    }

    @Test
    public void speaking_playbackComplete_returnsToStandby() {
        goToSpeaking();
        machine.onPlaybackComplete();
        assertLog("STANDBY", "finishSpeaking");
    }

    @Test
    public void speaking_buttonTrigger_bargesIn() {
        goToSpeaking();
        machine.onTrigger(TriggerSource.UI_BUTTON);
        assertLog("LISTENING", "cancelResponse", "startListening");
    }

    @Test
    public void speaking_wakeWordTrigger_isIgnored() {
        goToSpeaking();
        machine.onTrigger(TriggerSource.WAKE_WORD);
        assertLog();
        assertEquals(AssistantState.SPEAKING, machine.getState());
    }

    @Test
    public void listening_error_cancelsListeningAndReports() {
        goToListening();
        machine.onError("boom");
        assertLog("STANDBY", "cancelListening", "error:boom");
    }

    @Test
    public void thinking_error_cancelsResponseAndReports() {
        goToThinking();
        machine.onError("boom");
        assertLog("STANDBY", "cancelResponse", "error:boom");
    }

    @Test
    public void speaking_audioFocusLost_cancelsResponseAndReports() {
        goToSpeaking();
        machine.onAudioFocusLost();
        assertLog("STANDBY", "cancelResponse", "error:audio focus lost");
    }

    @Test
    public void standby_ignoresEverythingExceptTrigger() {
        machine.onFinalTranscript("hello");
        machine.onNoSpeechTimeout();
        machine.onFirstAudioReady();
        machine.onPlaybackComplete();
        machine.onError("boom");
        machine.onAudioFocusLost();
        assertLog();
        assertEquals(AssistantState.STANDBY, machine.getState());
    }

    @Test
    public void eventsFromAnotherStateAreIgnored() {
        goToListening();
        machine.onFirstAudioReady();
        machine.onPlaybackComplete();
        assertLog();

        goToThinking();
        machine.onFinalTranscript("again");
        machine.onNoSpeechTimeout();
        machine.onPlaybackComplete();
        assertLog();

        goToSpeaking();
        machine.onFinalTranscript("again");
        machine.onNoSpeechTimeout();
        machine.onFirstAudioReady();
        assertLog();
    }
}
