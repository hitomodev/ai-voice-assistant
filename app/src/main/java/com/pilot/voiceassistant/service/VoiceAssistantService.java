package com.pilot.voiceassistant.service;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.PowerManager;
import android.util.Log;

import com.pilot.voiceassistant.R;
import com.pilot.voiceassistant.core.AssistantController;
import com.pilot.voiceassistant.core.AssistantState;
import com.pilot.voiceassistant.core.ExecutorScheduler;
import com.pilot.voiceassistant.core.TriggerListener;
import com.pilot.voiceassistant.core.TriggerSource;
import com.pilot.voiceassistant.eal.llama.LlamaLLMEAL;
import com.pilot.voiceassistant.eal.openwakeword.OpenWakeWordEAL;
import com.pilot.voiceassistant.eal.sherpa.SherpaSTTEAL;
import com.pilot.voiceassistant.eal.sherpa.SherpaTTSEAL;
import com.pilot.voiceassistant.hal.AndroidAudioHAL;
import com.pilot.voiceassistant.hal.AndroidBoardControlHAL;
import com.pilot.voiceassistant.hal.AndroidMicHAL;

import com.pilot.voiceassistant.tools.BoardTools;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that hosts the assistant. It builds the hardware drivers and engines, hands
 * them to {@link AssistantController}, and relays its updates to the bound UI on the main thread.
 *
 * <p>It must be started while the app is visible and RECORD_AUDIO is granted, as Android 14
 * requires for a microphone foreground service.
 */
public final class VoiceAssistantService extends Service implements TriggerListener {

    /** Receives assistant updates on the main thread. */
    public interface UiListener {
        void onStateChanged(AssistantState state);

        /** A turn started from {@code source}; called after the change to {@code LISTENING}. */
        void onTurnStarted(TriggerSource source);

        /** The words heard so far while listening; they may still change. */
        void onPartialTranscript(String textSoFar);

        void onTranscript(String transcript);

        void onResponseText(String responseSoFar);

        void onError(String reason);
    }

    public final class LocalBinder extends Binder {
        public VoiceAssistantService getService() {
            return VoiceAssistantService.this;
        }
    }

    private static final String TAG = "VoiceAssistantService";
    private static final String CHANNEL_ID = "voice_assistant";
    private static final int NOTIFICATION_ID = 1;

    /** Folder in the APK assets that holds the openWakeWord models. */
    private static final String WAKE_WORD_ASSETS = "wakeword/";
    /** The openWakeWord classifier for "Hey Bro". */
    private static final String WAKE_WORD_MODEL = "Hey_Bro_20260612_172419.onnx";
    /**
     * The LLM model is not in the APK: it is pushed to this file under the app's external files
     * directory, /sdcard/Android/data/com.pilot.voiceassistant/files/.
     */
    private static final String LLM_MODEL_FILE = "llm/functiongemma-270m-it-q8_0.gguf";
    /** Raise it if the wake word fires by mistake, lower it if it is missed. */
    private static final float WAKE_WORD_THRESHOLD = OpenWakeWordEAL.DEFAULT_THRESHOLD;

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorScheduler scheduler;
    private ExecutorService captureExecutor;
    private AssistantController controller;
    /** Turns the screen on and keeps it on for the length of a turn. */
    private PowerManager.WakeLock screenLock;

    // Main thread only.
    private UiListener uiListener;
    private AssistantState state = AssistantState.STANDBY;
    private TriggerSource turnSource;

    @Override
    public void onCreate() {
        super.onCreate();
        // The only wake lock that turns on a screen that is off, from a service.
        @SuppressWarnings("deprecation")
        int screenLockFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        screenLock = getSystemService(PowerManager.class)
                .newWakeLock(screenLockFlags, "voiceassistant:turn");
        screenLock.setReferenceCounted(false);
        scheduler = new ExecutorScheduler("assistant-control",
                failure -> Log.e(TAG, "Control task failed", failure));
        captureExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "assistant-capture"));
        // Every engine runs on the device. The LLM turns the transcript into a board command.
        controller = new AssistantController(
                new AndroidMicHAL(this),
                new AndroidAudioHAL(this),
                new OpenWakeWordEAL(this::readWakeWordAsset, WAKE_WORD_MODEL,
                        WAKE_WORD_THRESHOLD),
                new SherpaSTTEAL(getAssets()),
                new LlamaLLMEAL(new File(getExternalFilesDir(null), LLM_MODEL_FILE),
                        BoardTools.create(new AndroidBoardControlHAL(this))),
                new SherpaTTSEAL(getAssets(), getFilesDir()),
                scheduler,
                captureExecutor,
                new ControllerListener());
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO not granted; stopping");
            stopSelf();
            return START_NOT_STICKY;
        }
        startForeground(NOTIFICATION_ID, buildNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE);
        // The microphone may only be opened once the service is in the foreground.
        controller.startWakeWord();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return binder;
    }

    @Override
    public void onDestroy() {
        controller.release();
        scheduler.shutdown();
        try {
            // The release task queues the microphone release on the capture thread.
            scheduler.awaitTermination(1000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        captureExecutor.shutdown();
        mainHandler.removeCallbacksAndMessages(null);
        screenLock.release();
        super.onDestroy();
    }

    @Override
    public void onTrigger(TriggerSource source) {
        Log.d(TAG, "Trigger: " + source);
        controller.onTrigger(source);
    }

    /**
     * Main thread only. The listener immediately receives the current state and, during a turn,
     * what started it.
     */
    public void setUiListener(UiListener listener) {
        uiListener = listener;
        if (listener != null) {
            listener.onStateChanged(state);
            if (state != AssistantState.STANDBY && turnSource != null) {
                listener.onTurnStarted(turnSource);
            }
        }
    }

    private byte[] readWakeWordAsset(String name) throws IOException {
        try (InputStream in = getAssets().open(WAKE_WORD_ASSETS + name)) {
            return in.readAllBytes();
        }
    }

    private Notification buildNotification() {
        NotificationChannel channel = new NotificationChannel(CHANNEL_ID,
                getString(R.string.notification_channel), NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(channel);
        return new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.app_name))
                .setContentText(getString(R.string.notification_text))
                .setSmallIcon(R.drawable.ic_mic)
                .setOngoing(true)
                .build();
    }

    /** Called on the control thread; forwards to the UI on the main thread. */
    private final class ControllerListener implements AssistantController.Listener {

        @Override
        public void onStateChanged(AssistantState newState) {
            Log.i(TAG, "State: " + newState);
            mainHandler.post(() -> {
                state = newState;
                if (newState == AssistantState.STANDBY) {
                    screenLock.release();
                } else {
                    // Released on the return to STANDBY, which ends every turn.
                    screenLock.acquire();
                }
                if (uiListener != null) {
                    uiListener.onStateChanged(newState);
                }
            });
        }

        @Override
        public void onTurnStarted(TriggerSource source) {
            Log.i(TAG, "Turn started by " + source);
            mainHandler.post(() -> {
                turnSource = source;
                if (uiListener != null) {
                    uiListener.onTurnStarted(source);
                }
            });
        }

        @Override
        public void onPartialTranscript(String textSoFar) {
            mainHandler.post(() -> {
                if (uiListener != null) {
                    uiListener.onPartialTranscript(textSoFar);
                }
            });
        }

        @Override
        public void onTranscript(String transcript) {
            Log.i(TAG, "Transcript: " + transcript);
            mainHandler.post(() -> {
                if (uiListener != null) {
                    uiListener.onTranscript(transcript);
                }
            });
        }

        @Override
        public void onResponseText(String responseSoFar) {
            mainHandler.post(() -> {
                if (uiListener != null) {
                    uiListener.onResponseText(responseSoFar);
                }
            });
        }

        @Override
        public void onError(String reason) {
            Log.e(TAG, "Error: " + reason);
            mainHandler.post(() -> {
                if (uiListener != null) {
                    uiListener.onError(reason);
                }
            });
        }
    }
}
