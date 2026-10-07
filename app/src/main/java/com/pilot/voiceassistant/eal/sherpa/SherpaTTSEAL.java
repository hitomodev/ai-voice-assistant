package com.pilot.voiceassistant.eal.sherpa;

import android.content.res.AssetManager;
import android.util.Log;

import com.k2fsa.sherpa.onnx.OfflineTts;
import com.k2fsa.sherpa.onnx.OfflineTtsConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsModelConfig;
import com.k2fsa.sherpa.onnx.OfflineTtsVitsModelConfig;
import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.TTSEAL;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

import kotlin.jvm.functions.Function1;

/**
 * {@link TTSEAL} on the sherpa-onnx offline synthesizer with a Piper voice, running on the
 * device. Each text chunk is synthesized as a whole and delivered in short buffers.
 *
 * <p>The synthesizer is only touched on the engine's own thread. Loading the voice starts in the
 * constructor; text fed before it finished waits for it. Callbacks arrive on the engine's thread.
 */
public final class SherpaTTSEAL implements TTSEAL {

    private static final String TAG = "SherpaTTSEAL";

    private static final String VOICE_DIR = "tts/";
    private static final String MODEL = "en_US-lessac-medium.onnx";
    private static final String TOKENS = "tokens.txt";
    /** Pronunciation data. The native code reads it as files, so it is copied out of the APK. */
    private static final String ESPEAK_DATA = "espeak-ng-data";
    private static final String COPY_DONE_MARKER = ".copied";
    private static final int SYNTHESIS_THREADS = 2;
    private static final int SPEAKER_ID = 0;
    private static final float SPEED = 1.0f;
    private static final int BUFFER_MS = 100;

    private static final class Session {
        final long id;
        final TTSCallback callback;

        Session(long id, TTSCallback callback) {
            this.id = id;
            this.callback = callback;
        }
    }

    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "assistant-tts"));
    /** Changes on every start, stop and release; work of an older session is dropped. */
    private final AtomicLong generation = new AtomicLong();
    /** The synthesis that still accepts text, or null. */
    private volatile Session session;

    // Engine thread only.
    private OfflineTts tts;
    private String loadError = "not loaded";

    /**
     * @param dataRoot a directory private to the app, where the pronunciation data is unpacked
     */
    public SherpaTTSEAL(AssetManager assets, File dataRoot) {
        post(() -> load(assets, dataRoot));
    }

    @Override
    public void startSynthesis(TTSCallback callback) {
        session = new Session(generation.incrementAndGet(), callback);
    }

    @Override
    public void feedText(String textChunk) {
        final Session current = session;
        if (current != null) {
            post(() -> synthesize(current, textChunk));
        }
    }

    @Override
    public void finishInput() {
        final Session current = session;
        if (current == null) {
            return;
        }
        session = null;
        post(() -> {
            if (isCurrent(current)) {
                current.callback.onComplete();
            }
        });
    }

    @Override
    public void stopSynthesis() {
        generation.incrementAndGet();
        session = null;
    }

    @Override
    public void release() {
        stopSynthesis();
        post(() -> {
            if (tts != null) {
                tts.release();
                tts = null;
                loadError = "released";
            }
        });
        worker.shutdown();
    }

    private void post(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // Released: text and calls that are still on their way are dropped.
        }
    }

    private boolean isCurrent(Session s) {
        return s.id == generation.get();
    }

    // ---- Engine thread ----

    private void load(AssetManager assets, File dataRoot) {
        long startNanos = System.nanoTime();
        try {
            File dataDir = new File(dataRoot, VOICE_DIR + ESPEAK_DATA);
            unpack(assets, VOICE_DIR + ESPEAK_DATA, dataDir);

            OfflineTtsVitsModelConfig vits = new OfflineTtsVitsModelConfig();
            vits.setModel(VOICE_DIR + MODEL);
            vits.setTokens(VOICE_DIR + TOKENS);
            vits.setDataDir(dataDir.getAbsolutePath());

            OfflineTtsModelConfig model = new OfflineTtsModelConfig();
            model.setVits(vits);
            model.setNumThreads(SYNTHESIS_THREADS);
            model.setDebug(false);

            OfflineTtsConfig config = new OfflineTtsConfig();
            config.setModel(model);

            OfflineTts loaded = new OfflineTts(assets, config);
            if (loaded.sampleRate() != AudioConfig.PLAYBACK_SAMPLE_RATE_HZ) {
                int voiceRate = loaded.sampleRate();
                loaded.release();
                throw new IllegalStateException("voice is " + voiceRate + " Hz, playback is "
                        + AudioConfig.PLAYBACK_SAMPLE_RATE_HZ + " Hz");
            }
            tts = loaded;
            Log.i(TAG, "Voice loaded in " + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
        } catch (IOException | RuntimeException | LinkageError e) {
            Log.e(TAG, "Voice could not be loaded", e);
            loadError = "voice could not be loaded: " + e;
        }
    }

    private void synthesize(Session current, String text) {
        if (!isCurrent(current)) {
            return;
        }
        if (tts == null) {
            current.callback.onError(loadError);
            return;
        }
        long startNanos = System.nanoTime();
        try {
            // Not a lambda: the native code looks up a method named invoke that takes float[]
            // and returns Integer, which only a class written out like this has.
            tts.generateWithCallback(text, SPEAKER_ID, SPEED, new Function1<float[], Integer>() {
                /** Returning 0 stops the synthesis of a session that was stopped. */
                @Override
                public Integer invoke(float[] samples) {
                    if (!isCurrent(current)) {
                        return 0;
                    }
                    deliver(current, samples);
                    return 1;
                }
            });
            Log.i(TAG, "Synthesized " + text.length() + " characters in "
                    + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
        } catch (RuntimeException e) {
            Log.e(TAG, "Synthesis failed", e);
            if (isCurrent(current)) {
                current.callback.onError("synthesis failed: " + e);
            }
        }
    }

    /** Converts samples in -1..1 to 16-bit PCM and hands them over in short buffers. */
    private void deliver(Session current, float[] samples) {
        int samplesPerBuffer = AudioConfig.PLAYBACK_SAMPLE_RATE_HZ * BUFFER_MS / 1000;
        for (int start = 0; start < samples.length; start += samplesPerBuffer) {
            if (!isCurrent(current)) {
                return;
            }
            int count = Math.min(samplesPerBuffer, samples.length - start);
            byte[] pcm = new byte[count * AudioConfig.BYTES_PER_SAMPLE];
            for (int i = 0; i < count; i++) {
                float value = Math.max(-1f, Math.min(1f, samples[start + i]));
                short sample = (short) (value * Short.MAX_VALUE);
                pcm[2 * i] = (byte) (sample & 0xff);
                pcm[2 * i + 1] = (byte) ((sample >> 8) & 0xff);
            }
            current.callback.onAudioBuffer(pcm);
        }
    }

    /** Copies an asset directory to {@code target} once; later starts find the marker file. */
    private static void unpack(AssetManager assets, String assetDir, File target)
            throws IOException {
        File marker = new File(target, COPY_DONE_MARKER);
        if (marker.exists()) {
            return;
        }
        long startNanos = System.nanoTime();
        copy(assets, assetDir, target);
        if (!marker.createNewFile()) {
            throw new IOException("could not create " + marker);
        }
        Log.i(TAG, "Unpacked " + assetDir + " in "
                + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
    }

    private static void copy(AssetManager assets, String assetPath, File target)
            throws IOException {
        String[] children = assets.list(assetPath);
        if (children == null || children.length == 0) {
            // A file: directories always have entries here.
            try (InputStream in = assets.open(assetPath);
                    OutputStream out = new FileOutputStream(target)) {
                in.transferTo(out);
            }
            return;
        }
        if (!target.isDirectory() && !target.mkdirs()) {
            throw new IOException("could not create " + target);
        }
        for (String child : children) {
            copy(assets, assetPath + "/" + child, new File(target, child));
        }
    }
}
