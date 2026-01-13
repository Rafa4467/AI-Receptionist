package com.rafael.aireceptionistapi.controller;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;

@RestController
@RequestMapping("/media")
public class MediaController {

    /**
     * Telefon-optimierte Audioausgabe für Twilio:
     * WAV, 8 kHz, μ-law, mono
     */
    @GetMapping("/{id}.wav")
    public ResponseEntity<FileSystemResource> serveWav(@PathVariable String id) {
        File file = new File("/tmp/tts/" + id + ".wav");

        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + ".wav")
                .contentType(MediaType.valueOf("audio/wav"))
                .body(resource);
    }

    /**
     * OPTIONAL: Fallback für alte MP3-Links (kannst du später löschen)
     */
    @GetMapping("/{id}.mp3")
    public ResponseEntity<FileSystemResource> serveMp3(@PathVariable String id) {
        File file = new File("/tmp/tts/" + id + ".mp3");

        if (!file.exists()) {
            return ResponseEntity.notFound().build();
        }

        FileSystemResource resource = new FileSystemResource(file);

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + id + ".mp3")
                .contentType(MediaType.valueOf("audio/mpeg"))
                .body(resource);
    }
}
