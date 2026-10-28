package com.zaalime.rag;

import com.zaalime.rag.answer.Generator;
import com.zaalime.rag.embed.Embedder;
import com.zaalime.rag.service.IngestService;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.stream.Stream;

/**
 * Real Postgres + pgvector in Docker (one container for the whole run), a scripted LLM and a
 * hashing embedder so the tests are fast and deterministic.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "rag.admin-token=secret-token",
        "rag.rate-limit-per-minute=1000",
        "rag.chunk-size=400",
        "rag.chunk-overlap=50"})
@Import(IntegrationTest.Beans.class)
public abstract class IntegrationTest {

    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(
            DockerImageName.parse("pgvector/pgvector:pg16").asCompatibleSubstituteFor("postgres"));
    static final Path REPO;

    static {
        POSTGRES.start();
        try {
            REPO = Files.createTempDirectory("rag-repo");
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("rag.ingest-root", REPO::toString);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class Beans {
        @Bean
        @Primary
        Embedder testEmbedder() {
            return new HashingEmbedder();
        }

        @Bean
        @Primary
        ScriptedLlm testGenerator() {
            return new ScriptedLlm();
        }
    }

    @Autowired
    protected IngestService ingest;

    @Autowired
    protected JdbcTemplate jdbc;

    @Autowired
    protected ScriptedLlm llm;

    /** Every test starts from the sample repo, fully indexed. */
    @BeforeEach
    void freshIndex() throws IOException {
        try (Stream<Path> files = Files.walk(REPO)) {
            files.sorted(Comparator.reverseOrder()).filter(p -> !p.equals(REPO)).forEach(p -> p.toFile().delete());
        }
        SampleRepo.create(REPO);
        jdbc.update("DELETE FROM chunks");
        jdbc.update("DELETE FROM indexed_files");
        llm.reset();
        ingest.ingest(".");
    }

    protected Path repo() {
        return REPO;
    }
}
