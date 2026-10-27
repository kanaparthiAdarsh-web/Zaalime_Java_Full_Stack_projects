package com.zaalime.orders.service.catalog;

import com.zaalime.orders.common.RedisKeys;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Everything the hot path needs from Redis about products: live stock and a
 * small snapshot (name, price) so checkout never has to query Postgres.
 */
@Component
public class StockStore {

    public record ProductSnapshot(long id, String sku, String name, String description, BigDecimal price) {
    }

    private final StringRedisTemplate redis;

    public StockStore(StringRedisTemplate redis) {
        this.redis = redis;
    }

    public void putSnapshot(Product p) {
        Map<String, String> fields = new HashMap<>();
        fields.put("sku", p.getSku());
        fields.put("name", p.getName());
        fields.put("description", p.getDescription() == null ? "" : p.getDescription());
        fields.put("price", p.getPrice().toPlainString());
        redis.opsForHash().putAll(RedisKeys.product(p.getId()), fields);
        redis.opsForSet().add(RedisKeys.CATALOG_INDEX, String.valueOf(p.getId()));
    }

    public Optional<ProductSnapshot> snapshot(long productId) {
        Map<Object, Object> h = redis.opsForHash().entries(RedisKeys.product(productId));
        if (h.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new ProductSnapshot(productId, (String) h.get("sku"), (String) h.get("name"),
                (String) h.get("description"), new BigDecimal((String) h.get("price"))));
    }

    public List<Long> catalogIds() {
        var members = redis.opsForSet().members(RedisKeys.CATALOG_INDEX);
        if (members == null) {
            return List.of();
        }
        return members.stream().map(Long::valueOf).sorted().toList();
    }

    /** Seeds the live stock only if Redis does not have it yet - never overwrites in-flight reservations. */
    public boolean initStockIfAbsent(long productId, long quantity) {
        return Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(RedisKeys.stock(productId), String.valueOf(quantity)));
    }

    public void setStock(long productId, long quantity) {
        redis.opsForValue().set(RedisKeys.stock(productId), String.valueOf(quantity));
    }

    public long addStock(long productId, long delta) {
        Long value = redis.opsForValue().increment(RedisKeys.stock(productId), delta);
        return value == null ? 0 : value;
    }

    public Map<Long, Long> availableStock(Collection<Long> productIds) {
        List<Long> ids = new ArrayList<>(productIds);
        List<String> keys = ids.stream().map(RedisKeys::stock).toList();
        List<String> values = keys.isEmpty() ? List.of() : redis.opsForValue().multiGet(keys);
        Map<Long, Long> result = new HashMap<>();
        for (int i = 0; i < ids.size(); i++) {
            String v = values == null ? null : values.get(i);
            result.put(ids.get(i), v == null ? 0L : Long.parseLong(v));
        }
        return result;
    }

    public long availableStock(long productId) {
        String v = redis.opsForValue().get(RedisKeys.stock(productId));
        return v == null ? 0L : Long.parseLong(v);
    }
}
