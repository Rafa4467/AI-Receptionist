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

        final String apiKey = System.getenv("OPENAI_API_KEY");
        final String model = System.getenv().getOrDefault("OPENAI_REALTIME_MODEL", "gpt-realtime");

        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPENAI_API_KEY fehlt -> Twilio Session wird geschlossen");
            twilioSession.close(CloseStatus.SERVER_ERROR);
            return;
        }

        // Twilio sendet "start" manchmal später -> Audio puffern bis streamSid da ist
        AtomicReference<String> streamSidRef = new AtomicReference<>(null);
        Queue<String> pendingAudio = new ConcurrentLinkedQueue<>();

        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        log.info("Twilio WS connected. Opening OpenAI WS (model={})", model);

        WebSocket openaiWs = client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response resp) {
                log.info("OpenAI WS OPEN status={} {}", resp.code(), resp.message());

                String instructions = """
                        Du bist eine echte Telefon-Rezeptionistin von „Viva la Mamma“.
                        Sprich ausschließlich Deutsch (de-DE), warm, ruhig, natürlich.
                        Antworte in ganzen Sätzen, meist 1–2 Sätze (bei Reservierungen mehr).
                        Stelle Reservierungen IMMER der Reihe nach: Personenanzahl, Datum, Uhrzeit, Name, Telefonnummer.
                        Wenn du etwas nicht sicher weißt: sag das ehrlich und biete an zu verbinden.
                        """;

                sendJson(ws, """
                {
                  "type": "session.update",
                  "session": {
                    "instructions": %s,
                    "voice": "cedar",
                    "input_audio_format": "g711_ulaw",
                    "output_audio_format": "g711_ulaw",
                    "turn_detection": {
                      "type": "server_vad",
                      "interrupt_response": false,
                      "threshold": 0.55,
                      "prefix_padding_ms": 200,
                      "silence_duration_ms": 420
                    },
                    "temperature": 0.7
                  }
                }
                """.formatted(jsonString(instructions)));

                // ✅ input_text statt text
                sendJson(ws, """
                {
                  "type":"conversation.item.create",
                  "item":{
                    "type":"message",
                    "role":"user",
                    "content":[{"type":"input_text","text":"Ciao und willkommen bei Viva la Mamma! Möchten Sie reservieren, etwas zum Menü wissen oder ist es etwas anderes?"}]
                  }
                }
                """);

                // ✅ modalities = ["audio","text"]
                sendJson(ws, """
                {"type":"response.create","response":{"modalities":["audio","text"]}}
                """);
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonNode n = M.readTree(text);
                    String type = n.path("type").asText();

                    // ===== B) Debug: OpenAI event types =====
                    log.info("OPENAI type={}", type);

                    if ("response.output_audio.delta".equals(type)) {
                        String audioB64 = n.path("delta").asText();

                        // ===== B) Debug: audio delta length =====
                        log.info("OPENAI audio delta len={}", audioB64 != null ? audioB64.length() : -1);

                        String streamSid = streamSidRef.get();
                        if (streamSid == null) {
                            pendingAudio.add(audioB64);
                            log.info("OPENAI audio buffered (no streamSid yet) bufferedCount~{}", pendingAudio.size());
                            return;
                        }

                        sendAudioToTwilio(twilioSession, streamSid, audioB64);
                        return;
                    }

                    if ("input_audio_buffer.speech_started".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid != null) {
                            safeSend(twilioSession, "{\"event\":\"clear\",\"streamSid\":\"" + streamSid + "\"}");
                            log.info("SEND->TWILIO clear streamSid={}", streamSid);
                        }
                        return;
                    }

                    if ("input_audio_buffer.speech_stopped".equals(type)) {
                        sendJson(ws, "{\"type\":\"input_audio_buffer.commit\"}");
                        log.info("OPENAI sent input_audio_buffer.commit");

                        // ✅ modalities = ["audio","text"]
                        sendJson(ws, "{\"type\":\"response.create\",\"response\":{\"modalities\":[\"audio\",\"text\"]}}");
                        log.info("OPENAI sent response.create modalities=[audio,text]");
                        return;
                    }

                    if ("error".equals(type)) {
                        log.error("OpenAI error raw={}", text);
                    }

                } catch (Exception e) {
                    log.warn("OpenAI parse error: {}", e.getMessage());
                }
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response resp) {
                log.error("OpenAI WS FAILED: {} resp={}", t.getMessage(), resp != null ? resp.code() : "null");
                try { twilioSession.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) {}
            }

            private void sendAudioToTwilio(WebSocketSession session, String streamSid, String audioB64) {
                // ===== C) Debug before sending audio to Twilio =====
                log.info("SEND->TWILIO media streamSid={} payloadLen={}",
                        streamSid, audioB64 != null ? audioB64.length() : -1);

                String msg = "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audioB64 + "\"}}";
                safeSend(session, msg);
            }
        });

        twilioSession.getAttributes().put("openaiWs", openaiWs);
        twilioSession.getAttributes().put("streamSidRef", streamSidRef);
        twilioSession.getAttributes().put("pendingAudio", pendingAudio);
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

        // ===== A) Debug: Twilio event types =====
        log.info("TWILIO event={}", event);

        if ("start".equals(event)) {
            String streamSid = n.path("start").path("streamSid").asText();
            streamSidRef.set(streamSid);
            log.info("TWILIO start streamSid={}", streamSid);

            // flush buffered audio
            String audio;
            int flushed = 0;
            while ((audio = pendingAudio.poll()) != null) {
                flushed++;
                log.info("SEND->TWILIO buffered media streamSid={} payloadLen={}",
                        streamSid, audio != null ? audio.length() : -1);
                safeSend(twilioSession, "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audio + "\"}}");
            }
            log.info("TWILIO start flushedBufferedAudio={}", flushed);
            return;
        }

        if ("media".equals(event)) {
            String payload = n.path("media").path("payload").asText();

            // ===== A) Debug: Twilio media payload length =====
            log.info("TWILIO media payloadLen={}", payload != null ? payload.length() : -1);

            openaiWs.send("{\"type\":\"input_audio_buffer.append\",\"audio\":\"" + payload + "\"}");
            return;
        }

        if ("stop".equals(event)) {
            log.info("TWILIO stop received -> closing twilio session");
            try { twilioSession.close(); } catch (Exception ignored) {}
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        log.info("Twilio WS closed status={}", status);
        Object ws = session.getAttributes().get("openaiWs");
        if (ws instanceof WebSocket ows) {
            try { ows.close(1000, "twilio closed"); } catch (Exception ignored) {}
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
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ignored) {}
    }
}