package com.rafael.aireceptionistapi.ai;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

@Service
public class LlmService {
    private final RestClient http;
    private final String apiKey = System.getenv("OPENAI_API_KEY");

    public LlmService() {
        this.http = RestClient.builder()
                .baseUrl("https://api.openai.com/v1/chat/completions")
                .defaultHeader("Authorization", "Bearer " + (apiKey == null ? "" : apiKey))
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    public String reply(String system, String user) {
        if (apiKey == null || apiKey.isBlank()) {
            return "Ich antworte gern intelligent, sobald der AI-Schlüssel gesetzt ist.";
        }
        var body = Map.of(
                "model", "gpt-4o-mini",
                "temperature", 0.4,
                "messages", new Object[] {
                        Map.of("role", "system", "content", system),
                        Map.of("role", "user",   "content", user)
                }
        );
        var resp = http.post().body(body).retrieve().body(Map.class);
        try {
            var choices = (java.util.List<Map<String,Object>>) resp.get("choices");
            var msg = (Map<String,Object>) choices.get(0).get("message");
            return String.valueOf(msg.get("content"));
        } catch (Exception e) {
            return "Entschuldigung, das konnte ich gerade nicht beantworten.";
        }
    }
}
