package com.zaalime.orders.service.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.service.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;

/**
 * Remembers what happened to each (user, Idempotency-Key) pair so a retried
 * request gets the original answer instead of creating a second order.
 */
@Component
public class IdempotencyStore {

    public enum State { IN_PROGRESS, ACCEPTED, REJECTED }

    public record Entry(String requestHash, State state, UUID orderId, String detail) {
    }

    // short lease while a request is in flight; if the instance dies mid-request
    // the key frees itself instead of blocking the client for a whole day
    private static final Duration IN_PROGRESS_LEASE = Duration.ofSeconds(60);

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final Duration ttl;

    public IdempotencyStore(StringRedisTemplate redis, ObjectMapper json, AppProperties props) {
        this.redis = redis;
        this.json = json;
        this.ttl = props.checkout().idempotencyTtl();
    }

    /**
     * Claims the key. Returns empty if we got it (caller proceeds), otherwise
     * the entry already stored for it.
     */
    public Optional<Entry> tryBegin(long userId, String key, String requestHash, UUID orderId) {
        String redisKey = RedisKeys.idempotency(userId, key);
        Entry fresh = new Entry(requestHash, State.IN_PROGRESS, orderId, null);
        Boolean claimed = redis.opsForValue().setIfAbsent(redisKey, write(fresh), IN_PROGRESS_LEASE);
        if (Boolean.TRUE.equals(claimed)) {
            return Optional.empty();
        }
        String existing = redis.opsForValue().get(redisKey);
        if (existing == null) {
            // expired between the two calls - just try once more
            return tryBegin(userId, key, requestHash, orderId);
        }
        return Optional.of(read(existing));
    }

    public Optional<Entry> find(long userId, String key) {
        String raw = redis.opsForValue().get(RedisKeys.idempotency(userId, key));
        return raw == null ? Optional.empty() : Optional.of(read(raw));
    }

    public void markAccepted(long userId, String key, String requestHash, UUID orderId) {
        redis.opsForValue().set(RedisKeys.idempotency(userId, key),
                write(new Entry(requestHash, State.ACCEPTED, orderId, null)), ttl);
    }

    public void markRejected(long userId, String key, String requestHash, String detail) {
        redis.opsForValue().set(RedisKeys.idempotency(userId, key),
                write(new Entry(requestHash, State.REJECTED, null, detail)), ttl);
    }

    /** Used when the failure was on our side (broker down etc.) so the client may retry with the same key. */
    public void forget(long userId, String key) {
        redis.delete(RedisKeys.idempotency(userId, key));
    }

    private String write(Entry entry) {
        try {
            return json.writeValueAsString(entry);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    private Entry read(String raw) {
        try {
            return json.readValue(raw, Entry.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Corrupt idempotency entry", e);
        }
    }
}
