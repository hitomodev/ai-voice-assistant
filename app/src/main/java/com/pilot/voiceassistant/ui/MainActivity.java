package com.pilot.voiceassistant.ui;

import android.Manifest;
import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.os.IBinder;
import android.widget.Button;
import android.widget.TextView;

import com.pilot.voiceassistant.R;
import com.pilot.voiceassistant.core.AssistantState;
import com.pilot.voiceassistant.core.TriggerSource;
import com.pilot.voiceassistant.service.LiveAssistantService;
import com.pilot.voiceassistant.service.VoiceAssistantService;

/**
 * Talk button and state display. Starts {@link VoiceAssistantService} once the microphone
 * permission is granted, and stays bound to it while visible.
 */
public final class MainActivity extends Activity implements VoiceAssistantService.UiListener {

    private static final int PERMISSION_REQUEST = 1;

    private TextView stateText;
    private TextView wakeText;
    private TextView errorText;
    private TextView transcriptText;
    private TextView responseText;
    private Button talkButton;

    private VoiceAssistantService service;
    private boolean bound;
    private boolean permissionRequested;

    private final ServiceConnection connection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder binder) {
            service = ((VoiceAssistantService.LocalBinder) binder).getService();
            service.setUiListener(MainActivity.this);
            talkButton.setEnabled(true);
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            service = null;
            talkButton.setEnabled(false);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        stateText = findViewById(R.id.state_text);
        wakeText = findViewById(R.id.wake_text);
        errorText = findViewById(R.id.error_text);
        transcriptText = findViewById(R.id.transcript_text);
        responseText = findViewById(R.id.response_text);
        talkButton = findViewById(R.id.talk_button);
        talkButton.setOnClickListener(v -> {
            if (service != null) {
                service.onTrigger(TriggerSource.UI_BUTTON);
            }
        });
    }

    @Override
    protected void onStart() {
        super.onStart();
        if (hasMicPermission()) {
            startAndBindService();
        } else if (!permissionRequested) {
            permissionRequested = true;
            requestPermissions(new String[] {
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.POST_NOTIFICATIONS
            }, PERMISSION_REQUEST);
        } else {
            errorText.setText(R.string.mic_permission_needed);
        }
    }

    @Override
    protected void onStop() {
        if (bound) {
            if (service != null) {
                service.setUiListener(null);
            }
            unbindService(connection);
            bound = false;
            service = null;
            talkButton.setEnabled(false);
        }
        super.onStop();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions,
            int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != PERMISSION_REQUEST) {
            return;
        }
        if (hasMicPermission()) {
            startAndBindService();
        } else {
            errorText.setText(R.string.mic_permission_needed);
        }
    }

    private boolean hasMicPermission() {
        return checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void startAndBindService() {
        if (bound) {
            return;
        }
        errorText.setText("");
        // The two assistants listen for the same wake word, so only one may run.
        stopService(new Intent(this, LiveAssistantService.class));
        Intent intent = new Intent(this, VoiceAssistantService.class);
        startForegroundService(intent);
        bound = bindService(intent, connection, Context.BIND_AUTO_CREATE);
    }

    // ---- VoiceAssistantService.UiListener (main thread) ----

    @Override
    public void onStateChanged(AssistantState state) {
        switch (state) {
            case STANDBY:
                stateText.setText(R.string.state_standby);
                wakeText.setTextColor(getColor(R.color.wake_hint));
                wakeText.setText(R.string.wake_hint);
                break;
            case LISTENING:
                stateText.setText(R.string.state_listening);
                wakeText.setText("");
                errorText.setText("");
                break;
            case THINKING:
                stateText.setText(R.string.state_thinking);
                break;
            case SPEAKING:
                stateText.setText(R.string.state_speaking);
                break;
        }
    }

    /** Shows for the whole turn that it was the wake word that woke the assistant. */
    @Override
    public void onTurnStarted(TriggerSource source) {
        if (source == TriggerSource.WAKE_WORD) {
            wakeText.setTextColor(getColor(R.color.wake_detected));
            wakeText.setText(R.string.wake_detected);
        } else {
            wakeText.setText("");
        }
    }

    /** Shows the words as they are recognized, in place of the previous exchange. */
    @Override
    public void onPartialTranscript(String textSoFar) {
        transcriptText.setText(textSoFar);
        responseText.setText("");
    }

    @Override
    public void onTranscript(String transcript) {
        transcriptText.setText(transcript);
    }

    @Override
    public void onResponseText(String responseSoFar) {
        responseText.setText(responseSoFar);
    }

    @Override
    public void onError(String reason) {
        errorText.setText(getString(R.string.error_prefix, reason));
    }
}
