package com.pilot.voiceassistant.eal.llama;

import android.util.Log;

import com.pilot.voiceassistant.eal.LLMEAL;
import com.pilot.voiceassistant.eal.Message;
import com.pilot.voiceassistant.tools.Tool;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * {@link LLMEAL} on a FunctionGemma model run by llama.cpp on the device. The model does one
 * thing: it picks the tool that matches the user's request. The engine runs that tool and
 * answers with the tool's own confirmation sentence; when the model picks no tool, the answer is
 * a fixed sentence, because this model is not made for conversation.
 *
 * <p>Each request is handled on its own: the model is given the last user message only. The
 * model is only touched on the engine's own thread. Loading starts in the constructor; a request
 * made before it finished waits for it. Callbacks arrive on the engine's thread.
 */
public final class LlamaLLMEAL implements LLMEAL {

    private static final String TAG = "LlamaLLMEAL";

    /** Room for the tool list, the request and the answer. */
    private static final int CONTEXT_TOKENS = 2048;
    /** A function call is about ten tokens long. */
    private static final int MAX_OUTPUT_TOKENS = 32;
    private static final int THREADS = 4;

    private static final String NO_TOOL_REPLY =
            "Sorry, I can only control the volume, the music and the radio.";

    private final List<Tool> tools;
    private final byte[] preamble;
    private final byte[] endOfCall = FunctionGemma.END_OF_CALL.getBytes(StandardCharsets.UTF_8);
    private final ExecutorService worker =
            Executors.newSingleThreadExecutor(r -> new Thread(r, "assistant-llm"));
    /** Changes on every request, cancel and release; an older request delivers nothing. */
    private final AtomicLong generation = new AtomicLong();
    /** The native model, or 0. Written on the engine's thread. */
    private volatile long handle;

    // Engine thread only.
    private String loadError = "not loaded";

    /**
     * @param modelFile the FunctionGemma model in GGUF format, on the device's storage
     */
    public LlamaLLMEAL(File modelFile, List<Tool> tools) {
        this.tools = tools;
        this.preamble = FunctionGemma.buildPreamble(tools).getBytes(StandardCharsets.UTF_8);
        post(() -> load(modelFile));
    }

    @Override
    public void generateResponse(List<Message> conversation, LLMCallback callback) {
        final long current = generation.incrementAndGet();
        final String request = conversation.get(conversation.size() - 1).getText();
        post(() -> respond(current, request, callback));
    }

    @Override
    public void cancelGeneration() {
        generation.incrementAndGet();
        long current = handle;
        if (current != 0) {
            LlamaNative.cancel(current);
        }
    }

    @Override
    public void release() {
        cancelGeneration();
        post(() -> {
            if (handle != 0) {
                long released = handle;
                handle = 0;
                LlamaNative.release(released);
                loadError = "released";
            }
        });
        worker.shutdown();
    }

    private void post(Runnable task) {
        try {
            worker.execute(task);
        } catch (RejectedExecutionException e) {
            // Released: requests that are still on their way are dropped.
        }
    }

    // ---- Engine thread ----

    private void load(File modelFile) {
        if (!modelFile.isFile()) {
            // The app has to create the directory itself: one created with adb is owned by the
            // shell user, and the app cannot read inside it.
            File directory = modelFile.getParentFile();
            if (directory != null && !directory.isDirectory() && !directory.mkdirs()) {
                Log.w(TAG, "Could not create " + directory);
            }
            loadError = "model file not found: " + modelFile;
            Log.e(TAG, loadError + " (push it with adb, then restart the app)");
            return;
        }
        long startNanos = System.nanoTime();
        try {
            long loaded = LlamaNative.load(modelFile.getAbsolutePath(), CONTEXT_TOKENS, THREADS);
            // Computes the tool list now, so that the first command is as fast as the others.
            generate(loaded, "hello", 1);
            handle = loaded;
            Log.i(TAG, "Model " + modelFile.getName() + " loaded in "
                    + (System.nanoTime() - startNanos) / 1_000_000 + " ms");
        } catch (RuntimeException | LinkageError e) {
            Log.e(TAG, "Model could not be loaded", e);
            loadError = "model could not be loaded: " + e.getMessage();
        }
    }

    private void respond(long current, String request, LLMCallback callback) {
        if (current != generation.get()) {
            return;
        }
        if (handle == 0) {
            callback.onError(loadError);
            return;
        }
        long startNanos = System.nanoTime();
        String output;
        try {
            output = generate(handle, request, MAX_OUTPUT_TOKENS);
        } catch (RuntimeException e) {
            Log.e(TAG, "Generation failed", e);
            if (current == generation.get()) {
                callback.onError("generation failed: " + e.getMessage());
            }
            return;
        }
        Log.i(TAG, "\"" + request + "\" -> " + output + " ("
                + (System.nanoTime() - startNanos) / 1_000_000 + " ms)");
        // Checked again: a cancelled request must not run its tool.
        Log.i(TAG, "HITLE current: " + current);
        if (current != generation.get()) {
            return;
        }
        String reply = runCalledTool(FunctionGemma.parseCalledFunction(output));
        Log.i("HITLE", "reply "+ reply);
        callback.onTextChunk(reply);
        callback.onComplete();
    }

    private String generate(long model, String request, int maxTokens) {
        return new String(LlamaNative.generate(model, preamble,
                FunctionGemma.buildRequest(request).getBytes(StandardCharsets.UTF_8), maxTokens,
                endOfCall), StandardCharsets.UTF_8);
    }

    /** Runs the tool with that name and returns what the assistant says. */
    private String runCalledTool(String name) {
        if (name == null) {
            return NO_TOOL_REPLY;
        }
        for (Tool tool : tools) {
            if (tool.getName().equals(name)) {
                Log.i(TAG, "Running tool " + name);
                try {
                    return tool.run();
                } catch (RuntimeException e) {
                    Log.e(TAG, "Tool " + name + " failed", e);
                    return "Sorry, that did not work.";
                }
            }
        }
        Log.w(TAG, "The model called an unknown function: " + name);
        return NO_TOOL_REPLY;
    }
}
