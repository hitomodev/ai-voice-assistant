package com.pilot.voiceassistant.eal.llama;

import com.pilot.voiceassistant.tools.Tool;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The text format of the FunctionGemma model: how the tools and the user's request are written
 * into the prompt, and how the function call is read from the output. It follows the chat
 * template stored in the model file, for tools without parameters.
 *
 * <p>The prompt is the preamble followed by the request. The preamble is the same for every
 * request, so the engine computes it once; it ends with a special token, as the engine requires.
 */
final class FunctionGemma {

    /** The model writes this after a function call; generation can stop there. */
    static final String END_OF_CALL = "<end_function_call>";

    private static final String INSTRUCTION =
            "You are a model that can do function calling with the following functions";
    private static final Pattern CALL =
            Pattern.compile("<start_function_call>call:(\\w+)\\{");

    private FunctionGemma() {
    }

    /** The developer turn that declares the tools, up to the start of the user turn. */
    static String buildPreamble(List<Tool> tools) {
        StringBuilder prompt = new StringBuilder("<start_of_turn>developer\n").append(INSTRUCTION);
        for (Tool tool : tools) {
            prompt.append("<start_function_declaration>declaration:").append(tool.getName())
                    .append("{description:<escape>").append(tool.getDescription())
                    .append("<escape>}<end_function_declaration>");
        }
        return prompt.append("<end_of_turn>\n<start_of_turn>").toString();
    }

    /** The user turn with the request, up to the point where the model answers. */
    static String buildRequest(String userText) {
        // Angle brackets are removed so that the request cannot contain the model's markers.
        return "user\n" + userText.replaceAll("[<>]", " ").trim()
                + "<end_of_turn>\n<start_of_turn>model\n";
    }

    /** Returns the name of the function the model called, or null if it answered in text. */
    static String parseCalledFunction(String output) {
        Matcher matcher = CALL.matcher(output);
        return matcher.find() ? matcher.group(1) : null;
    }
}
