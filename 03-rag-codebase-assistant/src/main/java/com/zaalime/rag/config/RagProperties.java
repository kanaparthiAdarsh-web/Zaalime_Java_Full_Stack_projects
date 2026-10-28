package com.zaalime.rag.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import java.nio.file.Path;
import java.time.Duration;

/**
 * @param ingestRoot     only paths below this directory can be indexed
 * @param ingestOnStart  comma separated paths to index in the background at startup ("." = everything)
 * @param candidatePool  candidates taken from each retriever before fusion
 */
@ConfigurationProperties(prefix = "rag")
public record RagProperties(
        @DefaultValue("..") Path ingestRoot,
        String ingestOnStart,
        @DefaultValue("1500") int chunkSize,
        @DefaultValue("200") int chunkOverlap,
        @DefaultValue("1000000") long maxFileBytes,
        @DefaultValue("4") int defaultTopK,
        @DefaultValue("20") int candidatePool,
        @DefaultValue Llm llm,
        @DefaultValue Cache cache,
        @DefaultValue("30") int rateLimitPerMinute,
        String adminToken) {

    /**
     * @param provider auto picks Claude if an API key is set, then Ollama if a URL is set, otherwise
     *                 extractive answers (the matching passages, no generation)
     */
    public record Llm(
            @DefaultValue("auto") String provider,
            String anthropicApiKey,
            @DefaultValue("claude-opus-5-5") String claudeModel,
            @DefaultValue("medium") String claudeEffort,
            @DefaultValue("16000") long maxAnswerTokens,
            String ollamaUrl,
            @DefaultValue("llama3.1") String ollamaModel) {
    }

    /** @param redisUrl when empty an in-process LRU is used */
    public record Cache(String redisUrl, @DefaultValue("1h") Duration ttl) {
    }
}
