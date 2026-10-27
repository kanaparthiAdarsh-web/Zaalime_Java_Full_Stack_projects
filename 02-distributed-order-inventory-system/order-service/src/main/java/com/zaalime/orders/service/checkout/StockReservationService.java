package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemRequest;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Wraps the Lua scripts. Redis runs a script atomically, so "check every line,
 * then decrement every line" can never interleave with another checkout -
 * that is what makes overselling impossible without any locks.
 */
@Service
public class StockReservationService {

    public sealed interface Result permits Reserved, OutOfStock, UnknownProduct {
    }

    /** remaining.get(i) is the stock left for lines.get(i) after this reservation. */
    public record Reserved(List<Long> remaining) implements Result {
    }

    public record OutOfStock(OrderItemRequest line) implements Result {
    }

    public record UnknownProduct(OrderItemRequest line) implements Result {
    }

    private final StringRedisTemplate redis;
    private final RedisScript<List<Long>> reserveScript;
    private final RedisScript<List<Long>> releaseScript;

    public StockReservationService(StringRedisTemplate redis,
                                   @Qualifier("reserveStockScript") RedisScript<List<Long>> reserveScript,
                                   @Qualifier("releaseStockScript") RedisScript<List<Long>> releaseScript) {
        this.redis = redis;
        this.reserveScript = reserveScript;
        this.releaseScript = releaseScript;
    }

    public Result reserve(List<OrderItemRequest> lines) {
        List<Long> reply = redis.execute(reserveScript, keys(lines), quantities(lines));
        if (reply == null || reply.isEmpty()) {
            throw new IllegalStateException("Empty reply from reserve script");
        }
        long code = reply.get(0);
        if (code == 1) {
            return new Reserved(reply.subList(1, reply.size()));
        }
        OrderItemRequest failed = lines.get((int) (reply.get(1) - 1)); // Lua is 1-based
        return code == 0 ? new OutOfStock(failed) : new UnknownProduct(failed);
    }

    public List<Long> release(List<OrderItemRequest> lines) {
        return redis.execute(releaseScript, keys(lines), quantities(lines));
    }

    private static List<String> keys(List<OrderItemRequest> lines) {
        return lines.stream().map(l -> RedisKeys.stock(l.productId())).toList();
    }

    private static Object[] quantities(List<OrderItemRequest> lines) {
        return lines.stream().map(l -> String.valueOf(l.quantity())).toArray();
    }
}
