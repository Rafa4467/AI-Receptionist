package com.rafael.aireceptionistapi.realtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.*;
import okio.ByteString;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.concurrent.atomic.AtomicReference;

@Component
public class TwilioMediaWebSocketHandler extends TextWebSocketHandler {

    private static final ObjectMapper M = new ObjectMapper();

    private final OkHttpClient client = new OkHttpClient();

    @Override
    public void afterConnectionEstablished(WebSocketSession twilioSession) throws Exception {

        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("OPENAI_REALTIME_MODEL", "gpt-4o-realtime");

        // OpenAI Realtime WS
        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        AtomicReference<String> streamSidRef = new AtomicReference<>(null);

        WebSocket openaiWs = client.newWebSocket(req, new WebSocketListener() {
            @Override
            public void onOpen(WebSocket webSocket, Response response) {

                // Session konfigurieren: Twilio liefert/erwartet g711_ulaw 8kHz :contentReference[oaicite:1]{index=1}
                String instructions = """
                        Du bist die Telefon-Rezeptionistin von „Viva la Mamma“.
                        Sprich ausschließlich Deutsch (de-DE), warm, ruhig und natürlich.
                        Antworte kurz (max 1–2 Sätze).
                        Bestätige freundlich („Ja, sehr gerne.“).
                        Wenn du etwas nicht sicher weißt: sag das ehrlich und biete an, zu einer Mitarbeiterin zu verbinden.
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

                // Erste Begrüßung (damit es sofort startet wie vAPI)
                sendJson(webSocket, """
                  {
                    "type":"conversation.item.create",
                    "item":{
                      "type":"message",
                      "role":"assistant",
                      "content":[{"type":"text","text":"Ciao und herzlich willkommen bei Viva la Mamma! Geht es um eine Reservierung, unser Menü oder etwas anderes?"}]
                    }
                  }
                """);
                sendJson(webSocket, """
                  {"type":"response.create"}
                """);
            }

            @Override
            public void onMessage(WebSocket webSocket, String text) {
                try {
                    JsonNode n = M.readTree(text);
                    String type = n.path("type").asText();

                    // Audio vom Modell kommt in Deltas – wir schicken es als Twilio "media" zurück :contentReference[oaicite:2]{index=2}
                    if ("response.output_audio.delta".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid == null) return;

                        String audioB64 = n.path("delta").asText();
                        String twilioMsg = """
                          {"event":"media","streamSid":"%s","media":{"payload":"%s"}}
                        """.formatted(streamSid, audioB64);

                        twilioSession.sendMessage(new TextMessage(twilioMsg));
                    }

                    // Optional: wenn User reingrätscht → Twilio Buffer clear (macht es vAPI-ähnlicher)
                    if ("input_audio_buffer.speech_started".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid != null) {
                            twilioSession.sendMessage(new TextMessage("""
                              {"event":"clear","streamSid":"%s"}
                            """.formatted(streamSid)));
                        }
                        // Optional: response.cancel (wenn du aggressives Barge-In willst)
                        // sendJson(webSocket, "{\"type\":\"response.cancel\"}");
                    }

                } catch (Exception ignore) {}
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                try { twilioSession.close(); } catch (Exception ignore) {}
            }
        });

        // Twilio -> OpenAI: kommt in handleTextMessage
        twilioSession.getAttributes().put("openaiWs", openaiWs);
        twilioSession.getAttributes().put("streamSidRef", streamSidRef);
    }

    @Override
    protected void handleTextMessage(WebSocketSession twilioSession, TextMessage message) throws Exception {

        WebSocket openaiWs = (WebSocket) twilioSession.getAttributes().get("openaiWs");
        @SuppressWarnings("unchecked")
        AtomicReference<String> streamSidRef = (AtomicReference<String>) twilioSession.getAttributes().get("streamSidRef");

        JsonNode n = M.readTree(message.getPayload());
        String event = n.path("event").asText();

        if ("start".equals(event)) {
            String streamSid = n.path("start").path("streamSid").asText();
            streamSidRef.set(streamSid);
            return;
        }

        if ("media".equals(event)) {
            String payload = n.path("media").path("payload").asText();

            // Twilio liefert Base64 mulaw/8000 – OpenAI erwartet g711_ulaw base64 :contentReference[oaicite:3]{index=3}
            String append = """
              {"type":"input_audio_buffer.append","audio":"%s"}
            """.formatted(payload);

            openaiWs.send(append);
        }

        if ("stop".equals(event)) {
            try { twilioSession.close(); } catch (Exception ignore) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        Object ws = session.getAttributes().get("openaiWs");
        if (ws instanceof WebSocket ows) {
            try { ows.close(1000, "twilio closed"); } catch (Exception ignore) {}
        }
    }

    private static void sendJson(WebSocket ws, String json) {
        ws.send(json);
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n") + "\"";
    }
}
