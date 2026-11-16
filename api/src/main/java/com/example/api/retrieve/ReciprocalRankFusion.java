package com.example.api.retrieve;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;


final class ReciprocalRankFusion {

    static final int K = 60;

    private ReciprocalRankFusion() {
    }


    static List<RetrievedChunk> fuse(List<List<RetrievedChunk>> rankings, int limit) {
        Map<Long, Fused> byChunk = new LinkedHashMap<>();
        for (List<RetrievedChunk> ranking : rankings) {
            for (int i = 0; i < ranking.size(); i++) {
                RetrievedChunk chunk = ranking.get(i);
                int rank = i + 1;
                byChunk.computeIfAbsent(chunk.id(), id -> new Fused(chunk)).add(rank);
            }
        }
        List<Fused> fused = new ArrayList<>(byChunk.values());
        fused.sort(Comparator.comparingDouble(Fused::score).reversed()
                .thenComparingInt(Fused::bestRank)
                .thenComparingLong(f -> f.chunk.id()));
        return fused.stream()
                .limit(limit)
                .map(f -> new RetrievedChunk(f.chunk.id(), f.chunk.documentId(), f.chunk.fileName(),
                        f.chunk.headingPath(), f.chunk.content(), (float) f.score()))
                .toList();
    }

    private static final class Fused {
        private final RetrievedChunk chunk;
        private double score;
        private int bestRank = Integer.MAX_VALUE;

        Fused(RetrievedChunk chunk) {
            this.chunk = chunk;
        }

        void add(int rank) {
            score += 1.0 / (K + rank);
            bestRank = Math.min(bestRank, rank);
        }

        double score() {
            return score;
        }

        int bestRank() {
            return bestRank;
        }
    }
}
