# Technical Architecture Plan: Android 14 AI Voice Assistant

**Target Platform:** Android Automotive OS (AAOS) on a custom board, Android 14 (API Level 34)  
**Implementation Language:** Java  
**Build & Install:** Gradle / Android Studio, APK installed with `adb`  
**System Type:** Multi-Trigger Voice AI System (Wake Word & Hardware/UI Button)

---

## 1. Scope & Assumptions

* **Pilot application.** It runs only on our own AAOS board and is not released to any store.
* **High-level definition.** This document defines layers, contracts, and flow logic. The wake word, STT, LLM, and TTS engines are abstractions only; engine selection and technical details are covered in later documents.
* **Main flow first.** The first build target is one complete turn: **in-app button → STT → LLM → TTS → speaker**. Wake word and hardware key are additional trigger sources that plug into the same flow afterwards.
* **Service start.** `VoiceAssistantService` is started by `MainActivity` while it is visible. Start on boot is deferred (see Section 7).

---

## 2. High-Level Architecture Overview

The system is split into three layers:
1. **Application Layer**: UI, trigger sources, and the Foreground Service that owns the state machine.
2. **Engine Abstraction Layer (EAL)**: Wake word, STT, LLM, and TTS engines.
3. **Hardware Abstraction Layer (HAL)**: Microphone input and audio output.

`VoiceAssistantService` is the only coordinator. Engines and hardware drivers never call each other; the service moves data between them.

```
┌──────────────────────────────────────────────────────────────────┐
│                        APPLICATION LAYER                         │
│                                                                  │
│  Trigger sources                                                 │
│  ┌──────────────┐  ┌──────────────────┐  ┌───────────────────┐   │
│  │ MainActivity │  │ HardwareKey      │  │ Wake word         │   │
│  │ button       │  │ Handler (later)  │  │ (WWEAL)           │   │
│  └──────┬───────┘  └────────┬─────────┘  └─────────┬─────────┘   │
│         └───────────────────┼─────────────────────┘              │
│                             ▼  TriggerListener                   │
│  ┌────────────────────────────────────────────────────────────┐  │
│  │                   VoiceAssistantService                    │  │
│  │    state machine · session control · audio frame routing   │  │
│  └──────────────┬──────────────────────────────┬──────────────┘  │
└─────────────────┼──────────────────────────────┼─────────────────┘
                  ▼                              ▼
┌───────────────────────────────┐  ┌───────────────────────────────┐
│      ENGINE LAYER (EAL)       │  │     HARDWARE LAYER (HAL)      │
│ WWEAL  STTEAL  LLMEAL  TTSEAL │  │      MicHAL     AudioHAL      │
└───────────────────────────────┘  └───────────────────────────────┘
```

### Main Flow Data Path

```
MicHAL ──PCM──► STTEAL ──transcript──► LLMEAL ──text chunks──► TTSEAL ──PCM──► AudioHAL
   └──────────────── every hop passes through VoiceAssistantService ────────────────┘
```

### Microphone Ownership

`VoiceAssistantService` owns the single capture loop on `MicHAL` and decides where frames go:

| State | Frames are routed to |
|---|---|
| `STANDBY` | `WWEAL` (once the wake word is enabled; otherwise the microphone is idle) |
| `LISTENING` | `STTEAL` |
| `THINKING`, `SPEAKING` | Nobody (microphone idle in the pilot) |

---

## 3. Abstraction Layer Contracts

All audio uses one PCM format, defined once as a shared constant (value decided in the detailed design). Every engine reports failures through an `onError` callback and frees its resources in `release()`.

### Hardware Abstraction Layer (HAL)

* **`MicHAL`**: Abstract driver for raw PCM microphone capture.
  * *Contract*: `startRecording()` (returns whether the microphone opened), `readRawAudioChunk(byte[] buffer)` (blocking; returns bytes read, negative on failure), `stopRecording()`, `release()`.
* **`AudioHAL`**: Abstract driver for speaker output and audio focus.
  * *Contract*: `requestAudioFocus()`, `abandonAudioFocus()`, `playAudioBuffer()` (queues and returns), `endOfStream()`, `stopPlayback()`, `setPlaybackListener()` (playback complete, focus lost), `release()`.
  * Playback complete is reported once everything queued before `endOfStream()` has been played. No completion is reported after `stopPlayback()`.

### Engine Abstraction Layer (EAL)

* **`WWEAL` (Wakeup Word Engine Abstract Layer)**: Analyzes PCM frames to detect the trigger phrase.
  * *Contract*: `initialize()`, `reset()`, `processFrame(byte[] pcmData)`, `setWakeWordListener(Listener listener)`, `release()`.
  * *Callback*: `onWakeWordDetected()`, `onError()`.
  * `reset()` forgets the audio heard so far. The service calls it each time the capture loop returns to `STANDBY`, so audio from before a turn cannot trigger after it.
* **`STTEAL` (Speech-to-Text Engine Abstract Layer)**: Converts streamed audio into a transcript and detects end of speech.
  * *Contract*: `startTranscription(STTCallback callback)`, `feedAudioChunk(byte[] pcmData)`, `finishTranscription()`, `cancel()`, `release()`.
  * *Callback*: `onEndOfSpeech()`, `onFinalTranscript(String text)`, `onError()`.
  * `STTEAL` owns end-of-speech detection. `finishTranscription()` is a manual stop, used when the caller ends the turn itself.
* **`LLMEAL` (LLM Engine Abstract Layer)**: Sends the conversation to an LLM provider (cloud or on-device) and streams the response text back.
  * *Contract*: `generateResponse(List<Message> conversation, LLMCallback callback)`, `cancelGeneration()`, `release()`.
  * *Callback*: `onTextChunk(String text)`, `onComplete()`, `onError()`.
  * The service keeps the conversation history and passes it on every turn.
* **`TTSEAL` (Text-to-Speech Engine Abstract Layer)**: Converts text to playable PCM audio, accepting text incrementally.
  * *Contract*: `startSynthesis(TTSCallback callback)`, `feedText(String textChunk)`, `finishInput()`, `stopSynthesis()`, `release()`.
  * *Callback*: `onAudioBuffer(byte[] pcmData)`, `onComplete()`, `onError()`.

---

## 4. Application Components & Services

### Components
* **`MainActivity`**: UI host with the talk button and state-driven animations. Starts and binds to `VoiceAssistantService`, and observes its state.
* **`VoiceAssistantService`**: Android `Service` running as a **Foreground Service** (`FOREGROUND_SERVICE_TYPE_MICROPHONE`). Creates the HAL and EAL instances, hosts `AssistantController`, and relays its updates to the UI.
* **`AssistantController`**: Plain Java coordinator. Executes the actions the state machine decides, runs the capture loop, owns the session ID and the conversation history, and moves data between the microphone, the engines, and the speaker.
* **`AssistantStateMachine`**: Plain Java class holding the state and the transition table from Section 5.
* `AssistantController` and `AssistantStateMachine` have no Android dependencies, so the whole flow is unit tested on the JVM with fake engines and fake hardware.
* **`TriggerListener`**: Single interface through which every trigger source reports an activation to the service.
* **`HardwareKeyHandler`** *(later phase)*: Reports the physical voice key (for example the steering wheel voice button). The key routing mechanism on our AAOS board is decided in the detailed design.

### Activation Entry Points
All sources deliver the same `onTrigger(source)` event; the state machine does not depend on which source fired, except where Section 5 says otherwise.
1. **In-App Touch Button** *(main flow)*: Tap on `MainActivity`.
2. **Physical Device Key** *(later phase)*: Via `HardwareKeyHandler`.
3. **Wake Word**: `WWEAL` listener, fed by the service's capture loop in `STANDBY`. The UI shows that the turn was started by the wake word.

---

## 5. Main Flow

### System State Machine

| State | Event | Next state | Action |
|---|---|---|---|
| `STANDBY` | Trigger | `LISTENING` | Start new session, start microphone, start STT |
| `LISTENING` | Final transcript received | `THINKING` | Stop microphone, send conversation to LLM |
| `LISTENING` | No-speech timeout or empty transcript | `STANDBY` | Stop microphone, cancel STT |
| `LISTENING` | Button trigger | `STANDBY` | Cancel the turn |
| `THINKING` | First TTS audio buffer ready | `SPEAKING` | Request audio focus, start playback |
| `THINKING` | Trigger (barge-in) | `LISTENING` | Cancel LLM and TTS, start new session |
| `SPEAKING` | Playback complete | `STANDBY` | Abandon audio focus |
| `SPEAKING` | Button trigger (barge-in) | `LISTENING` | Stop playback, cancel LLM and TTS, start new session |
| `SPEAKING` | Wake word trigger | `SPEAKING` | Ignored in the pilot |
| Any | Engine error or audio focus lost | `STANDBY` | Cancel everything, release focus, notify UI |

### Flow Rules

* **Session ID.** Every turn gets a new session ID. Each engine callback carries the ID of the session that started it, and the service drops callbacks from any earlier session. This keeps a cancelled response from speaking over a new turn.
* **Streaming.** LLM text chunks are grouped into sentences and fed to `TTSEAL` as they arrive. `SPEAKING` starts at the first audio buffer, so the LLM may still be generating while audio plays.
* **Playback complete** means the LLM has finished, TTS has finished, and `AudioHAL` has played the last buffer.
* **Wake word during `SPEAKING`** is ignored in the pilot, because the microphone hears the assistant's own voice. Button barge-in is always available.
* **Threading.** State transitions run on a single control thread; engine callbacks are posted to it. Microphone capture runs on its own thread, and playback on another.
* **Pilot values.** PCM is 16 kHz, mono, 16-bit. The listening timeout is 8 seconds. The conversation history keeps the last 20 messages.

### Flow Sequence (one turn)

1. **Trigger (`STANDBY` → `LISTENING`)**:
   * The user taps the button; `MainActivity` calls `onTrigger()` on the service.
   * The service creates a new session, starts `MicHAL` and `STTEAL`, and the UI shows the listening animation.

2. **Capture & Speech-to-Text (`LISTENING`)**:
   * The capture loop reads PCM from `MicHAL` and feeds `STTEAL`.
   * `STTEAL` detects end of speech and returns the final transcript.
   * If nothing is said before the timeout, the turn ends and the system returns to `STANDBY`.

3. **Reasoning (`THINKING`)**:
   * The microphone stops; the UI shows the thinking animation.
   * The transcript is appended to the conversation and passed to `LLMEAL.generateResponse()`.
   * Text chunks are forwarded to `TTSEAL` sentence by sentence.

4. **Synthesis & Output (`SPEAKING`)**:
   * At the first audio buffer, `AudioHAL` requests focus and playback starts; the UI animation follows the output volume.
   * The response text is appended to the conversation.
   * When playback completes, audio focus is released and the system returns to `STANDBY`.

---

## 6. Implementation Roadmap

1. **Phase 1: Project Skeleton & Interfaces**
   * Create the Gradle project (Java, API 34) and confirm it installs and launches on the board.
   * Define the Java `interface` for every HAL and EAL contract, plus `TriggerListener`.
   * Write fake engines (canned transcript, canned response, generated tone) for development and tests.

2. **Phase 2: Main Flow with Fake Engines**
   * Implement `AssistantStateMachine` with unit tests covering every row of the transition table.
   * Implement `VoiceAssistantService` (foreground service, capture loop, session ID handling).
   * Implement `MainActivity` with the talk button and a basic state display.
   * Implement `MicHAL` and `AudioHAL` on the board's real audio input and output.
   * *Result*: a full turn runs on the board end to end, including button barge-in and error recovery.

3. **Phase 3: Real Engines**
   * Integrate the selected STT, LLM, and TTS engines behind their interfaces.
   * Tune streaming, timeouts, and animations.

4. **Phase 4: Additional Triggers**
   * Wake word (`WWEAL`) on the `STANDBY` capture path.
   * Physical voice key (`HardwareKeyHandler`).
   * Start on boot.

---

## 7. Deferred to Detailed Design

* Engine selection for wake word, STT, LLM, and TTS (cloud or on-device).
* Final PCM format and buffer sizes for the selected engines.
* Hardware key routing on AAOS and whether the app becomes a system/privileged app (also decides start on boot).
* AAOS audio integration: audio usage, zones, and focus interaction with media and navigation.
* Echo cancellation, which is the precondition for wake word barge-in during `SPEAKING`.
* Conversation history limits and reset policy.
