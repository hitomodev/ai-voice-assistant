package com.pilot.voiceassistant.eal.openwakeword;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.WWEAL;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Runs the real models from the app assets on recorded audio, with the desktop build of ONNX
 * Runtime. The clips are synthetic speech in the pipeline's PCM format.
 */
public class OpenWakeWordEALTest {

    private static final String MODEL = "Hey_Bro_20260612_172419.onnx";
    private static final int CHUNK_BYTES =
            OpenWakeWordEAL.CHUNK_SAMPLES * AudioConfig.BYTES_PER_SAMPLE;
    private static final float NEVER = 2f;

    private static final class RecordingListener implements WWEAL.Listener {
        int detections;
        final List<String> errors = new ArrayList<>();

        @Override
        public void onWakeWordDetected() {
            detections++;
        }

        @Override
        public void onError(String reason) {
            errors.add(reason);
        }
    }

    private final RecordingListener listener = new RecordingListener();
    private OpenWakeWordEAL engine;

    @After
    public void tearDown() {
        if (engine != null) {
            engine.release();
        }
    }

    private static byte[] readModel(String name) throws IOException {
        return Files.readAllBytes(Paths.get("src/main/assets/wakeword", name));
    }

    private static byte[] readResource(String name) throws IOException {
        try (InputStream in = OpenWakeWordEALTest.class.getResourceAsStream("/wakeword/" + name)) {
            assertNotNull(name, in);
            return in.readAllBytes();
        }
    }

    private void start(float threshold) {
        engine = new OpenWakeWordEAL(OpenWakeWordEALTest::readModel, MODEL, threshold);
        engine.setWakeWordListener(listener);
        engine.initialize();
        assertEquals(new ArrayList<String>(), listener.errors);
    }

    private void feed(byte[] pcm, int frameBytes) {
        for (int i = 0; i < pcm.length; i += frameBytes) {
            engine.processFrame(Arrays.copyOfRange(pcm, i, Math.min(pcm.length, i + frameBytes)));
        }
    }

    @Test
    public void heyBro_isDetectedOnce() throws IOException {
        start(OpenWakeWordEAL.DEFAULT_THRESHOLD);

        feed(readResource("hey_bro.pcm"), AudioConfig.CHUNK_BYTES);

        assertEquals(1, listener.detections);
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void heyBro_isDetectedWhenFramesSplitSamples() throws IOException {
        start(OpenWakeWordEAL.DEFAULT_THRESHOLD);

        feed(readResource("hey_bro.pcm"), 333);

        assertEquals(1, listener.detections);
    }

    @Test
    public void otherSpeech_isNotDetected() throws IOException {
        start(OpenWakeWordEAL.DEFAULT_THRESHOLD);

        feed(readResource("other_speech.pcm"), AudioConfig.CHUNK_BYTES);

        assertEquals(0, listener.detections);
        assertTrue(listener.errors.isEmpty());
    }

    @Test
    public void afterReset_thePhraseIsDetectedAgain() throws IOException {
        start(OpenWakeWordEAL.DEFAULT_THRESHOLD);
        byte[] pcm = readResource("hey_bro.pcm");

        feed(pcm, AudioConfig.CHUNK_BYTES);
        engine.reset();
        feed(pcm, AudioConfig.CHUNK_BYTES);

        assertEquals(2, listener.detections);
    }

    /**
     * The expected scores come from the openWakeWord Python library (0.6.0) on the same clip and
     * models, one per 80 ms step. The first steps are skipped because the library starts from
     * random noise.
     */
    @Test
    public void scores_matchTheOpenWakeWordPythonLibrary() throws IOException {
        start(NEVER);
        byte[] pcm = readResource("hey_bro.pcm");
        String[] expected = new String(readResource("hey_bro.scores.txt"),
                StandardCharsets.UTF_8).trim().split("\\s+");
        int firstComparable = 27;
        assertTrue(expected.length > firstComparable);

        float peak = 0f;
        for (int step = 0; step < expected.length; step++) {
            engine.processFrame(
                    Arrays.copyOfRange(pcm, step * CHUNK_BYTES, (step + 1) * CHUNK_BYTES));
            if (step >= firstComparable) {
                assertEquals("step " + step, Float.parseFloat(expected[step]),
                        engine.lastScore(), 0.01f);
                peak = Math.max(peak, engine.lastScore());
            }
        }
        assertTrue("peak " + peak, peak > 0.9f);
        assertEquals(0, listener.detections);
    }

    @Test
    public void missingModel_isReportedAndFramesAreIgnored() {
        engine = new OpenWakeWordEAL(OpenWakeWordEALTest::readModel, "missing.onnx",
                OpenWakeWordEAL.DEFAULT_THRESHOLD);
        engine.setWakeWordListener(listener);

        engine.initialize();
        engine.reset();
        engine.processFrame(new byte[CHUNK_BYTES]);

        assertEquals(1, listener.errors.size());
        assertEquals(0, listener.detections);
    }
}
