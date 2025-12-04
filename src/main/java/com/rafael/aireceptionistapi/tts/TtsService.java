package com.rafael.aireceptionistapi.tts;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.FileOutputStream;
import java.util.Map;
import java.util.UUID;

@Service
public class TtsService {
    private final String apiKey = System.getenv("OPENAI_API_KEY");

    private final RestClient http = RestClient.builder()
            .baseUrl("https://api.openai.com/v1/audio/speech")
            .defaultHeader("Authorization", "Bearer " + apiKey)
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();

    /**
     * Erzeugt eine MP3 aus Text und gibt die öffentliche URL zurück.
     */
    public String synthesizeToUrl(String text, String publicBaseUrl) {
        try {
            var body = Map.of(
                    "model", "gpt-4o-realtime-audio-preview",
                    "voice", "verse",              // neue Stimme
                    "input", text,
                    "format", "mp3",
                    "speed", 1.02                  // Telefon-verständlicher
            );

            // 1) MP3 von OpenAI holen
            byte[] mp3 = http.post().body(body).retrieve().body(byte[].class);

            // 2) Datei speichern
            String id = UUID.randomUUID().toString();
            File dir = new File("/tmp/tts");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, id + ".mp3");

            try (FileOutputStream fos = new FileOutputStream(out)) {
                fos.write(mp3);
            }

            // 3) Öffentliche URL
            String base = publicBaseUrl.endsWith("/") ?
                    publicBaseUrl.substring(0, publicBaseUrl.length() - 1) :
                    publicBaseUrl;

            return base + "/media/" + id + ".mp3";

        } catch (Exception e) {
            System.out.println("TTS ERROR: " + e.getMessage());
            return null;
        }
    }
}
