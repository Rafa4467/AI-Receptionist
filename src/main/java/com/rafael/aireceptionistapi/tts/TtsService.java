package com.rafael.aireceptionistapi.tts;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import javax.sound.sampled.*;
import java.io.*;
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
     * Erzeugt eine Telefon-optimierte WAV (8kHz μ-law mono) aus Text und gibt die öffentliche URL zurück.
     * Ziel: deutlich bessere "Phone voice" Qualität bei Twilio <Play>.
     */
    public String synthesizeToUrl(String text, String publicBaseUrl) {
        try {
            // OpenAI liefert eine WAV (typisch PCM, meist 24kHz). Danach konvertieren wir in 8kHz μ-law WAV.
            var body = Map.of(
                    "model", "gpt-4o-mini-tts",
                    "voice", "marin",
                    "input", text,
                    "format", "wav",
                    "speed", 1.02
            );

            // 1) WAV von OpenAI holen
            byte[] wav = http.post().body(body).retrieve().body(byte[].class);

            // 2) Dateien speichern
            String id = UUID.randomUUID().toString();
            File dir = new File("/tmp/tts");
            if (!dir.exists()) dir.mkdirs();

            File tempWav = new File(dir, id + "_src.wav");
            File finalWav = new File(dir, id + ".wav");

            try (FileOutputStream fos = new FileOutputStream(tempWav)) {
                fos.write(wav);
            }

            // 3) In Telefon-Standard umwandeln: 8kHz μ-law mono WAV
            convertToUlaw8k(tempWav, finalWav);

            // Optional: Quellfile löschen
            //noinspection ResultOfMethodCallIgnored
            tempWav.delete();

            // 4) Öffentliche URL
            String base = publicBaseUrl.endsWith("/") ?
                    publicBaseUrl.substring(0, publicBaseUrl.length() - 1) :
                    publicBaseUrl;

            return base + "/media/" + id + ".wav";

        } catch (Exception e) {
            System.out.println("TTS ERROR: " + e.getMessage());
            return null;
        }
    }

    /**
     * Konvertiert beliebige WAV (PCM, andere Sample-Rate) in 8kHz μ-law mono WAV (Telefonstandard).
     */
    private void convertToUlaw8k(File inputWav, File outputWav) throws Exception {
        try (AudioInputStream sourceStream = AudioSystem.getAudioInputStream(inputWav)) {

            AudioFormat sourceFormat = sourceStream.getFormat();

            // 1) Erst auf PCM_SIGNED 16-bit mono bringen (falls nicht schon)
            AudioFormat pcm16Mono = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    sourceFormat.getSampleRate(),
                    16,
                    1,
                    2,
                    sourceFormat.getSampleRate(),
                    false
            );

            AudioInputStream pcmStream = AudioSystem.getAudioInputStream(pcm16Mono, sourceStream);

            // 2) Resample auf 8000 Hz (immer noch PCM16 mono)
            AudioFormat pcm16Mono8k = new AudioFormat(
                    AudioFormat.Encoding.PCM_SIGNED,
                    8000f,
                    16,
                    1,
                    2,
                    8000f,
                    false
            );

            AudioInputStream pcm8kStream = AudioSystem.getAudioInputStream(pcm16Mono8k, pcmStream);

            // 3) PCM16 8k mono -> ULAW 8k mono
            AudioFormat ulaw8k = new AudioFormat(
                    AudioFormat.Encoding.ULAW,
                    8000f,
                    8,
                    1,
                    1,
                    8000f,
                    false
            );

            AudioInputStream ulawStream = AudioSystem.getAudioInputStream(ulaw8k, pcm8kStream);

            // 4) Schreiben als WAV
            AudioSystem.write(ulawStream, AudioFileFormat.Type.WAVE, outputWav);
        }
    }
}
