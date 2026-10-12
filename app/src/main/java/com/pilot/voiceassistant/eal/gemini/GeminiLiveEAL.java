package com.pilot.voiceassistant.eal.gemini;

import android.util.Log;

import com.pilot.voiceassistant.audio.AudioConfig;
import com.pilot.voiceassistant.eal.LiveEAL;
import com.pilot.voiceassistant.tools.Tool;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayDeque;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import okio.ByteString;

/**
 * {@link LiveEAL} on the Gemini Live API, over its WebSocket. Every message is a JSON object:
 * the first one sent declares the model, the instruction and the tools; after the server
 * confirms it, microphone audio goes up and the spoken answer, its transcript and the tool calls
 * come down. Gemini detects by itself when the user stops speaking.
 *
 * <p>Callbacks arrive on the WebSocket's thread.
 */
public final class GeminiLiveEAL implements LiveEAL {

    /** The sample rate of the audio Gemini answers with. */
    public static final int OUTPUT_SAMPLE_RATE_HZ = 24000;

    private static final String TAG = "GeminiLiveEAL";
    private static final String ENDPOINT = "wss://generativelanguage.googleapis.com/ws/"
            + "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent";
    private static final String MODEL = "gemini-3.8-live";
    private static final String INPUT_MIME_TYPE = "audio/pcm;rate=" + AudioConfig.SAMPLE_RATE_HZ;
    /** Audio kept while the connection opens: 10 seconds of microphone chunks. */
    private static final int MAX_PENDING_MESSAGES = 10_000 / AudioConfig.CHUNK_MS;
    private static final int NORMAL_CLOSURE = 1000;

    private static final String INSTRUCTION =
            "You are the voice assistant of a car infotainment board. Answer in one or two short"
                    + " sentences. Always answer in the language the user speaks. Use the"
                    + " functions to control the volume, the music and the radio and to get the"
                    + " time. After a function has run, tell the user its result in the user's"
                    + " language.";

    private final String apiKey;
    private final String setupMessage;
    private final OkHttpClient client;
    private final Object lock = new Object();
    // Guarded by lock.
    private Connection current;

    /**
     * @param apiKey a Gemini API key; when empty, every conversation fails with a message that
     *         says so
     */
    public GeminiLiveEAL(String apiKey, List<Tool> tools) {
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.setupMessage = buildSetupMessage(tools);
        // A conversation has long pauses, so reads never time out.
        this.client = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    @Override
    public void connect(Listener listener) {
        close();
        if (apiKey.isEmpty()) {
            listener.onClosed("GEMINI_API_KEY is not set in local.properties");
            return;
        }
        Connection connection = new Connection(listener);
        synchronized (lock) {
            current = connection;
        }
        Log.i(TAG, "Connecting to " + MODEL);
        connection.open(client.newWebSocket(
                new Request.Builder().url(ENDPOINT + "?key=" + apiKey).build(), connection));
    }

    @Override
    public void sendAudio(byte[] pcmData) {
        send("{\"realtimeInput\":{\"audio\":{\"data\":\""
                + Base64.getEncoder().encodeToString(pcmData)
                + "\",\"mimeType\":\"" + INPUT_MIME_TYPE + "\"}}}");
    }

    @Override
    public void endAudioStream() {
        send("{\"realtimeInput\":{\"audioStreamEnd\":true}}");
    }

    @Override
    public void sendToolResult(String id, String name, String result) {
        try {
            JSONObject response = new JSONObject().put("name", name)
                    .put("response", new JSONObject().put("result", result));
            if (id != null) {
                response.put("id", id);
            }
            send(new JSONObject().put("toolResponse", new JSONObject()
                    .put("functionResponses", new JSONArray().put(response))).toString());
        } catch (JSONException e) {
            Log.e(TAG, "Tool result could not be written", e);
        }
    }

    @Override
    public void close() {
        Connection closed;
        synchronized (lock) {
            closed = current;
            current = null;
        }
        if (closed != null) {
            closed.close();
        }
    }

    @Override
    public void release() {
        close();
        client.dispatcher().executorService().shutdown();
        client.connectionPool().evictAll();
    }

    private void send(String message) {
        Connection connection;
        synchronized (lock) {
            connection = current;
        }
        if (connection != null) {
            connection.send(message);
        }
    }

    /** The tools have no parameters: a name and a description declare each of them. */
    private static String buildSetupMessage(List<Tool> tools) {
        try {
            JSONArray declarations = new JSONArray();
            for (Tool tool : tools) {
                declarations.put(new JSONObject()
                        .put("name", tool.getName())
                        .put("description", tool.getDescription())
                        // Gemini waits for the result before it answers.
                        .put("behavior", "BLOCKING"));
            }
            JSONObject setup = new JSONObject()
                    .put("model", "models/" + MODEL)
                    .put("generationConfig", new JSONObject()
                            .put("responseModalities", new JSONArray().put("AUDIO")))
                    .put("systemInstruction", new JSONObject()
                            .put("parts", new JSONArray()
                                    .put(new JSONObject().put("text", INSTRUCTION))))
                    .put("tools", new JSONArray()
                            .put(new JSONObject().put("functionDeclarations", declarations)))
                    // Both sides of the conversation are shown on the screen.
                    .put("inputAudioTranscription", new JSONObject())
                    .put("outputAudioTranscription", new JSONObject());
            return new JSONObject().put("setup", setup).toString();
        } catch (JSONException e) {
            throw new IllegalStateException(e);
        }
    }

    /** One WebSocket and what it reports. Nothing is reported once it is closed. */
    private final class Connection extends WebSocketListener {

        private final Listener listener;
        // Guarded by this.
        private final ArrayDeque<String> pending = new ArrayDeque<>();
        private WebSocket socket;
        private boolean ready;
        private boolean closed;

        Connection(Listener listener) {
            this.listener = listener;
        }

        synchronized void open(WebSocket webSocket) {
            socket = webSocket;
            if (closed) {
                webSocket.cancel();
            }
        }

        /** Until the server confirmed the setup, messages wait; the oldest are dropped. */
        synchronized void send(String message) {
            if (closed) {
                return;
            }
            if (ready) {
                socket.send(message);
                return;
            }
            if (pending.size() >= MAX_PENDING_MESSAGES) {
                pending.removeFirst();
            }
            pending.addLast(message);
        }

        synchronized void close() {
            if (closed) {
                return;
            }
            closed = true;
            pending.clear();
            if (socket == null) {
                return;
            }
            if (ready) {
                socket.close(NORMAL_CLOSURE, null);
            } else {
                socket.cancel();
            }
        }

        @Override
        public void onOpen(WebSocket webSocket, Response response) {
            synchronized (this) {
                socket = webSocket;
            }
            webSocket.send(setupMessage);
        }

        @Override
        public void onMessage(WebSocket webSocket, String text) {
            handleMessage(text);
        }

        /** The server sends its JSON in binary frames. */
        @Override
        public void onMessage(WebSocket webSocket, ByteString bytes) {
            handleMessage(bytes.utf8());
        }

        @Override
        public void onClosing(WebSocket webSocket, int code, String reason) {
            webSocket.close(NORMAL_CLOSURE, null);
            // The reason says what was wrong, for example with the key or the setup.
            fail("closed by Gemini (" + code + (reason.isEmpty() ? "" : ": " + reason) + ")");
        }

        @Override
        public void onFailure(WebSocket webSocket, Throwable t, Response response) {
            fail(response != null
                    ? "connection refused (HTTP " + response.code() + ")"
                    : "connection failed: " + t);
        }

        private void fail(String reason) {
            synchronized (this) {
                if (closed) {
                    return;
                }
                closed = true;
                pending.clear();
            }
            Log.e(TAG, reason);
            listener.onClosed(reason);
        }

        private synchronized boolean isClosed() {
            return closed;
        }

        private void handleMessage(String text) {
            if (isClosed()) {
                return;
            }
            try {
                JSONObject message = new JSONObject(text);
                if (message.has("setupComplete")) {
                    handleSetupComplete();
                }
                JSONObject content = message.optJSONObject("serverContent");
                if (content != null) {
                    handleContent(content);
                }
                JSONObject toolCall = message.optJSONObject("toolCall");
                if (toolCall != null) {
                    handleToolCall(toolCall);
                }
                if (message.has("goAway")) {
                    Log.w(TAG, "Gemini is about to end the conversation: "
                            + message.opt("goAway"));
                }
            } catch (JSONException e) {
                Log.e(TAG, "Message could not be read", e);
            }
        }

        private void handleSetupComplete() {
            synchronized (this) {
                if (closed || ready) {
                    return;
                }
                ready = true;
                for (String message : pending) {
                    socket.send(message);
                }
                pending.clear();
            }
            Log.i(TAG, "Conversation open");
            listener.onReady();
        }

        private void handleContent(JSONObject content) {
            String heard = textOf(content.optJSONObject("inputTranscription"));
            if (!heard.isEmpty()) {
                listener.onInputTranscript(heard);
            }
            String said = textOf(content.optJSONObject("outputTranscription"));
            if (!said.isEmpty()) {
                listener.onOutputTranscript(said);
            }
            JSONObject modelTurn = content.optJSONObject("modelTurn");
            JSONArray parts = modelTurn == null ? null : modelTurn.optJSONArray("parts");
            for (int i = 0; parts != null && i < parts.length(); i++) {
                JSONObject part = parts.optJSONObject(i);
                JSONObject inlineData = part == null ? null : part.optJSONObject("inlineData");
                String data = inlineData == null ? "" : inlineData.optString("data");
                if (!data.isEmpty()) {
                    listener.onAudio(Base64.getDecoder().decode(data));
                }
            }
            if (content.optBoolean("interrupted")) {
                listener.onInterrupted();
            }
            if (content.optBoolean("turnComplete")) {
                listener.onTurnComplete();
            }
        }

        private void handleToolCall(JSONObject toolCall) {
            JSONArray calls = toolCall.optJSONArray("functionCalls");
            for (int i = 0; calls != null && i < calls.length(); i++) {
                JSONObject call = calls.optJSONObject(i);
                if (call != null) {
                    listener.onToolCall(call.has("id") ? call.optString("id") : null,
                            call.optString("name"));
                }
            }
        }

        private String textOf(JSONObject transcription) {
            return transcription == null ? "" : transcription.optString("text");
        }
    }
}
