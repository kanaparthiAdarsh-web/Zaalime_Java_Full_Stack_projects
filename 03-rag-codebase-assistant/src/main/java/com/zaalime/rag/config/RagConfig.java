package com.zaalime.rag.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaalime.rag.answer.ClaudeGenerator;
import com.zaalime.rag.answer.ExtractiveGenerator;
import com.zaalime.rag.answer.Generator;
import com.zaalime.rag.answer.OllamaGenerator;
import com.zaalime.rag.embed.BgeEmbedder;
import com.zaalime.rag.embed.Embedder;
import com.zaalime.rag.ingest.CodeChunker;
import com.zaalime.rag.search.HybridRetriever;
import com.zaalime.rag.search.KeywordIndex;
import com.zaalime.rag.service.AnswerCache;
import com.zaalime.rag.service.AskService;
import com.zaalime.rag.service.IngestService;
import com.zaalime.rag.store.ChunkStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;

@Configuration
public class RagConfig {

    private static final Logger log = LoggerFactory.getLogger(RagConfig.class);

    @Bean
    @ConditionalOnMissingBean
    Embedder embedder() {
        return new BgeEmbedder();
    }

    @Bean
    @ConditionalOnMissingBean
    Generator generator(RagProperties props) {
        RagProperties.Llm llm = props.llm();
        String provider = llm.provider().toLowerCase(Locale.ROOT);
        if (provider.equals("auto")) {
            provider = notBlank(llm.anthropicApiKey()) ? "claude" : notBlank(llm.ollamaUrl()) ? "ollama" : "none";
        }
        return switch (provider) {
            case "claude" -> {
                if (!notBlank(llm.anthropicApiKey())) {
                    throw new IllegalStateException("rag.llm.provider=claude needs ANTHROPIC_API_KEY");
                }
                yield new ClaudeGenerator(llm.anthropicApiKey(), llm.claudeModel(), llm.claudeEffort(), llm.maxAnswerTokens());
            }
            case "ollama" -> {
                if (!notBlank(llm.ollamaUrl())) {
                    throw new IllegalStateException("rag.llm.provider=ollama needs OLLAMA_URL");
                }
                yield new OllamaGenerator(llm.ollamaUrl(), llm.ollamaModel());
            }
            default -> new ExtractiveGenerator();
        };
    }

    @Bean
    KeywordIndex keywordIndex() {
        return new KeywordIndex();
    }

    @Bean
    IngestService ingestService(RagProperties props, Embedder embedder, ChunkStore store, KeywordIndex keywords) {
        return new IngestService(props.ingestRoot(), props.maxFileBytes(),
                new CodeChunker(props.chunkSize(), props.chunkOverlap()), embedder, store, keywords);
    }

    @Bean
    HybridRetriever hybridRetriever(RagProperties props, ChunkStore store, KeywordIndex keywords, Embedder embedder) {
        return new HybridRetriever(store, keywords, embedder, props.candidatePool());
    }

    @Bean(destroyMethod = "close")
    AnswerCache answerCache(RagProperties props) {
        String url = props.cache().redisUrl();
        if (!notBlank(url)) {
            return new AnswerCache(null, props.cache().ttl());
        }
        URI uri = URI.create(url);
        var factory = new LettuceConnectionFactory(new RedisStandaloneConfiguration(
                uri.getHost(), uri.getPort() > 0 ? uri.getPort() : 6379));
        factory.afterPropertiesSet();
        factory.start();
        return new AnswerCache(factory, props.cache().ttl());
    }

    @Bean
    AskService askService(RagProperties props, HybridRetriever retriever, Generator generator, AnswerCache cache,
                          ChunkStore store, ObjectMapper json) {
        log.info("Answers by {}, cache: {}", generator.name(), cache.backend());
        return new AskService(retriever, generator, cache, store, json, props.defaultTopK());
    }

    /** Optional initial ingest, in the background so the server is up straight away. */
    @Bean
    ApplicationRunner initialIngest(RagProperties props, IngestService ingest) {
        return args -> {
            if (!notBlank(props.ingestOnStart())) {
                return;
            }
            Thread.ofPlatform().name("initial-ingest").daemon().start(() ->
                    Arrays.stream(props.ingestOnStart().split(",")).map(String::strip).filter(s -> !s.isEmpty())
                            .forEach(path -> {
                                try {
                                    log.info("Initial ingest of '{}': {}", path, ingest.ingest(path));
                                } catch (RuntimeException e) {
                                    log.error("Initial ingest of '{}' failed", path, e);
                                }
                            }));
        };
    }

    private static boolean notBlank(String s) {
        return s != null && !s.isBlank();
    }
}
