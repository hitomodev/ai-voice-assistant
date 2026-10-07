package com.pilot.voiceassistant.eal.llama;

/**
 * The native llama.cpp engine ({@code app/src/main/cpp}). A handle is one loaded model; it must
 * be used from one thread at a time, except for {@link #cancel(long)}.
 */
final class LlamaNative {

    static {
        System.loadLibrary("assistant_llm");
    }

    private LlamaNative() {
    }

    /**
     * @return the handle of the loaded model
     * @throws IllegalStateException if the model cannot be loaded
     */
    static native long load(String modelPath, int contextTokens, int threads);

    /**
     * Continues the preamble followed by the request, which hold the model's own turn markers,
     * until the model ends its turn, {@code stopUtf8} appears in the output, or {@code maxTokens}
     * is reached. The preamble is computed on the first call and kept for the following ones,
     * as long as it stays the same. It must end with one of the model's special tokens.
     *
     * @return the generated text as UTF-8, with the model's special tokens kept as text
     * @throws IllegalStateException if the generation fails
     */
    static native byte[] generate(long handle, byte[] preambleUtf8, byte[] requestUtf8,
            int maxTokens, byte[] stopUtf8);

    /** Makes a generation in progress return early. May be called from any thread. */
    static native void cancel(long handle);

    static native void release(long handle);
}
