package com.zaalime.orders.service.checkout;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import com.zaalime.orders.service.config.AppProperties;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/** Lets GET /api/orders/{id} answer "PENDING" for an order the worker has not written to Postgres yet. */
@Component
public class PendingOrderStore {

    private final StringRedisTemplate redis;
    private final ObjectMapper json;
    private final AppProperties props;

    public PendingOrderStore(StringRedisTemplate redis, ObjectMapper json, AppProperties props) {
        this.redis = redis;
        this.json = json;
        this.props = props;
    }

    public void save(OrderPlacedEvent event) {
        try {
            redis.opsForValue().set(RedisKeys.pendingOrder(event.orderId()), json.writeValueAsString(event),
                    props.checkout().pendingOrderTtl());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    public Optional<OrderPlacedEvent> find(UUID orderId) {
        String raw = redis.opsForValue().get(RedisKeys.pendingOrder(orderId));
        if (raw == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(json.readValue(raw, OrderPlacedEvent.class));
        } catch (JsonProcessingException e) {
            return Optional.empty();
        }
    }

    public void delete(UUID orderId) {
        redis.delete(RedisKeys.pendingOrder(orderId));
    }
}
