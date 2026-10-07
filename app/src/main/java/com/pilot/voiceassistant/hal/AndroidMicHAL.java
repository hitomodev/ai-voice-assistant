package com.pilot.voiceassistant.hal;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

import com.pilot.voiceassistant.audio.AudioConfig;

/**
 * {@link MicHAL} on {@link AudioRecord}. The recorder only exists between
 * {@link #startRecording()} and {@link #stopRecording()}, so the microphone is closed whenever
 * the assistant is not listening.
 */
public final class AndroidMicHAL implements MicHAL {

    private static final String TAG = "AndroidMicHAL";

    private final Context context;
    private AudioRecord record;

    public AndroidMicHAL(Context context) {
        this.context = context.getApplicationContext();
    }

    @Override
    public boolean startRecording() {
        stopRecording();
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            Log.e(TAG, "RECORD_AUDIO permission not granted");
            return false;
        }
        int minBufferBytes = AudioRecord.getMinBufferSize(AudioConfig.SAMPLE_RATE_HZ,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferBytes <= 0) {
            Log.e(TAG, "Capture format not supported: getMinBufferSize=" + minBufferBytes);
            return false;
        }
        AudioRecord newRecord;
        try {
            newRecord = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION,
                    AudioConfig.SAMPLE_RATE_HZ, AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    Math.max(minBufferBytes, AudioConfig.CHUNK_BYTES * 10));
        } catch (IllegalArgumentException | SecurityException e) {
            Log.e(TAG, "AudioRecord could not be created", e);
            return false;
        }
        if (newRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized");
            newRecord.release();
            return false;
        }
        try {
            newRecord.startRecording();
        } catch (IllegalStateException e) {
            Log.e(TAG, "AudioRecord could not start", e);
            newRecord.release();
            return false;
        }
        if (newRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            Log.e(TAG, "AudioRecord did not enter the recording state");
            newRecord.release();
            return false;
        }
        record = newRecord;
        return true;
    }

    @Override
    public int readRawAudioChunk(byte[] buffer) {
        if (record == null) {
            return AudioRecord.ERROR_INVALID_OPERATION;
        }
        return record.read(buffer, 0, buffer.length);
    }

    @Override
    public void stopRecording() {
        if (record == null) {
            return;
        }
        try {
            record.stop();
        } catch (IllegalStateException e) {
            Log.w(TAG, "AudioRecord stop failed", e);
        }
        record.release();
        record = null;
    }

    @Override
    public void release() {
        stopRecording();
    }
}
