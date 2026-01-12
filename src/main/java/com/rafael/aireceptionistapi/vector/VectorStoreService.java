package com.rafael.aireceptionistapi.vector;

import org.apache.commons.math3.linear.ArrayRealVector;
import org.apache.commons.math3.linear.RealVector;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

@Service
public class VectorStoreService {

    public static class VectorEntry {
        public final String text;
        public final RealVector vector;

        public VectorEntry(String text, double[] embedding) {
            this.text = text;
            this.vector = new ArrayRealVector(embedding);
        }
    }

    private final List<VectorEntry> store = new ArrayList<>();

    public void clear() {
        store.clear();
    }

    public void add(String text, double[] embedding) {
        store.add(new VectorEntry(text, embedding));
    }

    public List<VectorEntry> search(double[] queryEmbedding, double minScore) {
        RealVector q = new ArrayRealVector(queryEmbedding);

        return store.stream()
                .filter(e -> cosine(q, e.vector) >= minScore)
                .toList();
    }

    private double cosine(RealVector a, RealVector b) {
        return a.dotProduct(b) / (a.getNorm() * b.getNorm());
    }
}
