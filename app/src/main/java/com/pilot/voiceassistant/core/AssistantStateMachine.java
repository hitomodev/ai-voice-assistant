package com.pilot.voiceassistant.core;

/**
 * The assistant's state and transition table (architecture document, Section 5). It decides what
 * happens on each event and delegates the work to {@link Actions}.
 *
 * <p>Not thread-safe: every method must be called from the control thread. The state is updated
 * before an action runs, so an action may report a new event from inside its own call.
 */
public final class AssistantStateMachine {

    /** The work behind each transition. */
    public interface Actions {
        /** Start a new session: take audio focus, open the microphone and start STT. */
        void startListening();

        /** Stop the microphone and STT without producing a transcript, and release audio focus. */
        void cancelListening();

        /** Close the microphone and send the transcript to the LLM, with TTS on its output. */
        void startThinking(String transcript);

        /** Make sure audio focus is held so the response can be played. */
        void startSpeaking();

        /** Stop the LLM, TTS and playback, and release audio focus. */
        void cancelResponse();

        /** The response was played to the end: release audio focus. */
        void finishSpeaking();

        void reportError(String reason);
    }

    public interface StateListener {
        void onStateChanged(AssistantState state);
    }

    private final Actions actions;
    private final StateListener stateListener;
    private AssistantState state = AssistantState.STANDBY;

    public AssistantStateMachine(Actions actions, StateListener stateListener) {
        this.actions = actions;
        this.stateListener = stateListener;
    }

    public AssistantState getState() {
        return state;
    }

    public void onTrigger(TriggerSource source) {
        switch (state) {
            case STANDBY:
                setState(AssistantState.LISTENING);
                actions.startListening();
                break;
            case LISTENING:
                // A second button press cancels the turn. The wake word cannot fire here.
                if (source.isButton()) {
                    setState(AssistantState.STANDBY);
                    actions.cancelListening();
                }
                break;
            case THINKING:
                bargeIn();
                break;
            case SPEAKING:
                // The microphone hears the assistant's own voice, so only buttons may barge in.
                if (source.isButton()) {
                    bargeIn();
                }
                break;
        }
    }

    public void onFinalTranscript(String transcript) {
        if (state != AssistantState.LISTENING) {
            return;
        }
        String text = transcript == null ? "" : transcript.trim();
        if (text.isEmpty()) {
            setState(AssistantState.STANDBY);
            actions.cancelListening();
        } else {
            setState(AssistantState.THINKING);
            actions.startThinking(text);
        }
    }

    public void onNoSpeechTimeout() {
        if (state != AssistantState.LISTENING) {
            return;
        }
        setState(AssistantState.STANDBY);
        actions.cancelListening();
    }

    public void onFirstAudioReady() {
        if (state != AssistantState.THINKING) {
            return;
        }
        setState(AssistantState.SPEAKING);
        actions.startSpeaking();
    }

    public void onPlaybackComplete() {
        if (state != AssistantState.SPEAKING) {
            return;
        }
        setState(AssistantState.STANDBY);
        actions.finishSpeaking();
    }

    public void onAudioFocusLost() {
        onError("audio focus lost");
    }

    public void onError(String reason) {
        AssistantState previous = state;
        if (previous == AssistantState.STANDBY) {
            return;
        }
        setState(AssistantState.STANDBY);
        if (previous == AssistantState.LISTENING) {
            actions.cancelListening();
        } else {
            actions.cancelResponse();
        }
        actions.reportError(reason);
    }

    private void bargeIn() {
        setState(AssistantState.LISTENING);
        actions.cancelResponse();
        actions.startListening();
    }

    private void setState(AssistantState newState) {
        state = newState;
        stateListener.onStateChanged(newState);
    }
}
