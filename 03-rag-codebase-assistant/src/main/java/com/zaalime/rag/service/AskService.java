package com.zaalime.rag.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaalime.rag.answer.Citations;
import com.zaalime.rag.answer.ExtractiveGenerator;
import com.zaalime.rag.answer.GenerationException;
import com.zaalime.rag.answer.Generator;
import com.zaalime.rag.search.HybridRetriever;
import com.zaalime.rag.search.ScoredChunk;
import com.zaalime.rag.service.Dtos.AskRequest;
import com.zaalime.rag.service.Dtos.AskResponse;
import com.zaalime.rag.service.Dtos.Source;
import com.zaalime.rag.store.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class AskService {

    private static final Logger log = LoggerFactory.getLogger(AskService.class);

    static final String NO_CONTEXT_ANSWER = "I couldn't find anything in the indexed files that matches this question. "
            + "Try rephrasing it, removing filters, or ingesting the relevant folder first.";

    private final HybridRetriever retriever;
    private final Generator generator;
    private final Generator fallback = new ExtractiveGenerator();
    private final AnswerCache cache;
    private final ChunkStore store;
    private final ObjectMapper json;
    private final int defaultTopK;

    public AskService(HybridRetriever retriever, Generator generator, AnswerCache cache, ChunkStore store,
                      ObjectMapper json, int defaultTopK) {
        this.retriever = retriever;
        this.generator = generator;
        this.cache = cache;
        this.store = store;
        this.json = json;
        this.defaultTopK = defaultTopK;
    }

    public Generator generator() {
        return generator;
    }

    public String cacheBackend() {
        return cache.backend();
    }

    public AskResponse ask(AskRequest req) {
        long t0 = System.nanoTime();
        int topK = req.topK() != null ? req.topK() : defaultTopK;
        HybridRetriever.Mode mode = parseMode(req.mode());
        List<String> languages = req.languages() == null || req.languages().isEmpty() ? null
                : req.languages().stream().map(l -> l.toLowerCase(Locale.ROOT)).sorted().toList();
        String project = req.project() == null || req.project().isBlank() ? null : req.project();

        // the index version is part of the key: any re-ingest that changes content invalidates old answers
        String key = AnswerCache.key(req.question(),
                "k=" + topK + "|l=" + languages + "|p=" + project + "|m=" + mode + "|v=" + store.version()
                        + "|g=" + generator.name());
        var cached = cache.get(key).flatMap(this::read);
        if (cached.isPresent()) {
            return cached.get().withCache(true, Map.of("total", ms(t0)));
        }

        List<ScoredChunk> results = retriever.search(req.question(), topK, mode, languages, project);
        double retrieval = ms(t0);
        if (results.isEmpty()) {
            return new AskResponse(NO_CONTEXT_ANSWER, List.of(), "none", false, null,
                    Map.of("retrieval", retrieval, "total", ms(t0)));
        }

        long t1 = System.nanoTime();
        Generator used = generator;
        String warning = null;
        String answer;
        try {
            answer = generator.generate(req.question(), results);
        } catch (GenerationException e) {
            // degrade instead of failing: the retrieved passages are still a useful answer
            log.warn("Generation failed ({}), falling back to extractive answer", e.getMessage());
            warning = e.getMessage() + ". Showing the matching passages instead.";
            used = fallback;
            answer = fallback.generate(req.question(), results);
        }
        double generation = ms(t1);

        var checked = Citations.check(answer, results.size());
        List<Source> sources = new ArrayList<>();
        for (int i = 0; i < results.size(); i++) {
            var r = results.get(i);
            var c = r.chunk();
            sources.add(new Source(i + 1, c.path(), c.location(), c.language(), c.startLine(), c.endLine(),
                    r.score(), c.text(), checked.cited().contains(i + 1)));
        }
        Map<String, Double> timings = new LinkedHashMap<>();
        timings.put("retrieval", retrieval);
        timings.put("generation", generation);
        timings.put("total", ms(t0));
        AskResponse response = new AskResponse(checked.answer(), sources, used.name(), false, warning, timings);
        if (warning == null) {
            write(response).ifPresent(v -> cache.put(key, v));
        }
        return response;
    }

    static HybridRetriever.Mode parseMode(String mode) {
        if (mode == null || mode.isBlank()) {
            return HybridRetriever.Mode.HYBRID;
        }
        try {
            return HybridRetriever.Mode.valueOf(mode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("mode must be hybrid, vector or keyword");
        }
    }

    private java.util.Optional<AskResponse> read(String raw) {
        try {
            return java.util.Optional.of(json.readValue(raw, AskResponse.class));
        } catch (JsonProcessingException e) {
            return java.util.Optional.empty();
        }
    }

    private java.util.Optional<String> write(AskResponse r) {
        try {
            return java.util.Optional.of(json.writeValueAsString(r));
        } catch (JsonProcessingException e) {
            return java.util.Optional.empty();
        }
    }

    private static double ms(long since) {
        return Math.round((System.nanoTime() - since) / 1e5) / 10.0;
    }
}
