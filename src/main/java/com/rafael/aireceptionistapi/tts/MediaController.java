package com.rafael.aireceptionistapi.tts;

import org.springframework.core.io.FileSystemResource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.File;
import java.util.concurrent.TimeUnit;

@RestController
public class MediaController {

    // Liefert /media/<id>.mp3 aus /tmp/tts/<id>.mp3
    @GetMapping(value = "/media/{id}.mp3", produces = "audio/mpeg")
    public ResponseEntity<FileSystemResource> serve(@PathVariable String id) {
        File f = new File("/tmp/tts/" + id + ".mp3");
        if (!f.exists()) {
            return ResponseEntity.notFound().build();
        }
        var res = new FileSystemResource(f);
        return ResponseEntity.ok()
                .contentType(MediaType.valueOf("audio/mpeg"))
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=" + f.getName())
                .cacheControl(CacheControl.maxAge(5, TimeUnit.MINUTES).cachePublic())
                .body(res);
    }
}
