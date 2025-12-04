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

