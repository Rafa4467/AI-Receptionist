package com.rafael.aireceptionistapi.knowledge;

import com.rafael.aireceptionistapi.vector.EmbeddingService;
import com.rafael.aireceptionistapi.vector.VectorStoreService;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.List;

@Service
public class KnowledgeService {

    private final RestaurantKnowledgeRepository repo;
    private final EmbeddingService embeddings;
    private final VectorStoreService vectors;

    public KnowledgeService(RestaurantKnowledgeRepository repo,
                            EmbeddingService embeddings,
                            VectorStoreService vectors) {
        this.repo = repo;
        this.embeddings = embeddings;
        this.vectors = vectors;
    }

    public List<String> retrieve(String phone, String question) {
        if (phone == null) phone = "";

        var rk = repo.findByPhoneNumber(phone).orElse(null);
        if (rk == null || rk.getRawText() == null || rk.getRawText().isBlank()) return List.of();

        // MVP: Index pro Anfrage neu (später cachen)
        vectors.clear();

        Arrays.stream(rk.getRawText().split("\\. "))
                .map(String::trim)
                .filter(s -> s.length() > 40)
                .limit(400) // Schutz: nicht unendlich embedd-en
                .forEach(chunk -> vectors.add(chunk, embeddings.embed(chunk)));

        var hits = vectors.search(embeddings.embed(question), 0.78);
        return hits.stream().map(h -> h.text).limit(6).toList();
    }
}
