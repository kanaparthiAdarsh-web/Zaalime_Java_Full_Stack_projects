package com.zaalime.rag.search;

import com.zaalime.rag.embed.Embedder;
import com.zaalime.rag.ingest.Chunk;
import com.zaalime.rag.store.ChunkStore;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Semantic (pgvector) + lexical (Lucene BM25) retrieval, merged with weighted Reciprocal Rank Fusion. */
public class HybridRetriever {

    public enum Mode { HYBRID, VECTOR, KEYWORD }

    static final int RRF_K = 60; // standard constant from the RRF paper; damps the weight of the very top ranks

    // How much the keyword list counts next to the vector list. On the eval questions BM25 mostly adds
    // noise for plain-English questions, but is the sharpest signal when the question names an identifier.
    static final double KEYWORD_WEIGHT_PROSE = 0.5;
    static final double KEYWORD_WEIGHT_IDENTIFIER = 1.5;

    // a chunk declaring the identifier asked about beats chunks that merely mention it
    static final double DEFINITION_BONUS = 2.0 / (RRF_K + 1); // as if ranked first by both retrievers

    // a long README can fill every slot on its own; cap it so the answer sees more than one file
    static final int MAX_CHUNKS_PER_FILE = 2;

    private final ChunkStore store;
    private final KeywordIndex keywords;
    private final Embedder embedder;
    private final int candidatePool;

    public HybridRetriever(ChunkStore store, KeywordIndex keywords, Embedder embedder, int candidatePool) {
        this.store = store;
        this.keywords = keywords;
        this.embedder = embedder;
        this.candidatePool = candidatePool;
    }

    public List<ScoredChunk> search(String query, int topK, Mode mode, List<String> languages, String project) {
        if (keywords.size() == 0) {
            return List.of();
        }
        List<Chunk> vectorHits = mode == Mode.KEYWORD ? List.of()
                : store.search(embedder.embedQuery(query), candidatePool, languages, project).stream()
                        .map(ChunkStore.Hit::chunk).toList();
        List<Chunk> keywordHits = mode == Mode.VECTOR ? List.of()
                : keywords.search(query, candidatePool, languages, project).stream()
                        .map(KeywordIndex.Hit::chunk).toList();

        List<String> identifiers = Identifiers.in(query);
        double keywordWeight = identifiers.isEmpty() ? KEYWORD_WEIGHT_PROSE : KEYWORD_WEIGHT_IDENTIFIER;

        List<ScoredChunk> fused = new ArrayList<>();
        for (ScoredChunk s : fuse(List.of(vectorHits, keywordHits), List.of(1.0, keywordWeight))) {
            boolean declares = identifiers.stream().anyMatch(id -> Identifiers.declares(s.chunk().text(), id));
            fused.add(declares ? new ScoredChunk(s.chunk(), s.score() + DEFINITION_BONUS, s.vectorRank(), s.keywordRank()) : s);
        }
        fused.sort(Comparator.comparingDouble(ScoredChunk::score).reversed());
        return diversify(dropOverlaps(fused)).stream().limit(topK)
                .map(s -> new ScoredChunk(s.chunk(), Math.round(s.score() * 100000) / 100000.0, s.vectorRank(), s.keywordRank()))
                .toList();
    }

    /** Weighted RRF: score = sum over lists of weight / (k + rank). */
    static List<ScoredChunk> fuse(List<List<Chunk>> rankings, List<Double> weights) {
        Map<String, Double> scores = new LinkedHashMap<>();
        Map<String, Chunk> chunks = new HashMap<>();
        Map<String, Integer[]> ranks = new HashMap<>();
        for (int list = 0; list < rankings.size(); list++) {
            List<Chunk> ranking = rankings.get(list);
            for (int rank = 1; rank <= ranking.size(); rank++) {
                Chunk c = ranking.get(rank - 1);
                scores.merge(c.id(), weights.get(list) / (RRF_K + rank), Double::sum);
                chunks.put(c.id(), c);
                ranks.computeIfAbsent(c.id(), k -> new Integer[rankings.size()])[list] = rank;
            }
        }
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed())
                .map(e -> new ScoredChunk(chunks.get(e.getKey()), e.getValue(),
                        ranks.get(e.getKey())[0], ranks.get(e.getKey()).length > 1 ? ranks.get(e.getKey())[1] : null))
                .toList();
    }

    /** Neighbouring chunks overlap by design; don't spend two of the k slots on the same lines. */
    private static List<ScoredChunk> dropOverlaps(List<ScoredChunk> results) {
        List<ScoredChunk> kept = new ArrayList<>();
        for (ScoredChunk r : results) {
            Chunk c = r.chunk();
            boolean duplicate = kept.stream().map(ScoredChunk::chunk).anyMatch(k -> k.path().equals(c.path())
                    && !"pdf".equals(c.language())
                    && overlap(k, c) > 0.5 * (c.endLine() - c.startLine() + 1));
            if (!duplicate) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static List<ScoredChunk> diversify(List<ScoredChunk> results) {
        Map<String, Integer> perFile = new HashMap<>();
        List<ScoredChunk> kept = new ArrayList<>();
        for (ScoredChunk r : results) {
            if (perFile.merge(r.chunk().path(), 1, Integer::sum) <= MAX_CHUNKS_PER_FILE) {
                kept.add(r);
            }
        }
        return kept;
    }

    private static int overlap(Chunk a, Chunk b) {
        return Math.max(0, Math.min(a.endLine(), b.endLine()) - Math.max(a.startLine(), b.startLine()) + 1);
    }
}
