package com.rafael.aireceptionistapi.controller;

import com.rafael.aireceptionistapi.ai.LlmService;
import com.rafael.aireceptionistapi.knowledge.KnowledgeService;
import com.rafael.aireceptionistapi.reservation.ReservationService;
import com.rafael.aireceptionistapi.tts.TtsService;
import com.twilio.twiml.TwiMLException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.*;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.util.Locale;

@RestController
@RequestMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
public class VoiceController {

    private final LlmService llm;
    private final ReservationService reservationService;
    private final TtsService tts;
    private final KnowledgeService knowledgeService;

    private final String PUBLIC_BASE_URL = "https://ai-receptionist-production-4df6.up.railway.app";

    public VoiceController(
            LlmService llm,
            ReservationService reservationService,
            TtsService tts,
            KnowledgeService knowledgeService
    ) {
        this.llm = llm;
        this.reservationService = reservationService;
        this.tts = tts;
        this.knowledgeService = knowledgeService;
    }

    // 1) Einstieg
    @PostMapping("/incoming")
    public String incoming() throws TwiMLException {
        String greetText =
                "Hallo und herzlich willkommen bei Viva la Mamma! Vielen Dank für Ihren Anruf. " +
                        "Geht es um eine Reservierung, das Menü oder etwas anderes?";

        String url = tts.synthesizeToUrl(greetText, PUBLIC_BASE_URL);

        VoiceResponse.Builder resp = new VoiceResponse.Builder();

        if (url == null || url.isBlank()) {
            resp.say(new Say.Builder(greetText)
                    .language(Say.Language.DE_DE)
                    .voice(Say.Voice.ALICE)
                    .build());
        } else {
            resp.play(new Play.Builder(url).build());
        }

        resp.gather(new Gather.Builder()
                .inputs(Gather.Input.SPEECH)
                .language(Gather.Language.DE_DE)
                .action("/voice/gather")
                .method(com.twilio.http.HttpMethod.POST)
                .timeout(8)
                .speechTimeout("2")
                .build());

        return resp.build().toXml();
    }

    // 2) Auswertung
    @PostMapping("/gather")
    public String gathered(
            @RequestParam(value = "To", required = false) String to,
            @RequestParam(value = "SpeechResult", required = false) String speech
    ) throws TwiMLException {

        String utterance = (speech == null ? "" : speech).toLowerCase(Locale.ROOT);

        // --- Handoff decision FIRST ---
        if (utterance.contains("verbinden")
                || utterance.contains("mitarbeiter")
                || utterance.contains("ja")
                || utterance.contains("bitte")) {

            return handoffToHuman("Alles klar, ich verbinde Sie jetzt.");
        }

        if (utterance.contains("nein")
                || utterance.contains("passt")
                || utterance.contains("egal")) {

            return playAndGather("Okay. Wobei kann ich Ihnen sonst helfen?");
        }

        // Optional: simple Intent
        if (utterance.contains("reserv") || utterance.contains("tisch")) {
            return playAndGather("Gerne! Für wie viele Personen und wann?");
        }

        // --- Knowledge-based AI ---
        var snippets = knowledgeService.retrieve(to, utterance);

        if (snippets.isEmpty()) {
            return playAndGather(
                    "Dazu habe ich gerade keine verlässliche Information. " +
                            "Soll ich Sie mit einem Mitarbeiter verbinden oder kann ich bei etwas anderem helfen?"
            );
        }

        String systemPrompt = """
                Du bist ein Telefon-Rezeptionist.
                Antworte ausschließlich mit Informationen aus SNIPPETS.
                Wenn die Antwort nicht eindeutig ist, sage ehrlich, dass du es nicht sicher weißt.
                Maximal 2 kurze Sätze. Keine Listen. Ruhig und freundlich.
                """;

        String userPrompt =
                "SNIPPETS:\n" + snippets +
                        "\n\nFRAGE:\n" + utterance;

        String aiText = llm.reply(systemPrompt, userPrompt);
        return playAndGather(aiText);
    }

    // 3) Voicemail
    @PostMapping("/voicemail")
    public String voicemail() throws TwiMLException {
        com.twilio.twiml.voice.Record rec = new com.twilio.twiml.voice.Record.Builder()
                .maxLength(120)
                .playBeep(true)
                .transcribe(true)
                .transcribeCallback("/voice/transcript")
                .finishOnKey("#")
                .build();

        return new VoiceResponse.Builder()
                .say(new Say.Builder("Nach dem Signalton können Sie sprechen. Mit der Raute Taste beenden.")
                        .language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .record(rec)
                .say(new Say.Builder("Danke! Wir melden uns so schnell wie möglich. Auf Wiederhören!")
                        .language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .build()
                .toXml();
    }

    // --- HUMAN HANDOFF (ECHT) ---
    private String handoffToHuman(String text) throws TwiMLException {
        String url = tts.synthesizeToUrl(text, PUBLIC_BASE_URL);
        String humanNumber = System.getenv("HUMAN_DIAL_NUMBER");

        VoiceResponse.Builder resp = new VoiceResponse.Builder();

        if (url == null || url.isBlank()) {
            resp.say(new Say.Builder(text)
                    .language(Say.Language.DE_DE)
                    .voice(Say.Voice.ALICE)
                    .build());
        } else {
            resp.play(new Play.Builder(url).build());
        }

        if (humanNumber == null || humanNumber.isBlank()) {
            resp.say(new Say.Builder("Leider ist gerade niemand erreichbar. Sie können eine Nachricht hinterlassen.")
                    .language(Say.Language.DE_DE)
                    .voice(Say.Voice.ALICE)
                    .build());
            resp.redirect(new Redirect.Builder("/voice/voicemail").build());
            return resp.build().toXml();
        }

        resp.dial(new Dial.Builder(humanNumber)
                .timeout(20)
                .build());

        // Wenn keiner abhebt → Voicemail
        resp.redirect(new Redirect.Builder("/voice/voicemail").build());

        return resp.build().toXml();
    }

    private String playAndGather(String text) throws TwiMLException {
        String url = tts.synthesizeToUrl(text, PUBLIC_BASE_URL);

        VoiceResponse.Builder resp = new VoiceResponse.Builder();

        if (url == null || url.isBlank()) {
            resp.say(new Say.Builder(text)
                    .language(Say.Language.DE_DE)
                    .voice(Say.Voice.ALICE)
                    .build());
        } else {
            resp.play(new Play.Builder(url).build());
        }

        resp.gather(new Gather.Builder()
                .inputs(Gather.Input.SPEECH)
                .language(Gather.Language.DE_DE)
                .action("/voice/gather")
                .method(com.twilio.http.HttpMethod.POST)
                .timeout(8)
                .speechTimeout("2")
                .build());

        return resp.build().toXml();
    }

    @PostMapping("/transcript")
    public String transcript(@RequestParam(value = "TranscriptionText", required = false) String text) {
        System.out.println("Voicemail-Transkript: " + text);
        return "<Response></Response>";
    }
}
