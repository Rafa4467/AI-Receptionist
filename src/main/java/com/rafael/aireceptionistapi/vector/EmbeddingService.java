package com.rafael.aireceptionistapi.vector;

import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Service
public class EmbeddingService {

    private final RestClient http;
    private final String apiKey = System.getenv("OPENAI_API_KEY");

    public EmbeddingService() {
        this.http = RestClient.builder()
                .baseUrl("https://api.openai.com/v1/embeddings")
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .build();
    }

    public double[] embed(String text) {
        var body = Map.of(
                "model", "text-embedding-3-small",
                "input", text
        );

        var resp = http.post().body(body).retrieve().body(Map.class);
        var data = (List<Map<String, Object>>) resp.get("data");
        var embedding = (List<Double>) data.get(0).get("embedding");

        return embedding.stream().mapToDouble(Double::doubleValue).toArray();
    }
}
