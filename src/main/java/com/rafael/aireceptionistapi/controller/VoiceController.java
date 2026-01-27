package com.rafael.aireceptionistapi.controller;

import com.twilio.twiml.TwiMLException;
import com.twilio.twiml.VoiceResponse;
import com.twilio.twiml.voice.Connect;
import com.twilio.twiml.voice.Stream;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(value = "/voice", produces = MediaType.APPLICATION_XML_VALUE)
public class VoiceController {

    /**
     * EINZIGER Einstiegspunkt für eingehende Anrufe.
     * - KEINE Begrüßung hier
     * - KEIN Say
     * - KEIN Play
     * - KEIN Gather
     *
     * Die komplette Sprache + Logik passiert im Realtime WebSocket
     * (TwilioMediaWebSocketHandler)
     */
    @PostMapping("/incoming")
    public String incoming() throws TwiMLException {

        String wssBase = System.getenv("PUBLIC_WSS_BASE");

        // Fallback statt Exception -> Twilio bekommt IMMER gültiges TwiML zurück
        if (wssBase == null || wssBase.isBlank()) {
            return new VoiceResponse.Builder()
                    .say(new com.twilio.twiml.voice.Say.Builder(
                            "Konfiguration fehlt. PUBLIC_WSS_BASE ist nicht gesetzt."
                    ).build())
                    .hangup(new com.twilio.twiml.voice.Hangup.Builder().build())
                    .build()
                    .toXml();
        }

        // kleine Normalisierung (kein doppelte Slash)
        wssBase = wssBase.replaceAll("/+$", "");
        String wsUrl = wssBase + "/twilio-media";

        return new VoiceResponse.Builder()
                .connect(new Connect.Builder()
                        .stream(new Stream.Builder()
                                .url(wsUrl)
                                .build())
                        .build())
                .build()
                .toXml();
    }
}