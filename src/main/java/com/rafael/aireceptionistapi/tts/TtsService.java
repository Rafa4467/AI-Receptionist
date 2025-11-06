package com.rafael.aireceptionistapi.tts;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.util.Map;
import java.util.UUID;

@Service
public class TtsService {
    private final String apiKey = System.getenv("OPENAI_API_KEY");
    private final RestClient http = RestClient.builder()
            .baseUrl("https://api.openai.com/v1/audio/speech")
            .defaultHeader("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
            .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
            .build();

    /**
     * Erzeugt eine MP3 aus Text, speichert sie als Datei und gibt die öffentliche URL zurück,
     * z. B. https://<dein-railway>.app/media/<id>.mp3
     */
    public String synthesizeToUrl(String text, String publicBaseUrl) {
        if (apiKey == null || apiKey.isBlank()) {
            // Fallback: kein Key gesetzt
            return null;
        }
        try {
            var body = Map.of(
                    "model", "gpt-4o-mini-tts",  // natürlichere TTS
                    "voice", "alloy",            // gut verständliche Stimme
                    "format", "mp3",             // MP3 ausgeben
                    "input", text
            );

            // 1) MP3-Bytes von OpenAI holen
            byte[] mp3 = http.post().body(body).retrieve().body(byte[].class);

            // 2) Datei schreiben (tmp, pro Deployment flüchtig – reicht für laufende Calls)
            String id = UUID.randomUUID().toString();
            File dir = new File("/tmp/tts");
            if (!dir.exists()) dir.mkdirs();
            File out = new File(dir, id + ".mp3");
            try (var fos = new FileOutputStream(out)) {
                fos.write(mp3);
            }

            // 3) Öffentliche URL für Twilio <Play>
            String base = publicBaseUrl.endsWith("/") ? publicBaseUrl.substring(0, publicBaseUrl.length()-1) : publicBaseUrl;
            return base + "/media/" + id + ".mp3";
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }
}
