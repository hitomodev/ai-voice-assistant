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

import com.pilot.voiceassistant.BuildConfig;
import com.pilot.voiceassistant.R;
import com.pilot.voiceassistant.core.AssistantState;
import com.pilot.voiceassistant.core.ExecutorScheduler;
import com.pilot.voiceassistant.core.LiveAssistantController;
import com.pilot.voiceassistant.core.TriggerListener;
import com.pilot.voiceassistant.core.TriggerSource;
import com.pilot.voiceassistant.eal.gemini.GeminiLiveEAL;
import com.pilot.voiceassistant.eal.openwakeword.OpenWakeWordEAL;
import com.pilot.voiceassistant.hal.AndroidAudioHAL;
import com.pilot.voiceassistant.hal.AndroidBoardControlHAL;
import com.pilot.voiceassistant.hal.AndroidMicHAL;
import com.pilot.voiceassistant.tools.BoardTools;
import com.pilot.voiceassistant.tools.Tool;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground service that hosts the Modern assistant: the same wake word and board tools as
 * {@link VoiceAssistantService}, with Gemini Live in place of the STT, LLM and TTS engines. It
 * relays the controller's updates to the bound UI on the main thread.
 *
 * <p>It must be started while the app is visible and RECORD_AUDIO is granted, as Android 14
 * requires for a microphone foreground service. Only one of the two services may run at a time.
 */
public final class LiveAssistantService extends Service implements TriggerListener {

    /** Receives assistant updates on the main thread. */
    public interface UiListener {
        void onStateChanged(AssistantState state);

        /** A conversation started from {@code source}; called after the change to {@code LISTENING}. */
        void onSessionStarted(TriggerSource source);

        void onUserText(String textSoFar);

        void onResponseText(String textSoFar);

        void onError(String reason);
    }

    public final class LocalBinder extends Binder {
        public LiveAssistantService getService() {
            return LiveAssistantService.this;
        }
    }

    private static final String TAG = "LiveAssistantService";
    private static final String CHANNEL_ID = "live_assistant";
    private static final int NOTIFICATION_ID = 2;

    /** Folder in the APK assets that holds the openWakeWord models. */
    private static final String WAKE_WORD_ASSETS = "wakeword/";
    /** The openWakeWord classifier for "Hey Bro". */
    private static final String WAKE_WORD_MODEL = "Hey_Bro_20260612_172419.onnx";

    private final IBinder binder = new LocalBinder();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private ExecutorScheduler scheduler;
    private ExecutorService captureExecutor;
    private LiveAssistantController controller;
    /** Turns the screen on and keeps it on for the length of a conversation. */
    private PowerManager.WakeLock screenLock;

    // Main thread only.
    private UiListener uiListener;
    private AssistantState state = AssistantState.STANDBY;
    private TriggerSource sessionSource;

    @Override
    public void onCreate() {
        super.onCreate();
        // The only wake lock that turns on a screen that is off, from a service.
        @SuppressWarnings("deprecation")
        int screenLockFlags = PowerManager.SCREEN_BRIGHT_WAKE_LOCK
                | PowerManager.ACQUIRE_CAUSES_WAKEUP;
        screenLock = getSystemService(PowerManager.class)
                .newWakeLock(screenLockFlags, "voiceassistant:live");
        screenLock.setReferenceCounted(false);
        scheduler = new ExecutorScheduler("live-control",
                failure -> Log.e(TAG, "Control task failed", failure));
        captureExecutor = Executors.newSingleThreadExecutor(
                r -> new Thread(r, "live-capture"));
        List<Tool> tools = BoardTools.create(new AndroidBoardControlHAL(this));
        // Only the wake word runs on the device; the conversation runs in Gemini Live.
        controller = new LiveAssistantController(
                new AndroidMicHAL(this),
                new AndroidAudioHAL(this, GeminiLiveEAL.OUTPUT_SAMPLE_RATE_HZ),
                new OpenWakeWordEAL(this::readWakeWordAsset, WAKE_WORD_MODEL,
                        OpenWakeWordEAL.DEFAULT_THRESHOLD),
                new GeminiLiveEAL(BuildConfig.GEMINI_API_KEY, tools),
                tools,
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
     * Main thread only. The listener immediately receives the current state and, during a
     * conversation, what started it.
     */
    public void setUiListener(UiListener listener) {
        uiListener = listener;
        if (listener != null) {
            listener.onStateChanged(state);
            if (state != AssistantState.STANDBY && sessionSource != null) {
                listener.onSessionStarted(sessionSource);
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
    private final class ControllerListener implements LiveAssistantController.Listener {

        @Override
        public void onStateChanged(AssistantState newState) {
            Log.i(TAG, "State: " + newState);
            mainHandler.post(() -> {
                state = newState;
                if (newState == AssistantState.STANDBY) {
                    screenLock.release();
                } else {
                    // Released on the return to STANDBY, which ends every conversation.
                    screenLock.acquire();
                }
                if (uiListener != null) {
                    uiListener.onStateChanged(newState);
                }
            });
        }

        @Override
        public void onSessionStarted(TriggerSource source) {
            Log.i(TAG, "Conversation started by " + source);
            mainHandler.post(() -> {
                sessionSource = source;
                if (uiListener != null) {
                    uiListener.onSessionStarted(source);
                }
            });
        }

        @Override
        public void onUserText(String textSoFar) {
            Log.d(TAG, "User: " + textSoFar);
            mainHandler.post(() -> {
                if (uiListener != null) {
                    uiListener.onUserText(textSoFar);
                }
            });
        }

        @Override
        public void onResponseText(String textSoFar) {
            Log.d(TAG, "Assistant: " + textSoFar);
            mainHandler.post(() -> {
                if (uiListener != null) {
                    uiListener.onResponseText(textSoFar);
                }
            });
        }

        @Override
        public void onToolRun(String name, String result) {
            Log.i(TAG, "Tool " + name + " -> " + result);
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
