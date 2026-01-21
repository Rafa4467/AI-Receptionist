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

    // Wichtig: keine Timeouts für WS read
    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(Duration.ZERO)
            .readTimeout(Duration.ZERO)
            .writeTimeout(Duration.ofSeconds(10))
            .pingInterval(Duration.ofSeconds(15))
            .build();

    @Override
    public void afterConnectionEstablished(WebSocketSession twilioSession) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("OPENAI_REALTIME_MODEL", "gpt-4o-realtime");

        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPENAI_API_KEY fehlt! -> keine Audio-Ausgabe möglich");
            twilioSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // Audio-Puffer, falls OpenAI schneller ist als Twilio 'start'
        AtomicReference<String> streamSidRef = new AtomicReference<>(null);
        Queue<String> pendingAudio = new ConcurrentLinkedQueue<>();

        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        log.info("Twilio WS connected. Opening OpenAI Realtime WS (model={})...", model);

        WebSocket openaiWs = client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {
                log.info("OpenAI WS OPEN ✅ status={} message={}", response.code(), response.message());

                String instructions = """
                        Du bist die Telefon-Rezeptionistin von „Viva la Mamma“.
                        Sprich ausschließlich Deutsch (de-DE), warm, ruhig und natürlich.
                        Antworte kurz (max 1–2 Sätze), aber NICHT abgehackt.
                        Bestätige freundlich („Ja, sehr gerne.“).
                        Wenn du etwas nicht sicher weißt: sag das ehrlich und biete an, zu einer Mitarbeiterin zu verbinden.
                        Stelle bei Reservierung IMMER diese Fragen: Personenanzahl + Datum + Uhrzeit + Name + Telefonnummer.
                        """;

                // session.update
                sendJson(webSocket, """
                {
                  "type": "session.update",
                  "session": {
                    "instructions": %s,
                    "voice": "nova",
                    "input_audio_format": "g711_ulaw",
                    "output_audio_format": "g711_ulaw",
                    "turn_detection": { "type": "server_vad" },
                    "temperature": 0.4
                  }
                }
                """.formatted(jsonString(instructions)));

                // Begrüßung: als "assistant" Content vorbereiten -> dann response.create
                sendJson(webSocket, """
                {
                  "type":"conversation.item.create",
                  "item":{
                    "type":"message",
                    "role":"user",
                    "content":[{"type":"text","text":"Begrüße den Anrufer jetzt kurz: Ciao und willkommen bei Viva la Mamma. Frage ob Reservierung, Menü oder etwas anderes."}]
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
                            // WICHTIG: Twilio hat noch kein start geschickt -> puffern!
                            pendingAudio.add(audioB64);
                            return;
                        }
                        sendAudioToTwilio(twilioSession, streamSid, audioB64);
                        return;
                    }

                    // Wenn OpenAI merkt: User startet zu sprechen -> Buffer clear (barge-in feel)
                    if ("input_audio_buffer.speech_started".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid != null) {
                            safeSend(twilioSession, """
                            {"event":"clear","streamSid":"%s"}
                            """.formatted(streamSid));
                        }
                        return;
                    }

                    // Wenn User fertig gesprochen hat: trigger response (zuverlässiger als create_response)
                    if ("input_audio_buffer.speech_stopped".equals(type)) {
                        sendJson(webSocket, """
                        {
                          "type":"response.create",
                          "response": { "modalities": ["audio"] }
                        }
                        """);
                        return;
                    }

                    // Debug hilfreich (optional)
                    if ("error".equals(type)) {
                        log.error("OpenAI realtime error: {}", text);
                    }

                } catch (Exception e) {
                    log.warn("OpenAI message parse failed: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("OpenAI WS FAILURE ❌ {} (response={})", t.getMessage(), response != null ? response.code() : "null");
                try { twilioSession.close(CloseStatus.SERVER_ERROR); } catch (Exception ignore) {}
            }

            @Override
            public void onClosing(WebSocket webSocket, int code, String reason) {
                log.warn("OpenAI WS closing: code={} reason={}", code, reason);
            }

            @Override
            public void onClosed(WebSocket webSocket, int code, String reason) {
                log.warn("OpenAI WS closed: code={} reason={}", code, reason);
            }

            private void sendAudioToTwilio(WebSocketSession twilioSession, String streamSid, String audioB64) {
                String twilioMsg = "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audioB64 + "\"}}";
                safeSend(twilioSession, twilioMsg);
            }
        });

        twilioSession.getAttributes().put("openaiWs", openaiWs);
        twilioSession.getAttributes().put("streamSidRef", streamSidRef);
        twilioSession.getAttributes().put("pendingAudio", pendingAudio);
    }

    @Override
    protected void handleTextMessage(WebSocketSession twilioSession, TextMessage message) throws Exception {
        WebSocket openaiWs = (WebSocket) twilioSession.getAttributes().get("openaiWs");
        @SuppressWarnings("unchecked")
        AtomicReference<String> streamSidRef = (AtomicReference<String>) twilioSession.getAttributes().get("streamSidRef");
        @SuppressWarnings("unchecked")
        Queue<String> pendingAudio = (Queue<String>) twilioSession.getAttributes().get("pendingAudio");

        JsonNode n = M.readTree(message.getPayload());
        String event = n.path("event").asText();

        if ("start".equals(event)) {
            String streamSid = n.path("start").path("streamSid").asText();
            streamSidRef.set(streamSid);
            log.info("Twilio start ✅ streamSid={}", streamSid);

            // Jetzt alles gepufferte Audio flushen (DAS war dein Hauptproblem)
            String audio;
            while ((audio = pendingAudio.poll()) != null) {
                String twilioMsg = "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audio + "\"}}";
                safeSend(twilioSession, twilioMsg);
            }
            return;
        }

        if ("media".equals(event)) {
            String payload = n.path("media").path("payload").asText();
            // Twilio liefert base64 g711_ulaw -> OpenAI erwartet base64 g711_ulaw
            openaiWs.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + payload + "\"}");
            return;
        }

        if ("stop".equals(event)) {
            log.info("Twilio stop event");
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
                if (session.isOpen()) {
                    session.sendMessage(new TextMessage(payload));
                }
            }
        } catch (Exception e) {
            // wenn das fehlschlägt, ist die Verbindung meist eh schon weg
        }
    }
}