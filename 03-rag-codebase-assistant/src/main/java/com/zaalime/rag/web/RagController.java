package com.zaalime.rag.web;

import com.zaalime.rag.config.RagProperties;
import com.zaalime.rag.service.AskService;
import com.zaalime.rag.service.Dtos.AskRequest;
import com.zaalime.rag.service.Dtos.AskResponse;
import com.zaalime.rag.service.Dtos.IngestRequest;
import com.zaalime.rag.service.Dtos.IngestResult;
import com.zaalime.rag.service.IngestService;
import com.zaalime.rag.store.ChunkStore;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Map;

@RestController
public class RagController {

    private final AskService askService;
    private final IngestService ingestService;
    private final ChunkStore store;
    private final RagProperties props;
    private final RateLimiter limiter;

    public RagController(AskService askService, IngestService ingestService, ChunkStore store, RagProperties props) {
        this.askService = askService;
        this.ingestService = ingestService;
        this.store = store;
        this.props = props;
        this.limiter = new RateLimiter(props.rateLimitPerMinute());
    }

    @PostMapping("/ask")
    public ResponseEntity<AskResponse> ask(@Valid @RequestBody AskRequest request, HttpServletRequest http) {
        long retryAfter = limiter.tryAcquire(http.getRemoteAddr());
        if (retryAfter > 0) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfter))
                    .build();
        }
        return ResponseEntity.ok(askService.ask(request));
    }

    @PostMapping("/ingest")
    public IngestResult ingest(@Valid @RequestBody IngestRequest request,
                               @RequestHeader(name = "X-Admin-Token", required = false) String token) {
        String expected = props.adminToken();
        if (expected == null || expected.isBlank()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Ingestion over HTTP is disabled. Set ADMIN_TOKEN or use INGEST_ON_START.");
        }
        if (token == null || !MessageDigest.isEqual(token.getBytes(StandardCharsets.UTF_8),
                expected.getBytes(StandardCharsets.UTF_8))) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Missing or wrong X-Admin-Token header");
        }
        return ingestService.ingest(request.path());
    }

    @GetMapping("/stats")
    public Map<String, Object> stats() {
        Map<String, Object> stats = new LinkedHashMap<>(store.stats());
        stats.put("generator", askService.generator().name());
        stats.put("cache", askService.cacheBackend());
        return stats;
    }

    @GetMapping("/health")
    public Map<String, String> health() {
        return Map.of("status", "ok");
    }
}
