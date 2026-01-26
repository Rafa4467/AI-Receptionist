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
        if (wssBase == null || wssBase.isBlank()) {
            throw new IllegalStateException("PUBLIC_WSS_BASE is not set");
        }

        VoiceResponse response = new VoiceResponse.Builder()
                .connect(new Connect.Builder()
                        .stream(new Stream.Builder()
                                .url(wssBase + "/twilio-media")
                                .build()
                        )
                        .build()
                )
                .build();

        return response.toXml();
    }
}