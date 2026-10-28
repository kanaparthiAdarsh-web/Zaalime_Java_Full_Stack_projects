package com.zaalime.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaalime.rag.search.HybridRetriever;
import com.zaalime.rag.search.HybridRetriever.Mode;
import com.zaalime.rag.search.ScoredChunk;
import com.zaalime.rag.service.IngestService;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Retrieval quality on eval/questions.jsonl: does the right file show up in the top k, and how high?
 * Uses the real embedding model and indexes projects 01 and 02 of this repository.
 *
 *   mvn test -Peval
 *   mvn test -Peval -Deval.noisy=true   (also index project 03: more mentions, same answers)
 */
@Tag("eval")
@SpringBootTest(properties = {"rag.ingest-root=..", "rag.llm.provider=none"})
class RetrievalEval {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static final int K = 4;

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired
    IngestService ingest;

    @Autowired
    HybridRetriever retriever;

    record Question(String q, List<String> expect, String kind) {
    }

    @Test
    void reportHitRateAndMrr() throws Exception {
        List<String> projects = new ArrayList<>(List.of("01-HFT-matching-engine", "02-distributed-order-inventory-system"));
        // -Deval.noisy=true also indexes this project, whose tests and UI mention many of the same identifiers
        if (Boolean.getBoolean("eval.noisy")) {
            projects.add("03-rag-codebase-assistant");
        }
        for (String project : projects) {
            System.out.println("ingest " + project + ": " + ingest.ingest(project));
        }
        ObjectMapper json = new ObjectMapper();
        List<Question> questions = new ArrayList<>();
        for (String line : Files.readAllLines(Path.of("eval/questions.jsonl"))) {
            if (!line.isBlank()) {
                JsonNode n = json.readTree(line);
                List<String> expect = new ArrayList<>();
                n.get("expect").forEach(e -> expect.add(e.asText()));
                questions.add(new Question(n.get("q").asText(), expect, n.get("kind").asText()));
            }
        }

        System.out.printf("%n%d questions, k=%d%n%-9s %7s %6s   %-14s %-14s%n", questions.size(), K, "mode", "hit@k", "MRR",
                "concept hit/MRR", "identifier hit/MRR");
        Map<Mode, Double> hitRates = new TreeMap<>();
        for (Mode mode : Mode.values()) {
            int hits = 0;
            double rr = 0;
            Map<String, int[]> byKind = new TreeMap<>();
            Map<String, Double> rrByKind = new TreeMap<>();
            for (Question q : questions) {
                List<ScoredChunk> results = retriever.search(q.q(), K, mode, null, null);
                int rank = 0;
                for (int i = 0; i < results.size() && rank == 0; i++) {
                    String path = results.get(i).chunk().path();
                    if (q.expect().stream().anyMatch(path::endsWith)) {
                        rank = i + 1;
                    }
                }
                int[] kind = byKind.computeIfAbsent(q.kind(), k -> new int[2]);
                kind[1]++;
                if (rank > 0) {
                    hits++;
                    rr += 1.0 / rank;
                    kind[0]++;
                    rrByKind.merge(q.kind(), 1.0 / rank, Double::sum);
                } else {
                    System.out.printf("  miss [%s] %s -> %s%n", mode, q.q(),
                            results.stream().map(r -> r.chunk().path().substring(r.chunk().path().lastIndexOf('/') + 1)).toList());
                }
            }
            int[] concept = byKind.getOrDefault("concept", new int[2]);
            int[] ident = byKind.getOrDefault("identifier", new int[2]);
            System.out.printf("%-9s %6.0f%% %6.2f   %-14s %-14s%n", mode.name().toLowerCase(), 100.0 * hits / questions.size(),
                    rr / questions.size(),
                    String.format("%d/%d  %.2f", concept[0], concept[1], rrByKind.getOrDefault("concept", 0.0) / Math.max(1, concept[1])),
                    String.format("%d/%d  %.2f", ident[0], ident[1], rrByKind.getOrDefault("identifier", 0.0) / Math.max(1, ident[1])));
            hitRates.put(mode, (double) hits / questions.size());
        }
        assertThat(hitRates.get(Mode.HYBRID)).isGreaterThanOrEqualTo(0.85);
    }
}
