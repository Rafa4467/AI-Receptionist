package com.rafael.aireceptionistapi.controller;

import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Gather;
import com.twilio.twiml.voice.Say;
import com.twilio.twiml.voice.Record;
import com.twilio.twiml.TwiMLException;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import com.rafael.aireceptionistapi.ai.LlmService;

import java.util.Locale;

@RestController
@RequestMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
public class VoiceController {

    // 1) Einstieg: Begrüßung + Spracherkennung aktivieren
    @PostMapping("/incoming")
    public String incoming() throws TwiMLException {
        Say greet = new Say
                .Builder("Hallo! Sie sprechen mit dem digitalen Empfang. Wie kann ich helfen? " +
                "Zum Beispiel: Reservierung, Öffnungszeiten, Adresse oder mit einem Mitarbeiter verbinden.")
                .voice(Say.Voice.ALICE) // *** Stimme (****)
                .language(Say.Language.DE_DE)
                .build();

        Gather gather = new Gather.Builder()
                .inputs(Gather.Input.SPEECH)// Spracherkennung
                .language(Gather.Language.DE_DE)
                .hints("Reservierung, Tisch, heute, morgen, Uhr, Öffnungszeiten, Adresse, Speisekarte, Menü, Mitarbeiter")
                .action("/voice/gather")   // nach Erkennung hierhin posten
                .method(com.twilio.http.HttpMethod.POST)
                .timeout(5)
                .build();

        VoiceResponse resp = new VoiceResponse.Builder()
                .say(greet)
                .gather(gather)
                // Fallback falls nichts gesagt: direkt zu gather-Handler, der erneut fragt
                .say(new Say.Builder("Ich habe nichts verstanden.").language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .redirect(new com.twilio.twiml.voice.Redirect.Builder("/voice/incoming").build())
                .build();

        return resp.toXml();
    }

    private final LlmService llm;
    public VoiceController(LlmService llm) { this.llm = llm; }

    // 2) Auswertung der Spracherkennung
    @PostMapping("/gather")
    public String gathered(@RequestParam(value="SpeechResult", required=false) String speech) throws TwiMLException {
        String utterance = (speech == null ? "" : speech).toLowerCase(Locale.ROOT);

        // ganz simple Intent-Erkennung
        if (utterance.contains("reserv") || utterance.contains("tisch")) {
            return sayAndGather("Gerne! Für wie viele Personen und wann?");
        }
        if (utterance.contains("öffnungs") || utterance.contains("uhr")) {
            return say("Unsere Öffnungszeiten sind Montag bis Freitag 11 bis 22 Uhr und Samstag 12 bis 22 Uhr.");
        }
        if (utterance.contains("adress") || utterance.contains("wo") || utterance.contains("finden")) {
            return say("Unsere Adresse ist Musterstraße 12 in Wien, gleich bei der U Bahn. Ich habe Ihnen auch eine SMS mit dem Link gesendet.");
        }
        if (utterance.contains("menü") || utterance.contains("speisekarte")) {
            return say("Unsere Tageskarte: Suppe, Pasta, Veggie Bowl und Hausburger. Möchten Sie reservieren?");
        }
        if (utterance.contains("mitarbeit") || utterance.contains("chef") || utterance.contains("verbinden")) {
            // Optional: direkt verbinden (hier nur Ansage)
            return say("Einen Moment, ich verbinde Sie mit einem Mitarbeiter.");
            // Für echtes Durchstellen: <Dial> mit Zielnummer implementieren
        }


        // --- AI-Fallback kommt hier ---
        String systemPrompt = """
        Du bist der freundliche, präzise Telefon-Rezeptionist eines Restaurants in Wien.
        Sprich kurz, höflich und auf Deutsch. Bei Reservierungen frage nacheinander nach:
        Personenzahl, Datum, Uhrzeit, Name und Telefonnummer. Stelle höchstens EINE klärende Frage.
        """;
        String aiText = llm.reply(systemPrompt, "Anrufer sagt: " + utterance);
        return sayAndGather(aiText);   // <-- ersetzt den alten "return resp.toXml()"

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

    // kleine Helfer
    private String say(String text) throws TwiMLException {
        VoiceResponse resp = new VoiceResponse.Builder()
                .say(new Say.Builder(text).language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .hangup(new com.twilio.twiml.voice.Hangup.Builder().build())
                .build();
        return resp.toXml();
    }

    private String sayAndGather(String text) throws TwiMLException {
        VoiceResponse resp = new VoiceResponse.Builder()
                .say(new Say.Builder(text).language(Say.Language.DE_DE).voice(Say.Voice.ALICE).build())
                .gather(new Gather.Builder().inputs(Gather.Input.SPEECH).language(Gather.Language.DE_DE)
                        .action("/voice/gather").timeout(5).build())
                .build();
        return resp.toXml();
    }

    // Optional: Entscheidung zur Voicemail
    @PostMapping("/voicemail-choice")
    public String voicemailChoice(@RequestParam(value="SpeechResult", required=false) String speech) throws TwiMLException {
        String u = speech == null ? "" : speech.toLowerCase(Locale.ROOT);
        if (u.contains("ja") || u.contains("nachricht")) {
            return voicemail();
        }
        return sayAndGather("Kein Problem. Wie kann ich sonst helfen?");
    }

    // Optional: Transkript-Webhook (nur Logging)
    @PostMapping("/transcript")
    public String transcript(@RequestParam(value="TranscriptionText", required=false) String text) {
        System.out.println("Voicemail-Transkript: " + text);
        return "<Response></Response>";
    }
}
