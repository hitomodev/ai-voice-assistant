package com.pilot.voiceassistant.core;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.hal.MicHAL;

import java.util.Arrays;

/**
 * One microphone capture run: opens the microphone, forwards chunks to a sink until stopped, then
 * closes the microphone. All {@link MicHAL} calls happen on the thread that runs it.
 */
final class CaptureLoop implements Runnable {

    interface Sink {
        void onAudioChunk(byte[] pcmData);
    }

    interface ErrorHandler {
        void onCaptureError(String reason);
    }

    private final MicHAL mic;
    private volatile boolean running = true;

    // Guarded by this.
    private Sink sink;
    private ErrorHandler errorHandler;
    private boolean ended;

    CaptureLoop(MicHAL mic, Sink sink, ErrorHandler errorHandler) {
        this.mic = mic;
        this.sink = sink;
        this.errorHandler = errorHandler;
    }

    /** Ends the loop after the read in progress. Safe to call from any thread, before or after start. */
    void stop() {
        running = false;
    }

    /**
     * Sends the following chunks, and a later error, somewhere else without closing the
     * microphone. Safe to call from any thread, before or after start.
     *
     * @return false if the loop has already ended; nothing changes then
     */
    synchronized boolean redirect(Sink newSink, ErrorHandler newErrorHandler) {
        if (ended || !running) {
            return false;
        }
        sink = newSink;
        errorHandler = newErrorHandler;
        return true;
    }

    @Override
    public void run() {
        if (!running) {
            end();
            return;
        }
        try {
            if (!mic.startRecording()) {
                fail("microphone could not be opened");
                return;
            }
            byte[] buffer = new byte[AudioConfig.CHUNK_BYTES];
            while (running) {
                int read = mic.readRawAudioChunk(buffer);
                if (!running) {
                    break;
                }
                if (read < 0) {
                    fail("microphone read failed (" + read + ")");
                    break;
                }
                if (read > 0) {
                    currentSink().onAudioChunk(Arrays.copyOf(buffer, read));
                }
            }
        } catch (RuntimeException e) {
            fail("microphone failure: " + e);
        } finally {
            end();
            mic.stopRecording();
        }
    }

    private synchronized Sink currentSink() {
        return sink;
    }

    private synchronized void end() {
        ended = true;
    }

    private void fail(String reason) {
        ErrorHandler handler;
        synchronized (this) {
            ended = true;
            handler = errorHandler;
        }
        handler.onCaptureError(reason);
    }
}
