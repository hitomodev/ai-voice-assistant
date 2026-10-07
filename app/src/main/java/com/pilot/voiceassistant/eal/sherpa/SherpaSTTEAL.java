package com.pilot.voiceassistant.eal.sherpa;

import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.EndpointConfig;
import com.k2fsa.sherpa.onnx.EndpointRule;
import com.k2fsa.sherpa.onnx.FeatureConfig;
import com.k2fsa.sherpa.onnx.OnlineModelConfig;
import com.k2fsa.sherpa.onnx.OnlineRecognizer;
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig;
import com.k2fsa.sherpa.onnx.OnlineStream;
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig;
import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.STTEAL;

import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link STTEAL} on the sherpa-onnx streaming recognizer, running on the device. The model is an
 * English streaming zipformer transducer, read from the APK assets.
 *
 * <p>The recognizer and its stream are only touched on the engine's own thread: every call is
 * queued to it, so decoding never holds up the capture thread. Loading the model starts in the
 * constructor; a transcription started before it finished waits for it. Callbacks arrive on the
 * engine's thread.
 */
public final class SherpaSTTEAL implements STTEAL {

    private static final String TAG = "SherpaSTTEAL";

    private static final String MODEL_DIR = "stt/";
    private static final String ENCODER = "encoder-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
    private static final String DECODER = "decoder-epoch-99-avg-1-chunk-16-left-128.onnx";
    private static final String JOINER = "joiner-epoch-99-avg-1-chunk-16-left-128.int8.onnx";
    private static final String TOKENS = "tokens.txt";
    private static final int FEATURE_DIM = 80;
    private static final int DECODER_THREADS = 2;

    /** Silence after speech that ends the utterance. Shorter cuts people off, longer feels slow. */
    private static final float END_OF_SPEECH_SILENCE_S = 1.0f;
    /** Silence that restarts decoding while nothing has been said yet. */
    private static final float IDLE_RESET_SILENCE_S = 2.4f;
    /** An utterance is ended at this length even if the user is still talking. */
    private static final float MAX_UTTERANCE_S = 20f;
    /** Silence appended on a manual stop, so the last word is decoded. */
    private static final float TAIL_PADDING_S = 0.5f;

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "assistant-stt"));
    /** Changes on every start, cancel and release; queued work of an older value is dropped. */
    private final AtomicLong generation = new AtomicLong();

    // Engine thread only.
    private OnlineRecognizer recognizer;
    private String loadError = "not loaded";
    private OnlineStream stream;
    private long streamGeneration;
    private STTCallback callback;
    private String lastText = "";

    public SherpaSTTEAL(AssetManager assets) {
        post(() -> load(assets));
    }

    @Override
    public void startTranscription(STTCallback callback) {
        final long current = generation.incrementAndGet();
        post(() -> {
            closeStream();
            if (current != generation.get()) {
                return;
            }
            if (recognizer == null) {
                callback.onError(loadError);
                return;
            }
            try {
                stream = recognizer.createStream("");
            } catch (RuntimeException e) {
                Log.e(TAG, "Stream could not be created", e);
                callback.onError("stream could not be created: " + e);
                return;
            }
            streamGeneration = current;
            this.callback = callback;
            lastText = "";
        });
    }

    @Override
    public void feedAudioChunk(byte[] pcmData) {
        final long current = generation.get();
        post(() -> {
            if (isActive(current)) {
                decode(toSamples(pcmData), false);
            }
        });
    }

    @Override
    public void finishTranscription() {
        final long current = generation.get();
        post(() -> {
            if (isActive(current)) {
                int padding = (int) (TAIL_PADDING_S * AudioConfig.SAMPLE_RATE_HZ);
                decode(new float[padding], true);
            }
        });
    }

    @Override
    public void cancel() {
        generation.incrementAndGet();
        post(this::closeStream);
    }

    @Override
    public void release() {
        generation.incrementAndGet();
        post(() -> {
            closeStream();
            if (recognizer != null) {
                recognizer.release();
                recognizer = null;
                loadError = "released";
            }
        });
        worker.shutdown();
    }

    private void post(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // Released: audio and calls that are still on their way are dropped.
        }
    }

    // ---- Engine thread ----

    private void load(AssetManager assets) {
        long startNanos = System.nanoTime();
        try {
            OnlineTransducerModelConfig transducer = new OnlineTransducerModelConfig();
            transducer.setEncoder(MODEL_DIR + ENCODER);
            transducer.setDecoder(MODEL_DIR + DECODER);
            transducer.setJoiner(MODEL_DIR + JOINER);

            OnlineModelConfig model = new OnlineModelConfig();
            model.setTransducer(transducer);
            model.setTokens(MODEL_DIR + TOKENS);
            model.setNumThreads(DECODER_THREADS);
            model.setDebug(false);

            EndpointConfig endpoint = new EndpointConfig();
            endpoint.setRule1(new EndpointRule(false, IDLE_RESET_SILENCE_S, 0f));
            endpoint.setRule2(new EndpointRule(true, END_OF_SPEECH_SILENCE_S, 0f));
            endpoint.setRule3(new EndpointRule(false, 0f, MAX_UTTERANCE_S));

            OnlineRecognizerConfig config = new OnlineRecognizerConfig();
            config.setFeatConfig(new FeatureConfig(AudioConfig.SAMPLE_RATE_HZ, FEATURE_DIM, 0f));
            config.setModelConfig(model);
            config.setEndpointConfig(endpoint);
            config.setEnableEndpoint(true);
            config.setDecodingMethod("greedy_search");

            recognizer = new OnlineRecognizer(assets, config);
            Log.i(TAG, "Model loaded in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "Model could not be loaded", e);
            loadError = "model could not be loaded: " + e;
        }
    }

    private boolean isActive(long current) {
        return stream != null && current == streamGeneration && current == generation.get();
    }

    /**
     * Feeds samples to the recognizer and reports what it heard. The utterance ends when the
     * recognizer detects the end of speech, or right away when {@code lastSamples} is set.
     */
    private void decode(float[] samples, boolean lastSamples) {
        try {
            stream.acceptWaveform(samples, AudioConfig.SAMPLE_RATE_HZ);
            if (lastSamples) {
                stream.inputFinished();
            }
            while (recognizer.isReady(stream)) {
                recognizer.decode(stream);
            }
            String text = recognizer.getResult(stream).getText().trim().toLowerCase(Locale.US);
            if (!text.equals(lastText)) {
                lastText = text;
                if (!text.isEmpty()) {
                    callback.onPartialTranscript(text);
                }
            }
            if (lastSamples) {
                complete(text);
            } else if (recognizer.isEndpoint(stream)) {
                if (text.isEmpty()) {
                    // Only silence so far: keep waiting. The caller owns the no-speech timeout.
                    recognizer.reset(stream);
                } else {
                    complete(text);
                }
            }
        } catch (RuntimeException e) {
            Log.e(TAG, "Decoding failed", e);
            STTCallback failed = callback;
            closeStream();
            failed.onError("decoding failed: " + e);
        }
    }

    private void complete(String text) {
        Log.i(TAG, "Final transcript: " + text);
        STTCallback done = callback;
        closeStream();
        done.onEndOfSpeech();
        done.onFinalTranscript(text);
    }

    private void closeStream() {
        if (stream != null) {
            stream.release();
            stream = null;
        }
        callback = null;
    }

    /** The recognizer takes samples scaled to -1..1. */
    private static float[] toSamples(byte[] pcmData) {
        float[] samples = new float[pcmData.length / AudioConfig.BYTES_PER_SAMPLE];
        for (int i = 0; i < samples.length; i++) {
            int low = pcmData[2 * i] & 0xff;
            int high = pcmData[2 * i + 1];
            samples[i] = (short) (low | (high << 8)) / 32768f;
        }
        return samples;
    }
}
