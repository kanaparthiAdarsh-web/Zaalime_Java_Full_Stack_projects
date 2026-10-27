package com.zaalime.orders.worker;

import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.common.event.OrderLine;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/** The worker's view of Redis: read live stock, give back reservations of failed orders. */
@Component
public class LiveStock {

    private final StringRedisTemplate redis;
    private final RedisScript<List<Long>> releaseScript;

    public LiveStock(StringRedisTemplate redis, @Qualifier("releaseStockScript") RedisScript<List<Long>> releaseScript) {
        this.redis = redis;
        this.releaseScript = releaseScript;
    }

    public long available(long productId) {
        String v = redis.opsForValue().get(RedisKeys.stock(productId));
        return v == null ? 0 : Long.parseLong(v);
    }

    /** @return new live level per line, -1 where the key no longer exists */
    public List<Long> release(List<OrderLine> lines) {
        List<String> keys = lines.stream().map(l -> RedisKeys.stock(l.productId())).toList();
        Object[] quantities = lines.stream().map(l -> String.valueOf(l.quantity())).toArray();
        return redis.execute(releaseScript, keys, quantities);
    }

    public void clearPendingSnapshot(UUID orderId) {
        redis.delete(RedisKeys.pendingOrder(orderId));
    }
}
