package com.pilot.voiceassistant.hal;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.SystemClock;
import android.util.Log;

import com.pilot.voiceassistant.audio.AudioConfig;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * {@link AudioHAL} on {@link AudioTrack} and {@link AudioManager} audio focus, using the
 * assistant audio usage. Each response is played by its own thread, which is discarded when the
 * response ends or is stopped.
 */
public final class AndroidAudioHAL implements AudioHAL {

    private static final String TAG = "AndroidAudioHAL";
    private static final byte[] END_OF_STREAM = new byte[0];
    private static final long DRAIN_SLACK_MS = 500;
    /** The listening cue: two short rising notes. */
    private static final int[] CUE_NOTES_HZ = {660, 880};
    private static final int CUE_NOTE_MS = 90;
    private static final int CUE_FADE_MS = 10;
    private static final double CUE_AMPLITUDE = 0.3;

    private final int sampleRateHz;
    private final AudioManager audioManager;
    private final AudioAttributes attributes;
    private final AudioFocusRequest focusRequest;
    private final Object lock = new Object();

    private volatile PlaybackListener listener;
    private Playback current;
    /** Created on first use and then replayed. */
    private AudioTrack cueTrack;

    /** Plays at the sample rate of the TTS voice. */
    public AndroidAudioHAL(Context context) {
        this(context, AudioConfig.PLAYBACK_SAMPLE_RATE_HZ);
    }

    /**
     * @param sampleRateHz the sample rate of the buffers given to {@link #playAudioBuffer}
     */
    public AndroidAudioHAL(Context context, int sampleRateHz) {
        this.sampleRateHz = sampleRateHz;
        audioManager = context.getSystemService(AudioManager.class);
        attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build();
        focusRequest = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(attributes)
                .setOnAudioFocusChangeListener(this::onFocusChange)
                .build();
    }

    @Override
    public void setPlaybackListener(PlaybackListener listener) {
        this.listener = listener;
    }

    @Override
    public boolean requestAudioFocus() {
        return audioManager.requestAudioFocus(focusRequest)
                == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    }

    @Override
    public void abandonAudioFocus() {
        audioManager.abandonAudioFocusRequest(focusRequest);
    }

    @Override
    public void playListeningCue() {
        synchronized (lock) {
            try {
                if (cueTrack == null) {
                    cueTrack = createCueTrack();
                } else {
                    cueTrack.stop();
                    cueTrack.reloadStaticData();
                }
                cueTrack.play();
            } catch (RuntimeException e) {
                // The cue is only a hint: the turn goes on without it.
                Log.w(TAG, "Listening cue failed", e);
            }
        }
    }

    @Override
    public void playAudioBuffer(byte[] pcmData) {
        synchronized (lock) {
            if (current == null) {
                current = new Playback();
                current.start();
            }
            current.queue.add(pcmData);
        }
    }

    @Override
    public void endOfStream() {
        synchronized (lock) {
            if (current != null) {
                current.queue.add(END_OF_STREAM);
                return;
            }
        }
        notifyComplete();
    }

    @Override
    public void stopPlayback() {
        Playback stopped;
        synchronized (lock) {
            stopped = current;
            current = null;
        }
        if (stopped != null) {
            stopped.cancel();
        }
    }

    @Override
    public void release() {
        stopPlayback();
        abandonAudioFocus();
        synchronized (lock) {
            if (cueTrack != null) {
                cueTrack.release();
                cueTrack = null;
            }
        }
    }

    private AudioTrack createCueTrack() {
        short[] pcm = buildCue();
        AudioTrack t = new AudioTrack.Builder()
                .setAudioAttributes(attributes)
                .setAudioFormat(new AudioFormat.Builder()
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .build())
                .setBufferSizeInBytes(pcm.length * AudioConfig.BYTES_PER_SAMPLE)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();
        t.write(pcm, 0, pcm.length);
        return t;
    }

    /** Each note fades in and out, so that it does not click. */
    private short[] buildCue() {
        int noteSamples = sampleRateHz * CUE_NOTE_MS / 1000;
        int fadeSamples = sampleRateHz * CUE_FADE_MS / 1000;
        short[] pcm = new short[noteSamples * CUE_NOTES_HZ.length];
        for (int note = 0; note < CUE_NOTES_HZ.length; note++) {
            double step = 2 * Math.PI * CUE_NOTES_HZ[note] / sampleRateHz;
            for (int i = 0; i < noteSamples; i++) {
                double fade = Math.min(1.0,
                        Math.min(i, noteSamples - 1 - i) / (double) fadeSamples);
                pcm[note * noteSamples + i] =
                        (short) (Math.sin(step * i) * fade * CUE_AMPLITUDE * Short.MAX_VALUE);
            }
        }
        return pcm;
    }

    private void onFocusChange(int focusChange) {
        if (focusChange == AudioManager.AUDIOFOCUS_LOSS
                || focusChange == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
            PlaybackListener l = listener;
            if (l != null) {
                l.onAudioFocusLost();
            }
        }
    }

    private void notifyComplete() {
        PlaybackListener l = listener;
        if (l != null) {
            l.onPlaybackComplete();
        }
    }

    /** Called by a playback thread that reached the end of its stream. */
    private void onPlaybackFinished(Playback playback) {
        synchronized (lock) {
            if (current != playback) {
                return; // Stopped in the meantime: no completion for this response.
            }
            current = null;
        }
        notifyComplete();
    }

    private final class Playback extends Thread {

        final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
        private final Object trackLock = new Object();
        private AudioTrack track;
        private volatile boolean cancelled;

        Playback() {
            super("assistant-playback");
        }

        void cancel() {
            cancelled = true;
            interrupt();
            synchronized (trackLock) {
                if (track != null) {
                    try {
                        // Unblocks a write in progress and drops audio already buffered.
                        track.pause();
                        track.flush();
                    } catch (IllegalStateException e) {
                        Log.w(TAG, "AudioTrack pause failed", e);
                    }
                }
            }
        }

        @Override
        public void run() {
            boolean finished = false;
            try {
                AudioTrack t = createTrack();
                synchronized (trackLock) {
                    track = t;
                }
                t.play();
                long writtenFrames = 0;
                while (!cancelled) {
                    byte[] pcm = queue.take();
                    if (pcm == END_OF_STREAM) {
                        drain(t, writtenFrames);
                        finished = !cancelled;
                        break;
                    }
                    int written = t.write(pcm, 0, pcm.length);
                    if (written < 0) {
                        Log.e(TAG, "AudioTrack write failed: " + written);
                        finished = !cancelled;
                        break;
                    }
                    writtenFrames += written / AudioConfig.BYTES_PER_SAMPLE;
                }
            } catch (InterruptedException e) {
                // Cancelled while waiting for audio.
            } catch (RuntimeException e) {
                Log.e(TAG, "Playback failed", e);
                finished = !cancelled;
            } finally {
                synchronized (trackLock) {
                    if (track != null) {
                        track.release();
                        track = null;
                    }
                }
            }
            if (finished) {
                onPlaybackFinished(this);
            }
        }

        private AudioTrack createTrack() {
            int minBufferBytes = AudioTrack.getMinBufferSize(sampleRateHz,
                    AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
            if (minBufferBytes <= 0) {
                throw new IllegalStateException(
                        "Playback format not supported: getMinBufferSize=" + minBufferBytes);
            }
            return new AudioTrack.Builder()
                    .setAudioAttributes(attributes)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setSampleRate(sampleRateHz)
                            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .build())
                    .setBufferSizeInBytes(minBufferBytes)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .build();
        }

        /** Waits until the audio already written has been played out. */
        private void drain(AudioTrack t, long writtenFrames) throws InterruptedException {
            long remainingFrames = writtenFrames - (t.getPlaybackHeadPosition() & 0xFFFFFFFFL);
            long deadline = SystemClock.elapsedRealtime()
                    + remainingFrames * 1000 / sampleRateHz + DRAIN_SLACK_MS;
            while (!cancelled
                    && (t.getPlaybackHeadPosition() & 0xFFFFFFFFL) < writtenFrames
                    && SystemClock.elapsedRealtime() < deadline) {
                Thread.sleep(20);
            }
        }
    }
}
