package com.rafael.aireceptionistapi.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.time.Duration;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicReference;

@Component
public class TwilioMediaWebSocketHandler extends TextWebSocketHandler {

    private static final Logger log = LoggerFactory.getLogger(TwilioMediaWebSocketHandler.class);
    private static final ObjectMapper M = new ObjectMapper();

    // Wichtig: keine Read-Timeouts für WebSockets
    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(Duration.ZERO)
            .readTimeout(Duration.ZERO)
            .writeTimeout(Duration.ofSeconds(10))
            .pingInterval(Duration.ofSeconds(15))
            .build();

    @Override
    public void afterConnectionEstablished(WebSocketSession twilioSession) throws Exception {

        String apiKey = System.getenv("OPENAI_API_KEY");
        String model  = System.getenv().getOrDefault("OPENAI_REALTIME_MODEL", "gpt-4o-realtime-preview");

        // "Nicht reingrätschen": default FALSE
        boolean bargeIn = Boolean.parseBoolean(System.getenv().getOrDefault("BARGE_IN", "false"));

        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPENAI_API_KEY fehlt! -> WS wird geschlossen");
            twilioSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        AtomicReference<String> streamSidRef = new AtomicReference<>(null);

        // Audio puffern, falls OpenAI schneller spricht als Twilio "start" liefert
        Queue<String> pendingAudio = new ConcurrentLinkedQueue<>();

        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        log.info("Twilio WS connected. Opening OpenAI Realtime WS (model={}) ...", model);

        WebSocket openaiWs = client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log.info("OpenAI WS OPEN ✅ status={} message={}", response.code(), response.message());

                String instructions = """
                        Du bist KEIN Chatbot. Du bist eine echte Person am Telefon.
                        Du bist die Telefon-Rezeptionistin von „Viva la Mamma“.
                        Sprich ausschließlich Deutsch (de-DE).
                        Stimme: ruhig, warm, natürlich, menschlich.
                        Antworte in ganzen, natürlichen Sätzen.
                        Normal: max. 1–2 Sätze. Bei Reservierung darfst du länger.

                        Beginne oft weich:
                        „Ja, sehr gerne.“
                        „Natürlich.“
                        „Einen kleinen Moment bitte.“

                        Bei Reservierungen frage IMMER nacheinander:
                        1) Für wie viele Personen?
                        2) Für welches Datum?
                        3) Um wie viel Uhr?
                        4) Auf welchen Namen?
                        5) Telefonnummer?

                        Wenn du unsicher bist: sag das ehrlich und biete an zu verbinden.
                        Kein Vorlesen, kein Aufzählen, keine Listen.
                        """;

                // session.update (mit Voice Settings)
                sendJson(webSocket, """
                {
                  "type": "session.update",
                  "session": {
                    "instructions": %s,
                    "voice": "nova",
                    "voice_settings": {
                      "stability": 0.45,
                      "similarity_boost": 0.65,
                      "style": 0.55,
                      "use_speaker_boost": true
                    },
                    "input_audio_format": "g711_ulaw",
                    "output_audio_format": "g711_ulaw",
                    "turn_detection": {
                      "type": "server_vad",
                      "threshold": 0.55,
                      "prefix_padding_ms": 200,
                      "silence_duration_ms": 420
                    },
                    "temperature": 0.25
                  }
                }
                """.formatted(jsonString(instructions)));

                // Greeting
                sendJson(webSocket, """
                {
                  "type":"conversation.item.create",
                  "item":{
                    "type":"message",
                    "role":"user",
                    "content":[{"type":"text","text":"Ciao und willkommen bei Viva la Mamma. Möchten Sie reservieren, etwas über das Menü wissen oder etwas anderes?"}]
                  }
                }
                """);

                sendJson(webSocket, """
                {
                  "type":"response.create",
                  "response": { "modalities": ["audio"] }
                }
                """);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonNode n = M.readTree(text);
                    String type = n.path("type").asText();

                    if ("response.output_audio.delta".equals(type)) {
                        String audioB64 = n.path("delta").asText();
                        String streamSid = streamSidRef.get();

                        if (streamSid == null) {
                            pendingAudio.add(audioB64);
                            return;
                        }
                        sendAudioToTwilio(twilioSession, streamSid, audioB64);
                        return;
                    }

                    // OPTIONAL: Barge-in nur wenn du es willst
                    if (bargeIn && "input_audio_buffer.speech_started".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid != null) {
                            safeSend(twilioSession, "{\"event\":\"clear\",\"streamSid\":\"" + streamSid + "\"}");
                        }
                        sendJson(webSocket, "{\"type\":\"response.cancel\"}");
                        return;
                    }

                    // Wenn User fertig spricht -> commit + response
                    if ("input_audio_buffer.speech_stopped".equals(type)) {
                        sendJson(webSocket, "{\"type\":\"input_audio_buffer.commit\"}");
                        sendJson(webSocket, "{\"type\":\"response.create\",\"response\":{\"modalities\":[\"audio\"]}}");
                        return;
                    }

                    if ("error".equals(type)) {
                        log.error("OpenAI realtime error: {}", text);
                    }

                } catch (Exception e) {
                    log.warn("OpenAI parse error: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("OpenAI WS FAILED ❌ {} (response={})",
                        t.getMessage(), response != null ? response.code() : "null");
                try { twilioSession.close(CloseStatus.SERVER_ERROR); } catch (Exception ignore) {}
            }

            private void sendAudioToTwilio(WebSocketSession twilioSession, String streamSid, String audioB64) {
                String twilioMsg = "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audioB64 + "\"}}";
                safeSend(twilioSession, twilioMsg);
            }
        });

        twilioSession.getAttributes().put("openaiWs", openaiWs);
        twilioSession.getAttributes().put("streamSidRef", streamSidRef);
        twilioSession.getAttributes().put("pendingAudio", pendingAudio);
        twilioSession.getAttributes().put("bargeIn", bargeIn);
    }

    @Override
    protected void handleTextMessage(WebSocketSession twilioSession, TextMessage message) throws Exception {

        WebSocket openaiWs = (WebSocket) twilioSession.getAttributes().get("openaiWs");
        if (openaiWs == null) return;

        @SuppressWarnings("unchecked")
        AtomicReference<String> streamSidRef =
                (AtomicReference<String>) twilioSession.getAttributes().get("streamSidRef");

        @SuppressWarnings("unchecked")
        Queue<String> pendingAudio =
                (Queue<String>) twilioSession.getAttributes().get("pendingAudio");

        JsonNode n = M.readTree(message.getPayload());
        String event = n.path("event").asText();

        if ("start".equals(event)) {
            String streamSid = n.path("start").path("streamSid").asText();
            streamSidRef.set(streamSid);
            log.info("Twilio start ✅ streamSid={}", streamSid);

            // 🔥 Wichtig: buffered audio flushen
            String audio;
            while ((audio = pendingAudio.poll()) != null) {
                safeSend(twilioSession,
                        "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audio + "\"}}");
            }
            return;
        }

        if ("media".equals(event)) {
            String payload = n.path("media").path("payload").asText();
            openaiWs.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + payload + "\"}");
            return;
        }

        if ("stop".equals(event)) {
            log.info("Twilio stop received");
            try { openaiWs.send("{\"type\":\"input_audio_buffer.commit\"}"); } catch (Exception ignore) {}
            try { twilioSession.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object ws = session.getAttributes().get("openaiWs");
        if (ws instanceof WebSocket ows) {
            try { ows.close(1000, "twilio closed"); } catch (Exception ignore) {}
        }
        log.info("Twilio WS closed: {}", status);
    }

    private static void sendJson(WebSocket ws, String json) {
        ws.send(json);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }

    private static void safeSend(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ignore) {}
    }
}