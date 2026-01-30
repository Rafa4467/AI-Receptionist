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
import java.util.concurrent.atomic.AtomicBoolean;
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

        // Barge-in state (damit wir “nachlaufende” deltas nach cancel droppen können)
        AtomicBoolean assistantSpeaking = new AtomicBoolean(false);

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
Deine Art ist positiv, aufmerksam, serviceorientiert und lebendig, aber nie hektisch oder laut. Du klingst „echt“ wie am Telefon.
Bestätige pro Schritt maximal einmal (keine doppelten Bestätigungen wie ‘alles klar’, ‘perfekt’)

STIMME & TON
- Warm, freundlich, leicht begeistert („freut mich!“), mit natürlicher Energie.
- Kurze, klare Sätze. Keine langen Monologe.
- Kling menschlich: kleine Bestätigungen („okay“, „alles klar“, „perfekt“), aber nicht zu oft.
- Wenn der Gast unsicher ist: ruhig führen, freundlich nachfragen.
- Keine Emojis, keine Aufzählungen am Telefon vorlesen.

TELEFON-REALISMUS
- Handle wie ein echter Anruf: reagiere schnell, natürlich, ohne künstliche „KI“-Erklärungen.
- Wenn du etwas nicht verstanden hast: bitte um Wiederholung, kurz und höflich.
- Wenn Hintergrundlärm/undeutlich: „Ich hab Sie gerade nicht ganz verstanden – könnten Sie das bitte kurz wiederholen?“
- Wenn der Gast gleichzeitig spricht: bleib ruhig, lass ihn ausreden, dann kurz zusammenfassen.

HAUPTZIEL
Du hilfst bei Reservierungen, Fragen zum Restaurant, Öffnungszeiten, Adresse/Anfahrt, Allergien/Sonderwünschen.
Wenn etwas außerhalb deiner Infos liegt: sag ehrlich, dass du es nicht sicher weißt, und biete an, kurz nachzufragen/zu verbinden.

RESERVIERUNGEN – IMMER Schritt für Schritt (genau diese Reihenfolge)
Wenn der Gast reservieren möchte, frage IMMER nacheinander:
1) Personenanzahl
2) Datum
3) Uhrzeit
4) Name (Vor- und Nachname, falls möglich)
5) Telefonnummer (für Rückfragen)

Regeln:
- Stelle pro Schritt nur eine klare Frage.
- Wiederhole nach jedem Schritt kurz das Gehörte („Okay, für 4 Personen.“).
- Wenn Datum/Uhrzeit unklar: stelle 1–2 kurze Rückfragen.
- Wenn der Gast “heute”/“morgen” sagt: frage zur Sicherheit nach dem Wochentag oder wiederhole das Datum verbal.

BESTÄTIGUNG
Wenn du alle 5 Punkte hast, bestätige alles in 1–2 Sätzen und frage: „Passt das so für Sie?“

ABSCHLUSS
Beende den Anruf warm und positiv.
""";

                // Session Update (mit Barge-in)
                sendJson(ws, """
                {
                  "type": "session.update",
                  "session": {
                    "instructions": %s,
                    "voice": "coral",
                    "input_audio_format": "g711_ulaw",
                    "output_audio_format": "g711_ulaw",
                    "turn_detection": {
                      "type": "server_vad",
                      "interrupt_response": true,
                      "threshold": 0.40,
                      "prefix_padding_ms": 120,
                      "silence_duration_ms": 320
                    },
                    "temperature": 0.8
                  }
                }
                """.formatted(jsonString(instructions)));

                // Fixe Begrüßung “word for word”
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

                    // AUDIO DELTAS (dein Model sendet response.audio.delta)
                    if ("response.audio.delta".equals(type) || "response.output_audio.delta".equals(type)) {
                        String audioB64 = n.path("delta").asText();

                        // Wenn wir gerade gecancelt haben, können “late deltas” reintröpfeln -> droppen
                        if (!assistantSpeaking.get()) {
                            // Wir setzen speaking erst beim ersten delta (damit wir late-delta drop sauber haben)
                            assistantSpeaking.set(true);
                        }

                        String streamSid = streamSidRef.get();
                        if (streamSid == null) {
                            pendingAudio.add(audioB64);
                            log.info("OPENAI audio buffered (no streamSid yet) bufferedCount~{}", pendingAudio.size());
                            return;
                        }

                        sendAudioToTwilio(twilioSession, streamSid, audioB64);
                        return;
                    }

                    // DONE EVENTS -> assistantSpeaking false
                    if ("response.audio.done".equals(type) || "response.done".equals(type)) {
                        assistantSpeaking.set(false);
                        log.info("OPENAI response done -> assistantSpeaking=false");
                        return;
                    }

                    // BARGE-IN: User startet zu sprechen -> Twilio clear + OpenAI response.cancel
                    if ("input_audio_buffer.speech_started".equals(type)) {
                        String streamSid = streamSidRef.get();
                        if (streamSid != null) {
                            safeSend(twilioSession, "{\"event\":\"clear\",\"streamSid\":\"" + streamSid + "\"}");
                            log.info("SEND->TWILIO clear streamSid={}", streamSid);
                        }

                        // WICHTIG: OpenAI wirklich stoppen
                        boolean ok = ws.send("{\"type\":\"response.cancel\"}");
                        assistantSpeaking.set(false); // ab hier deltas droppen (falls nachlaufen)

                        log.info("BARGE-IN: sent response.cancel ok={}", ok);
                        return;
                    }

                    // Wenn User fertig: commit + response.create
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
                // Wenn wir gecancelt haben und assistantSpeaking=false, droppe deltas (z.B. nach cancel)
                if (!assistantSpeaking.get()) return;

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

            // flush buffered audio
            String audio;
            int flushed = 0;
            while ((audio = pendingAudio.poll()) != null) {
                flushed++;
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

    // OkHttp ws.send -> boolean
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
        return "\"" + s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n") + "\"";
    }

    private static void safeSend(WebSocketSession session, String payload) {
        try {
            synchronized (session) {
                if (session.isOpen()) session.sendMessage(new TextMessage(payload));
            }
        } catch (Exception ignored) {}
    }
}