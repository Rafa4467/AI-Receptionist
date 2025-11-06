package com.rafael.aireceptionistapi.controller;

import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.voice.Record;
import com.twilio.twiml.TwiMLException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.rafael.aireceptionistapi.ai.LlmService;
import com.rafael.aireceptionistapi.reservation.ReservationService;
import com.twilio.twiml.voice.Play;
import com.rafael.aireceptionistapi.tts.TtsService;


import java.util.Locale;

@RestController
@RequestMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
public class VoiceController {

    private final LlmService llm;
    private final ReservationService reservationService;
    private final TtsService tts;
    private final String PUBLIC_BASE_URL = "https://ai-receptionist-production-4df6.up.railway.app"; // <- DEINE URL

    public VoiceController(LlmService llm, ReservationService reservationService, TtsService tts) {
        this.llm = llm;
        this.reservationService = reservationService;
        this.tts = tts;
    }

    // 1) Einstieg: Begrüßung + Spracherkennung aktivieren
    @PostMapping("/incoming")
    public String incoming() throws TwiMLException {
        String greetText = "Hallo und herzlich willkommen bei Viva la Mamma! Vielen Dank für Ihren Anruf. "
                + "Geht es um eine Reservierung, das Menü oder etwas anderes?";

        String url = tts.synthesizeToUrl(greetText, PUBLIC_BASE_URL);

        // ✅ Fallback, falls TTS nicht lieferbar ist
        if (url == null || url.isBlank()) {
            return new VoiceResponse.Builder()
                    .say(new Say.Builder(greetText)
                            .language(Say.Language.DE_DE)
                            .voice(Say.Voice.ALICE)
                            .build())
                    .gather(new Gather.Builder()
                            .inputs(Gather.Input.SPEECH)
                            .language(Gather.Language.DE_DE)
                            .action("/voice/gather")
                            .method(com.twilio.http.HttpMethod.POST)
                            .timeout(8)
                            .speechTimeout("2")
                            .build())
                    .build()
                    .toXml();
        }

        // Normale TTS-Variante
        Play play = new Play.Builder(url).build();
        Gather gather = new Gather.Builder()
                .inputs(Gather.Input.SPEECH)
                .language(Gather.Language.DE_DE)
                .action("/voice/gather")
                .method(com.twilio.http.HttpMethod.POST)
                .timeout(8)
                .speechTimeout("2")
                .build();

        return new VoiceResponse.Builder()
                .play(play)
                .gather(gather)
                .redirect(new com.twilio.twiml.voice.Redirect.Builder("/voice/incoming").build())
                .build()
                .toXml();
    }



    // 2) Auswertung der Spracherkennung
    @PostMapping("/gather")
    public String gathered(@RequestParam(value="SpeechResult", required=false) String speech) throws TwiMLException {
        String utterance = (speech == null ? "" : speech).toLowerCase(Locale.ROOT);

        // ganz simple Intent-Erkennung
        if (utterance.contains("reserv") || utterance.contains("tisch")) {
            return playAndGather("Gerne! Für wie viele Personen und wann?");
        }
        if (utterance.contains("öffnungs") || utterance.contains("uhr")) {
            return playAndGather("Unsere Öffnungszeiten sind Montag bis Freitag 11 bis 22 Uhr und Samstag 12 bis 22 Uhr. Möchten Sie reservieren?");
        }
        if (utterance.contains("adress") || utterance.contains("wo") || utterance.contains("finden")) {
            return playAndGather("Unsere Adresse ist Musterstraße 12 in Wien. Soll ich gleich einen Tisch vormerken?");
        }
        if (utterance.contains("menü") || utterance.contains("speisekarte")) {
            return playAndGather("Unsere Tageskarte: Suppe, Pasta, Veggie Bowl und Hausburger. Soll ich eine Reservierung für Sie anlegen?");
        }
        if (utterance.contains("mitarbeit") || utterance.contains("chef") || utterance.contains("verbinden")) {
            // Optional: direkt verbinden (hier nur Ansage)
            return playOnly("Einen Moment, ich verbinde Sie mit einem Mitarbeiter.");
            // Für echtes Durchstellen: <Dial> mit Zielnummer implementieren
        }


        // --- AI-Fallback kommt hier ---
        String systemPrompt = """
        Du bist der freundliche, präzise Telefon-Rezeptionist eines Restaurants in Wien.
        Sprich kurz, höflich und auf Deutsch. Bei Reservierungen frage nacheinander nach nur die kommenden Sachen die noch nicht des Anrufers erwähnt wurden:
        Personenzahl, Datum, Uhrzeit, Name und Telefonnummer. Stelle höchstens EINE klärende Frage.
        """;
        String aiText = llm.reply(systemPrompt, "Anrufer sagt: " + utterance);
        return playAndGather(aiText);   // <-- ersetzt den alten "return resp.toXml()"

    }



    // 3) Voicemail: Nachricht aufnehmen & (optional) transkribieren
    @PostMapping("/voicemail")
    public String voicemail() throws TwiMLException {
        Record rec = new Record.Builder()
                .maxLength(120)
                .playBeep(true)
                .transcribe(true)                 // Twilio Transkription per E-Mail/Console
                .transcribeCallback("/voice/transcript") // optional
                .finishOnKey("#")
                .build();

        VoiceResponse resp = new VoiceResponse.Builder()
                .say(new Say.Builder("Nach dem Signalton können Sie sprechen. Mit der Raute Taste beenden.")
                        .language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .record(rec)
                .say(new Say.Builder("Danke! Wir melden uns so schnell wie möglich. Auf Wiederhören!")
                        .language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .build();
        return resp.toXml();
    }

    private String playOnly(String text) throws TwiMLException {
        String url = tts.synthesizeToUrl(text, PUBLIC_BASE_URL);

        // Fallback: falls TTS nicht funktioniert
        if (url == null || url.isBlank()) {
            return new VoiceResponse.Builder()
                    .say(new Say.Builder(text)
                            .language(Say.Language.DE_DE)
                            .voice(Say.Voice.ALICE)
                            .build())
                    .hangup(new com.twilio.twiml.voice.Hangup.Builder().build())
                    .build()
                    .toXml();
        }

        VoiceResponse resp = new VoiceResponse.Builder()
                .play(new Play.Builder(url != null ? url : "").build())
                .hangup(new com.twilio.twiml.voice.Hangup.Builder().build())
                .build();
        return resp.toXml();
    }

    private String playAndGather(String text) throws TwiMLException {
        String url = tts.synthesizeToUrl(text, PUBLIC_BASE_URL);

        // Fallback, wenn TTS nicht funktioniert
        if (url == null || url.isBlank()) {
            return new VoiceResponse.Builder()
                    .say(new Say.Builder(text)
                            .language(Say.Language.DE_DE)
                            .voice(Say.Voice.ALICE)
                            .build())
                    .gather(new Gather.Builder()
                            .inputs(Gather.Input.SPEECH)
                            .language(Gather.Language.DE_DE)
                            .action("/voice/gather")
                            .method(com.twilio.http.HttpMethod.POST)
                            .timeout(8)
                            .speechTimeout("2")
                            .build())
                    .build()
                    .toXml();
        }
        // Normale TTS-Ausgabe
        return new VoiceResponse.Builder()
                .play(new Play.Builder(url).build())
                .gather(new Gather.Builder()
                        .inputs(Gather.Input.SPEECH)
                        .language(Gather.Language.DE_DE)
                        .action("/voice/gather")
                        .method(com.twilio.http.HttpMethod.POST)
                        .timeout(8)
                        .speechTimeout("2")
                        .build())
                .build()
                .toXml();

    }


    // Optional: Entscheidung zur Voicemail
    @PostMapping("/voicemail-choice")
    public String voicemailChoice(@RequestParam(value="SpeechResult", required=false) String speech) throws TwiMLException {
        String u = speech == null ? "" : speech.toLowerCase(Locale.ROOT);
        if (u.contains("ja") || u.contains("nachricht")) {
            return voicemail();
        }
        return playAndGather("Kein Problem. Wie kann ich sonst helfen?");
    }

    // Optional: Transkript-Webhook (nur Logging)
    @PostMapping("/transcript")
    public String transcript(@RequestParam(value="TranscriptionText", required=false) String text) {
        System.out.println("Voicemail-Transkript: " + text);
        return "<Response></Response>";
    }
}
