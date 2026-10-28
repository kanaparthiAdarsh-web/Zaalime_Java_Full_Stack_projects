package com.zaalime.rag.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/** Answer cache: Redis when configured (shared by all instances), otherwise an in-process LRU. */
public class AnswerCache implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(AnswerCache.class);
    private static final int MAX_LOCAL_ENTRIES = 1000;

    private record Entry(String json, long expiresAt) {
    }

    private final LettuceConnectionFactory connection;
    private final StringRedisTemplate redis;
    private final Duration ttl;
    private final Map<String, Entry> local = new LinkedHashMap<>(16, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Entry> eldest) {
            return size() > MAX_LOCAL_ENTRIES;
        }
    };

    /** @param connection null for the in-process cache */
    public AnswerCache(LettuceConnectionFactory connection, Duration ttl) {
        this.connection = connection;
        this.redis = connection == null ? null : new StringRedisTemplate(connection);
        this.ttl = ttl;
    }

    @Override
    public void close() {
        if (connection != null) {
            connection.destroy();
        }
    }

    public String backend() {
        return redis != null ? "redis" : "memory";
    }

    /** Questions that differ only in case or spacing share a key. */
    public static String key(String question, String parameters) {
        String normalized = String.join(" ", question.toLowerCase().strip().split("\\s+"));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((normalized + "\n" + parameters).getBytes(StandardCharsets.UTF_8));
            return "rag:answer:" + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<String> get(String key) {
        if (redis != null) {
            try {
                return Optional.ofNullable(redis.opsForValue().get(key));
            } catch (RuntimeException e) { // a cache outage must never break answering
                log.warn("Cache read failed: {}", e.getMessage());
                return Optional.empty();
            }
        }
        synchronized (local) {
            Entry e = local.get(key);
            if (e == null || e.expiresAt() < System.currentTimeMillis()) {
                local.remove(key);
                return Optional.empty();
            }
            return Optional.of(e.json());
        }
    }

    public void put(String key, String json) {
        if (redis != null) {
            try {
                redis.opsForValue().set(key, json, ttl);
            } catch (RuntimeException e) {
                log.warn("Cache write failed: {}", e.getMessage());
            }
            return;
        }
        synchronized (local) {
            local.put(key, new Entry(json, System.currentTimeMillis() + ttl.toMillis()));
        }
    }
}
