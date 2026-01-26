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

    private final OkHttpClient client = new OkHttpClient.Builder()
            .callTimeout(Duration.ZERO)
            .readTimeout(Duration.ZERO)
            .writeTimeout(Duration.ofSeconds(10))
            .pingInterval(Duration.ofSeconds(15))
            .build();

    @Override
    public void afterConnectionEstablished(WebSocketSession twilioSession) throws Exception {
        String apiKey = System.getenv("OPENAI_API_KEY");
        String model = System.getenv().getOrDefault("OPENAI_REALTIME_MODEL", "gpt-4o-mini-realtime");

        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPENAI_API_KEY fehlt!");
            twilioSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        AtomicReference<String> streamSidRef = new AtomicReference<>(null);
        Queue<String> pendingAudio = new ConcurrentLinkedQueue<>();

        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        WebSocket openaiWs = client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket webSocket, Response response) {

                String instructions = """
                        Du bist KEIN Chatbot.
Du bist eine echte Person am Telefon.
Du bist die Telefon-Rezeptionistin von „Viva la Mamma“.

Sprich ausschließlich Deutsch (de-DE).
Deine Stimme ist ruhig, warm, natürlich, menschlich.
Du klingst wie eine echte Mitarbeiterin, nicht wie KI.

Antworte immer in ganzen, natürlichen Sätzen.
Maximal 1–2 Sätze, außer es ist eine Reservierung.

Beginne Antworten oft weich:
„Ja, sehr gerne.“
„Natürlich.“
„Einen kleinen Moment bitte.“

Bei Reservierungen:
Frage IMMER nacheinander:
– Für wie viele Personen?
– Für welches Datum?
– Um wie viel Uhr?
– Auf welchen Namen?
– Telefonnummer?

Wenn du unsicher bist:
Sag das ehrlich und biete an, zu verbinden.

Mache kleine natürliche Pausen.
Kein Vorlesen, kein Aufzählen, kein Roboterstil.
""";

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

                        safeSend(twilioSession,
                                "{\"event\":\"media\",\"streamSid\":\"" + streamSid +
                                        "\",\"media\":{\"payload\":\"" + audioB64 + "\"}}");
                    }

                    if ("input_audio_buffer.speech_started".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid != null) {
                            safeSend(twilioSession,
                                    "{\"event\":\"clear\",\"streamSid\":\"" + streamSid + "\"}");
                        }
                        sendJson(webSocket, "{\"type\":\"response.cancel\"}");
                    }

                    if ("input_audio_buffer.speech_stopped".equals(type)) {
                        sendJson(webSocket, "{\"type\":\"input_audio_buffer.commit\"}");
                        sendJson(webSocket, """
                        { "type": "response.cancel" }
                        """);
                        sendJson(webSocket,
                                "{\"type\":\"response.create\",\"response\":{\"modalities\":[\"audio\"]}}");
                    }

                } catch (Exception e) {
                    log.warn("OpenAI parse error", e);
                }
            }

            @Override
            public void onFailure(WebSocket webSocket, Throwable t, Response response) {
                log.error("OpenAI WS FAILED: {}", t.getMessage());

                // ❌ NICHT den ganzen Server töten
                // ❌ NICHT RuntimeException werfen

                try {
                    if (twilioSession.isOpen()) {
                        safeSend(twilioSession,
                                "{\"event\":\"clear\",\"streamSid\":\"" + streamSidRef.get() + "\"}");
                        twilioSession.close();
                    }
                } catch (Exception ignored) {}
            }
        });

        twilioSession.getAttributes().put("openaiWs", openaiWs);
        twilioSession.getAttributes().put("streamSidRef", streamSidRef);
        twilioSession.getAttributes().put("pendingAudio", pendingAudio);
    }

    @Override
    protected void handleTextMessage(WebSocketSession twilioSession, TextMessage message) throws Exception {

        WebSocket openaiWs = (WebSocket) twilioSession.getAttributes().get("openaiWs");
        if (openaiWs == null) {
            // OpenAI WS noch nicht bereit → nichts tun
            return;
        }

        @SuppressWarnings("unchecked")
        AtomicReference<String> streamSidRef =
                (AtomicReference<String>) twilioSession.getAttributes().get("streamSidRef");

        JsonNode n = M.readTree(message.getPayload());
        String event = n.path("event").asText();

    /* -------------------------
       START (Twilio Stream init)
       ------------------------- */
        if ("start".equals(event)) {
            String streamSid = n.path("start").path("streamSid").asText();
            streamSidRef.set(streamSid);
            log.info("Twilio stream started: {}", streamSid);
            return;
        }

    /* -------------------------
       MEDIA (Audio vom User)
       ------------------------- */
        if ("media".equals(event)) {
            String payload = n.path("media").path("payload").asText();

            // 1️⃣ Audio an OpenAI anhängen
            openaiWs.send(
                    "{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + payload + "\"}"
            );

            return;
        }

    /* -------------------------
       STOP (Call beendet)
       ------------------------- */
        if ("stop".equals(event)) {
            log.info("Twilio stop received");

            try {
                // OpenAI sauber beenden
                openaiWs.send("{\"type\":\"input_audio_buffer.commit\"}");
                openaiWs.send("{\"type\":\"response.cancel\"}");
            } catch (Exception ignore) {}

            try {
                twilioSession.close();
            } catch (Exception ignore) {}

            return;
        }
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
        } catch (Exception ignore) {}
    }
}