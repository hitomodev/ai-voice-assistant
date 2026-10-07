package com.pilot.voiceassistant.eal.openwakeword;

import android.util.Log;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.WWEAL;

import java.io.IOException;
import java.nio.FloatBuffer;
import java.util.Arrays;
import java.util.Collections;
import java.util.Locale;
import java.util.Random;

import ai.onnxruntime.OnnxTensor;
import ai.onnxruntime.OrtEnvironment;
import ai.onnxruntime.OrtException;
import ai.onnxruntime.OrtSession;
import ai.onnxruntime.TensorInfo;

/**
 * {@link WWEAL} on openWakeWord, run with ONNX Runtime. It is a port of the streaming pipeline of
 * the openWakeWord Python library, which chains three models every 80 ms of audio:
 *
 * <pre>
 * PCM ──► melspectrogram ──► speech embedding ──► wake word classifier ──► score 0..1
 *         8 frames x 32      1 x 96, from the      from the last 16
 *                            last 76 frames        embeddings
 * </pre>
 *
 * <p>The melspectrogram and embedding models are shared by every openWakeWord wake word; only
 * the classifier is specific to the phrase.
 *
 * <p>Except for the constructor and {@link #setWakeWordListener}, every method must be called
 * from the capture thread. The listener is called on that thread too.
 */
public final class OpenWakeWordEAL implements WWEAL {

    /** Supplies the content of a model file. */
    public interface ModelSource {
        byte[] read(String name) throws IOException;
    }

    public static final String MELSPECTROGRAM_MODEL = "melspectrogram.onnx";
    public static final String EMBEDDING_MODEL = "embedding_model.onnx";
    public static final float DEFAULT_THRESHOLD = 0.5f;

    private static final String TAG = "OpenWakeWordEAL";

    /** The pipeline advances in steps of 80 ms. */
    static final int CHUNK_SAMPLES = 1280;
    /** Audio of the previous chunk that the melspectrogram of the next one overlaps. */
    private static final int CONTEXT_SAMPLES = 480;
    private static final int MEL_WINDOW_SAMPLES = 512;
    private static final int MEL_HOP_SAMPLES = 160;
    private static final int MEL_BINS = 32;
    private static final int FRAMES_PER_CHUNK = 8;
    /** Melspectrogram frames that make one embedding. */
    private static final int EMBEDDING_FRAMES = 76;
    private static final int EMBEDDING_SIZE = 96;
    /** Scores of the first chunks after a reset are ignored, as openWakeWord does. */
    private static final int WARM_UP_CHUNKS = 5;

    /** Scores from this value up are logged, to help tune the threshold. */
    private static final float LOG_SCORE_FLOOR = 0.1f;
    /** A summary is logged every 10 seconds of audio. */
    private static final int STATS_INTERVAL_CHUNKS = 125;

    private final ModelSource models;
    private final String wakeWordModel;
    private final float threshold;
    private volatile Listener listener;

    private OrtEnvironment environment;
    private OrtSession melSession;
    private OrtSession embeddingSession;
    private OrtSession wakeWordSession;
    private String melInput;
    private String embeddingInput;
    private String wakeWordInput;
    private boolean ready;

    /** Number of embeddings the classifier looks at. */
    private int featureFrames;
    /** Embeddings of noise that fill the classifier input after a reset. */
    private float[] noiseFeatures;

    private final float[] audio = new float[CONTEXT_SAMPLES + CHUNK_SAMPLES];
    private int pendingSamples;
    /** Low byte of a sample that was split across two frames, or -1. */
    private int pendingByte = -1;
    private final float[] melWindow = new float[EMBEDDING_FRAMES * MEL_BINS];
    private float[] features;
    private int chunksSinceReset;
    private float lastScore;

    private int statsChunks;
    private float statsPeakScore;
    private long statsInferenceNanos;

    /**
     * @param wakeWordModel file name of the openWakeWord classifier for the phrase
     * @param threshold score from which the phrase counts as detected, between 0 and 1
     */
    public OpenWakeWordEAL(ModelSource models, String wakeWordModel, float threshold) {
        this.models = models;
        this.wakeWordModel = wakeWordModel;
        this.threshold = threshold;
    }

    @Override
    public void setWakeWordListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void initialize() {
        long startNanos = System.nanoTime();
        try {
            environment = OrtEnvironment.getEnvironment();
            try (OrtSession.SessionOptions options = new OrtSession.SessionOptions()) {
                // The models are small; one thread keeps the standby load on a single core.
                options.setIntraOpNumThreads(1);
                options.setInterOpNumThreads(1);
                melSession = environment.createSession(models.read(MELSPECTROGRAM_MODEL), options);
                embeddingSession = environment.createSession(models.read(EMBEDDING_MODEL), options);
                wakeWordSession = environment.createSession(models.read(wakeWordModel), options);
            }
            melInput = melSession.getInputNames().iterator().next();
            embeddingInput = embeddingSession.getInputNames().iterator().next();
            wakeWordInput = wakeWordSession.getInputNames().iterator().next();

            long[] shape = ((TensorInfo) wakeWordSession.getInputInfo().get(wakeWordInput)
                    .getInfo()).getShape();
            if (shape.length != 3 || shape[1] <= 0 || shape[2] != EMBEDDING_SIZE) {
                throw new IllegalStateException(wakeWordModel + " is not an openWakeWord model:"
                        + " input shape " + Arrays.toString(shape));
            }
            featureFrames = (int) shape[1];
            features = new float[featureFrames * EMBEDDING_SIZE];
            noiseFeatures = embedNoise();
            ready = true;
            reset();
            Log.i(TAG, String.format(Locale.US,
                    "Initialized %s in %d ms: input %s, threshold %.2f, ONNX Runtime %s",
                    wakeWordModel, (System.nanoTime() - startNanos) / 1_000_000,
                    Arrays.toString(shape), threshold, environment.getVersion()));
        } catch (IOException | OrtException | RuntimeException | LinkageError e) {
            Log.e(TAG, "Initialization failed", e);
            closeSessions();
            reportError("initialization failed: " + e);
        }
    }

    @Override
    public void reset() {
        if (!ready) {
            return;
        }
        Arrays.fill(audio, 0f);
        pendingSamples = 0;
        pendingByte = -1;
        Arrays.fill(melWindow, 1f);
        System.arraycopy(noiseFeatures, 0, features, 0, features.length);
        chunksSinceReset = 0;
        lastScore = 0f;
        Log.d(TAG, "Reset");
    }

    @Override
    public void processFrame(byte[] pcmData) {
        if (!ready) {
            return;
        }
        try {
            int i = 0;
            if (pendingByte >= 0 && pcmData.length > 0) {
                addSample((short) (pendingByte | (pcmData[0] << 8)));
                pendingByte = -1;
                i = 1;
            }
            for (; i + AudioConfig.BYTES_PER_SAMPLE <= pcmData.length;
                    i += AudioConfig.BYTES_PER_SAMPLE) {
                addSample((short) ((pcmData[i] & 0xff) | (pcmData[i + 1] << 8)));
            }
            if (i < pcmData.length) {
                pendingByte = pcmData[i] & 0xff;
            }
        } catch (OrtException | RuntimeException e) {
            Log.e(TAG, "Inference failed", e);
            closeSessions();
            reportError("inference failed: " + e);
        }
    }

    @Override
    public void release() {
        closeSessions();
        Log.i(TAG, "Released");
    }

    /** The score of the most recent 80 ms step; 0 after a reset or a detection. */
    float lastScore() {
        return lastScore;
    }

    private void addSample(short sample) throws OrtException {
        if (!ready) {
            return;
        }
        // The models were trained on samples in the 16-bit range, not scaled to -1..1.
        audio[CONTEXT_SAMPLES + pendingSamples++] = sample;
        if (pendingSamples == CHUNK_SAMPLES) {
            processChunk();
            System.arraycopy(audio, CHUNK_SAMPLES, audio, 0, CONTEXT_SAMPLES);
            pendingSamples = 0;
        }
    }

    private void processChunk() throws OrtException {
        long startNanos = System.nanoTime();

        float[] mel = melspectrogram(audio, audio.length);
        if (mel.length != FRAMES_PER_CHUNK * MEL_BINS) {
            throw new IllegalStateException("melspectrogram returned " + mel.length + " values");
        }
        System.arraycopy(melWindow, mel.length, melWindow, 0, melWindow.length - mel.length);
        System.arraycopy(mel, 0, melWindow, melWindow.length - mel.length, mel.length);

        System.arraycopy(features, EMBEDDING_SIZE, features, 0, features.length - EMBEDDING_SIZE);
        embed(melWindow, 0, features, features.length - EMBEDDING_SIZE);

        float score = classify();
        long inferenceNanos = System.nanoTime() - startNanos;

        chunksSinceReset++;
        if (chunksSinceReset <= WARM_UP_CHUNKS) {
            score = 0f;
        }
        lastScore = score;
        logScore(score, inferenceNanos);

        if (score >= threshold) {
            Log.i(TAG, String.format(Locale.US, "Wake word detected: score %.3f, threshold %.2f",
                    score, threshold));
            // Start over, so the same utterance is reported only once.
            reset();
            Listener current = listener;
            if (current != null) {
                current.onWakeWordDetected();
            }
        }
    }

    private void logScore(float score, long inferenceNanos) {
        if (score >= LOG_SCORE_FLOOR) {
            Log.d(TAG, String.format(Locale.US, "Score %.3f (threshold %.2f)", score, threshold));
        }
        statsChunks++;
        statsPeakScore = Math.max(statsPeakScore, score);
        statsInferenceNanos += inferenceNanos;
        if (statsChunks == STATS_INTERVAL_CHUNKS) {
            Log.d(TAG, String.format(Locale.US,
                    "Listening: %d steps, peak score %.3f, %.1f ms inference per 80 ms step",
                    statsChunks, statsPeakScore, statsInferenceNanos / 1e6 / statsChunks));
            statsChunks = 0;
            statsPeakScore = 0f;
            statsInferenceNanos = 0;
        }
    }

    /** Returns the frames of {@code samples}, {@link #MEL_BINS} values each. */
    private float[] melspectrogram(float[] samples, int count) throws OrtException {
        try (OnnxTensor input = OnnxTensor.createTensor(environment,
                FloatBuffer.wrap(samples, 0, count), new long[] {1, count});
                OrtSession.Result result = melSession.run(
                        Collections.singletonMap(melInput, input))) {
            FloatBuffer output = ((OnnxTensor) result.get(0)).getFloatBuffer();
            float[] mel = new float[output.remaining()];
            output.get(mel);
            // The scaling the embedding model was trained with.
            for (int i = 0; i < mel.length; i++) {
                mel[i] = mel[i] / 10f + 2f;
            }
            return mel;
        }
    }

    /** Writes the embedding of the {@link #EMBEDDING_FRAMES} frames starting at {@code frame}. */
    private void embed(float[] mel, int frame, float[] out, int outOffset) throws OrtException {
        try (OnnxTensor input = OnnxTensor.createTensor(environment,
                FloatBuffer.wrap(mel, frame * MEL_BINS, EMBEDDING_FRAMES * MEL_BINS),
                new long[] {1, EMBEDDING_FRAMES, MEL_BINS, 1});
                OrtSession.Result result = embeddingSession.run(
                        Collections.singletonMap(embeddingInput, input))) {
            FloatBuffer output = ((OnnxTensor) result.get(0)).getFloatBuffer();
            if (output.remaining() != EMBEDDING_SIZE) {
                throw new IllegalStateException(
                        "embedding model returned " + output.remaining() + " values");
            }
            output.get(out, outOffset, EMBEDDING_SIZE);
        }
    }

    private float classify() throws OrtException {
        try (OnnxTensor input = OnnxTensor.createTensor(environment, FloatBuffer.wrap(features),
                new long[] {1, featureFrames, EMBEDDING_SIZE});
                OrtSession.Result result = wakeWordSession.run(
                        Collections.singletonMap(wakeWordInput, input))) {
            return ((OnnxTensor) result.get(0)).getFloatBuffer().get(0);
        }
    }

    /**
     * Embeds just enough random noise to fill the classifier input. openWakeWord starts from
     * noise rather than silence, so the first real audio is scored against a neutral background.
     */
    private float[] embedNoise() throws OrtException {
        int frames = EMBEDDING_FRAMES + (featureFrames - 1) * FRAMES_PER_CHUNK;
        float[] noise = new float[(frames - 1) * MEL_HOP_SAMPLES + MEL_WINDOW_SAMPLES];
        Random random = new Random(0);
        for (int i = 0; i < noise.length; i++) {
            noise[i] = random.nextInt(2000) - 1000;
        }
        float[] mel = melspectrogram(noise, noise.length);
        if (mel.length < frames * MEL_BINS) {
            throw new IllegalStateException("melspectrogram returned " + mel.length + " values");
        }
        float[] result = new float[featureFrames * EMBEDDING_SIZE];
        for (int i = 0; i < featureFrames; i++) {
            embed(mel, i * FRAMES_PER_CHUNK, result, i * EMBEDDING_SIZE);
        }
        return result;
    }

    private void closeSessions() {
        ready = false;
        for (OrtSession session : new OrtSession[] {melSession, embeddingSession,
                wakeWordSession}) {
            if (session != null) {
                try {
                    session.close();
                } catch (OrtException e) {
                    Log.w(TAG, "Session close failed", e);
                }
            }
        }
        melSession = null;
        embeddingSession = null;
        wakeWordSession = null;
    }

    private void reportError(String reason) {
        Listener current = listener;
        if (current != null) {
            current.onError(reason);
        }
    }
}
