# AI Voice Assistant (AAOS pilot)

Pilot voice assistant for a custom Android Automotive OS board (Android 14, API 34), written in Java.
The design is in [android_14_voice_ai_architecture.md](android_14_voice_ai_architecture.md).

**Current status:** Phases 1 and 2, plus the wake word from Phase 4. The main flow (talk button or
"Hey Bro" → STT → LLM → TTS → speaker) runs on real microphone and speaker drivers. The wake word
engine is real; the other engines are stand-ins:

| Engine | Behaviour |
|---|---|
| Wake word | openWakeWord model for "Hey Bro", run on the device with ONNX Runtime |
| STT | After 2.5 s of microphone audio, returns the transcript "What is the weather today?" |
| LLM | Streams a canned reply that quotes the transcript and the turn number |
| TTS | Plays one tone per sentence, at a different pitch for each sentence |

## Build and test

Needs JDK 17 or newer and the Android SDK (platform 34). `local.properties` points at the SDK.

```bash
./gradlew assembleDebug          # app/build/outputs/apk/debug/app-debug.apk
./gradlew testDebugUnitTest      # JVM unit tests, no device needed
./gradlew lintDebug
```

## Install and sanity check on the board

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -s VoiceAssistantService OpenWakeWordEAL AndroidMicHAL AndroidAudioHAL``
```

1. Open **Voice Assistant** and grant the microphone permission. The screen shows **Standby**.
2. Tap **Talk**. The state goes Listening → Thinking → Speaking → Standby; the transcript and reply
   appear, and three tones play.
3. Tap **Talk** while it is Speaking: playback stops and it goes back to Listening.
4. Tap **Talk** twice quickly: the second tap cancels and it returns to Standby.

5. Wait for Standby and say **"Hey Bro"**. The state goes to Listening and a green
   **Woken up by "Hey Bro"** line stays on screen until the turn ends.

Every turn starts with a short two-note tone: speak after it. From that tone until the return to
Standby the assistant holds audio focus, so music is paused or ducked, and the screen is turned on
and kept on. If the screen stays off on a wake word, allow the app to turn it on:

```bash
adb shell appops set com.pilot.voiceassistant TURN_SCREEN_ON allow
```

If it stays in Listening for 8 seconds and returns to Standby, no audio is arriving from the
microphone. Errors are shown in red on the screen and logged.

## Tradition and Modern

The app opens on a screen with two buttons:

| Mode | What runs |
|---|---|
| **Tradition** | The flow above: every engine runs on the device (`MainActivity`, `VoiceAssistantService`) |
| **Modern** | Same wake word and board tools; Gemini Live replaces STT, LLM and TTS (`LiveActivity`, `LiveAssistantService`) |

Modern needs internet and a Gemini API key in `local.properties` (not in git), then a rebuild:

```properties
GEMINI_API_KEY=your-key
```

"Hey Bro" or **Talk** opens a conversation. It goes back and forth without the wake word: speak
after each two-note tone. The microphone is not sent while the assistant speaks. The conversation
ends after 10 seconds of silence or on **Stop**, and the assistant answers in the language you
speak. The model and the instruction are in `GeminiLiveEAL`.

```bash
adb logcat -s LiveAssistantService GeminiLiveEAL OpenWakeWordEAL AndroidBoardControlHAL
```

## Wake word

The microphone stays open in Standby and feeds `OpenWakeWordEAL`. It is closed while the assistant
is Thinking or Speaking, so the wake word cannot interrupt a reply; the Talk button can.

The models are in `app/src/main/assets/wakeword/`:

| File | Role |
|---|---|
| `Hey_Bro_20260612_172419.onnx` | Classifier for the phrase |
| `melspectrogram.onnx`, `embedding_model.onnx` | Feature models shared by every openWakeWord phrase (openWakeWord release v0.5.1) |

To change the phrase, add another openWakeWord `.onnx` classifier to that folder and set
`WAKE_WORD_MODEL` in `VoiceAssistantService`. `WAKE_WORD_THRESHOLD` (default 0.5) sets the
sensitivity: raise it if the assistant wakes by mistake, lower it if "Hey Bro" is missed.

Debug log lines from `OpenWakeWordEAL`:

| Line | Meaning |
|---|---|
| `Initialized … in N ms` | The three models loaded |
| `Reset` | Standby capture started |
| `Score 0.xxx (threshold 0.50)` | A step scored 0.1 or more; use these to choose the threshold |
| `Listening: 125 steps, peak score …, N ms inference per 80 ms step` | Every 10 s in Standby: proof that audio is arriving, and the CPU cost |
| `Wake word detected: score …` | The trigger was sent |

Pilot limits: the microphone is reopened between the wake word and the command, so leave a short
pause after "Hey Bro".

## Layout

```
app/src/main/java/com/pilot/voiceassistant/
  audio/    shared PCM format
  hal/      MicHAL, AudioHAL and their Android implementations
  eal/      WWEAL, STTEAL, LLMEAL, TTSEAL; eal/fake holds the stand-in engines,
            eal/openwakeword the wake word engine
  core/     state machine, controller, capture loop (no Android dependencies)
  service/  VoiceAssistantService (foreground service)
  ui/       MainActivity
```

Real engines replace the stand-ins in `VoiceAssistantService.onCreate()`.
