package com.pilot.voiceassistant.hal;

import android.content.Context;
import android.media.AudioManager;
import android.util.Log;
import android.view.KeyEvent;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;

/**
 * {@link BoardControlHAL} on the standard Android audio and media-key APIs, which work on a phone
 * and on a board that routes them. The radio has no standard Android API: it is not implemented
 * until the board's own radio interface is known.
 */
public final class AndroidBoardControlHAL implements BoardControlHAL {

    private static final String TAG = "AndroidBoardControlHAL";

    private final AudioManager audioManager;

    public AndroidBoardControlHAL(Context context) {
        audioManager = context.getSystemService(AudioManager.class);
    }

    @Override
    public boolean volumeUp() {
        return adjustVolume(AudioManager.ADJUST_RAISE);
    }

    @Override
    public boolean volumeDown() {
        return adjustVolume(AudioManager.ADJUST_LOWER);
    }

    @Override
    public boolean setMuted(boolean muted) {
        return adjustVolume(muted ? AudioManager.ADJUST_MUTE : AudioManager.ADJUST_UNMUTE);
    }

    @Override
    public boolean media(MediaCommand command) {
        int keyCode;
        switch (command) {
            case PLAY:
                keyCode = KeyEvent.KEYCODE_MEDIA_PLAY;
                break;
            case PAUSE:
                keyCode = KeyEvent.KEYCODE_MEDIA_PAUSE;
                break;
            case NEXT:
                keyCode = KeyEvent.KEYCODE_MEDIA_NEXT;
                break;
            default:
                keyCode = KeyEvent.KEYCODE_MEDIA_PREVIOUS;
                break;
        }
        Log.i(TAG, "Media command " + command);
        // Delivered to the media player that is active or was active last.
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, keyCode));
        audioManager.dispatchMediaKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, keyCode));
        return true;
    }

    @Override
    public boolean setRadioOn(boolean on) {
        Log.w(TAG, "Radio " + (on ? "on" : "off") + " requested, but it is not implemented");
        return false;
    }

    private boolean adjustVolume(int direction) {
        if (audioManager.isVolumeFixed()) {
            Log.w(TAG, "Volume is fixed on this device; adjustment " + direction + " ignored");
            return false;
        }
        try {
            audioManager.adjustStreamVolume(AudioManager.STREAM_MUSIC, direction,
                    AudioManager.FLAG_SHOW_UI);
        } catch (SecurityException e) {
            // For example when the change would switch Do Not Disturb.
            Log.w(TAG, "Volume adjustment " + direction + " refused", e);
            return false;
        }
        Log.i(TAG, "Volume adjusted " + direction + ", now "
                + audioManager.getStreamVolume(AudioManager.STREAM_MUSIC) + "/"
                + audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC));
        return true;
    }
    
    @Override
    public String currentTime(){
        LocalTime now = LocalTime.now();
        // Format as "h:mm a" (e.g., "3:45 PM") or word-friendly layout
        DateTimeFormatter formatter = DateTimeFormatter.ofPattern("h:mm a", Locale.getDefault());
        String formattedTime = now.format(formatter);
        
        // Convert to a spoken-friendly string
        return "The current time is " + formattedTime;
    }
}
