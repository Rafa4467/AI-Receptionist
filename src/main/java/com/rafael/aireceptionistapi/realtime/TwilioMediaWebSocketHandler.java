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
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ConcurrentHashMap;
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

        AtomicReference<String> streamSidRef = new AtomicReference<>(null);
        Queue<String> pendingAudio = new ConcurrentLinkedQueue<>();

        // Tool-call state
        Map<String, String> callIdToName = new ConcurrentHashMap<>();
        Map<String, StringBuilder> callIdToArgsBuf = new ConcurrentHashMap<>();

        Request req = new Request.Builder()
                .url("wss://api.openai.com/v1/realtime?model=" + model)
                .addHeader("Authorization", "Bearer " + apiKey)
                .addHeader("OpenAI-Beta", "realtime=v1")
                .build();

        log.info("Twilio WS connected. Opening OpenAI WS (model={})", model);

        WebSocket openaiWs = client.newWebSocket(req, new WebSocketListener() {

            @Override
            public void onOpen(WebSocket ws, Response resp) {
                log.info("OPENAI onOpen CALLED. status={} msg={}", resp.code(), resp.message());

                String instructions = """
Du bist die Telefon-Rezeptionistin von „Viva la Mamma“.
Du sprichst ausschließlich Deutsch (de-DE) und klingst jung, klar weiblich, warm und freundlich – als würdest du beim Sprechen leicht lächeln.
Kurze, klare Sätze. Keine langen Monologe.

WICHTIG: Wenn der Gast ein relatives Datum sagt (z.B. „nächsten Dienstag“, „kommenden Freitag“, „übermorgen“),
dann rufe IMMER das Tool `resolve_relative_date` auf, bevor du nachfragst oder bestätigst.
Nenne danach das konkrete Datum kurz (z.B. „Dienstag, 03.02.2026“), dann erst weiter im Reservierungs-Schema.

RESERVIERUNGEN – IMMER Schritt für Schritt (genau diese Reihenfolge)
1) Personenanzahl
2) Datum
3) Uhrzeit
4) Name
5) Telefonnummer

Regeln:
- Pro Schritt nur 1 Frage.
- Bestätige knapp das Gehörte, aber NICHT übertrieben („Okay“ reicht).
- Sage NICHT „super/perfekt“, bevor der Schritt wirklich fertig ist.
""";

                // Tool definition (Function Calling)
                String toolsJson = """
                [{
                  "type":"function",
                  "name":"resolve_relative_date",
                  "description":"Löst deutsche relative Datumsangaben (z.B. nächster Dienstag, morgen, übermorgen) in ein konkretes Datum in Europe/Vienna auf.",
                  "parameters":{
                    "type":"object",
                    "properties":{
                      "phrase":{"type":"string","description":"Originalphrase des Gastes, z.B. 'nächsten Dienstag'."}
                    },
                    "required":["phrase"]
                  }
                }]
                """;

                // session.update with tools
                sendJson(ws, """
                {
                  "type": "session.update",
                  "session": {
                    "instructions": %s,
                    "voice": "coral",
                    "input_audio_format": "g711_ulaw",
                    "output_audio_format": "g711_ulaw",
                    "tools": %s,
                    "tool_choice": "auto",
                    "turn_detection": {
                      "type": "server_vad",
                      "create_response": false,
                      "interrupt_response": true,
                      "threshold": 0.45,
                      "prefix_padding_ms": 80,
                      "silence_duration_ms": 180
                    },
                    "temperature": 0.7
                  }
                }
                """.formatted(jsonString(instructions), toolsJson));

                // Begrüßung (fix, word-for-word)
                String greet = "Guten Tag und herzlich willkommen bei Viva la Mamma. Möchten Sie eine Reservierung vornehmen oder haben Sie eine andere Frage?";

                String prompt = """
Say exactly the following, word for word, without adding anything before or after:
%s
""".formatted(greet);

                sendJson(ws, """
                {
                  "type":"response.create",
                  "response":{
                    "modalities":["audio","text"],
                    "instructions": %s
                  }
                }
                """.formatted(jsonString(prompt)));
            }

            @Override
            public void onMessage(WebSocket ws, String text) {
                try {
                    JsonNode n = M.readTree(text);
                    String type = n.path("type").asText();
                    log.info("OPENAI type={}", type);

                    // 1) Tool call item appears as an output item
                    if ("response.output_item.added".equals(type) || "response.output_item.done".equals(type)) {
                        JsonNode item = n.path("item");
                        String itemType = item.path("type").asText();
                        if ("function_call".equals(itemType)) {
                            String callId = item.path("call_id").asText();
                            String name = item.path("name").asText();
                            if (callId != null && !callId.isBlank()) {
                                callIdToName.put(callId, name);
                                callIdToArgsBuf.putIfAbsent(callId, new StringBuilder());
                                log.info("TOOL CALL detected name={} call_id={}", name, callId);
                            }
                        }
                        // keep going
                    }

                    // 2) Tool arguments stream in deltas
                    if ("response.function_call_arguments.delta".equals(type)) {
                        String callId = n.path("call_id").asText();
                        String delta = n.path("delta").asText("");
                        callIdToArgsBuf.computeIfAbsent(callId, k -> new StringBuilder()).append(delta);
                        log.info("TOOL args delta call_id={} len={}", callId, delta.length());
                        return;
                    }

                    // 3) Tool arguments done -> execute backend function, send function_call_output, then response.create
                    if ("response.function_call_arguments.done".equals(type)) {
                        String callId = n.path("call_id").asText();
                        String argsStr = n.path("arguments").asText(null);

                        if ((argsStr == null || argsStr.isBlank()) && callIdToArgsBuf.containsKey(callId)) {
                            argsStr = callIdToArgsBuf.get(callId).toString();
                        }

                        String toolName = callIdToName.getOrDefault(callId, "unknown");
                        log.info("TOOL args done name={} call_id={} args={}", toolName, callId, argsStr);

                        String output = "{\"ok\":false,\"note\":\"unknown tool\"}";
                        if ("resolve_relative_date".equals(toolName)) {
                            String phrase = "";
                            try {
                                JsonNode args = M.readTree(argsStr);
                                phrase = args.path("phrase").asText("");
                            } catch (Exception ignored) {}
                            output = DateResolver.resolveGermanRelativeDate(phrase);
                        }

                        // Send function_call_output item back to OpenAI
                        sendJson(ws, """
                        {
                          "type":"conversation.item.create",
                          "item":{
                            "type":"function_call_output",
                            "call_id": %s,
                            "output": %s
                          }
                        }
                        """.formatted(jsonString(callId), jsonString(output)));

                        // Trigger model to continue speaking based on tool output
                        sendJson(ws, """
                        {
                          "type":"response.create",
                          "response":{"modalities":["audio","text"]}
                        }
                        """);

                        return;
                    }

                    // Audio to Twilio
                    if ("response.audio.delta".equals(type) || "response.output_audio.delta".equals(type)) {
                        String audioB64 = n.path("delta").asText();
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
            public void onClosing(WebSocket ws, int code, String reason) {
                log.error("OPENAI onClosing code={} reason={}", code, reason);
            }

            @Override
            public void onClosed(WebSocket ws, int code, String reason) {
                log.error("OPENAI onClosed code={} reason={}", code, reason);
            }

            @Override
            public void onFailure(WebSocket ws, Throwable t, Response resp) {
                log.error("OpenAI WS FAILED: {} resp={}", t.getMessage(), resp != null ? resp.code() : "null");
                try { twilioSession.close(CloseStatus.SERVER_ERROR); } catch (Exception ignored) {}
            }

            private void sendAudioToTwilio(WebSocketSession session, String streamSid, String audioB64) {
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

        log.info("TWILIO event={}", event);

        if ("start".equals(event)) {
            String streamSid = n.path("start").path("streamSid").asText();
            streamSidRef.set(streamSid);
            log.info("TWILIO start streamSid={}", streamSid);

            String audio;
            int flushed = 0;
            while ((audio = pendingAudio.poll()) != null) {
                flushed++;
                log.info("SEND->TWILIO buffered media streamSid={} payloadLen={}",
                        streamSid, audio != null ? audio.length() : -1);

                safeSend(twilioSession,
                        "{\"event\":\"media\",\"streamSid\":\"" + streamSid + "\",\"media\":{\"payload\":\"" + audio + "\"}}");
            }
            log.info("TWILIO start flushedBufferedAudio={}", flushed);
            return;
        }

        if ("media".equals(event)) {
            String payload = n.path("media").path("payload").asText();
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
        boolean ok = ws.send(json);
        if (!ok) {
            String head = json.length() > 120 ? json.substring(0, 120) : json;
            log.error("OPENAI ws.send returned FALSE (message not sent). First120={}", head);
        } else {
            log.info("OPENAI ws.send OK len={}", json.length());
        }
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