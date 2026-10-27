package com.zaalime.orders.service.cart;

import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.service.cart.CartDtos.CartItemView;
import com.zaalime.orders.service.cart.CartDtos.CartView;
import com.zaalime.orders.service.catalog.StockStore;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemRequest;
import com.zaalime.orders.service.config.AppProperties;
import com.zaalime.orders.service.web.ApiException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Carts are short-lived and per user, so they live in a Redis hash
 * (productId -> quantity) with a sliding TTL instead of in Postgres.
 */
@Service
public class CartService {

    private static final Duration CART_TTL = Duration.ofDays(7);

    private final StringRedisTemplate redis;
    private final StockStore stockStore;
    private final AppProperties.Checkout limits;

    public CartService(StringRedisTemplate redis, StockStore stockStore, AppProperties props) {
        this.redis = redis;
        this.stockStore = stockStore;
        this.limits = props.checkout();
    }

    public CartView view(long userId) {
        Map<Long, Integer> cart = rawItems(userId);
        Map<Long, Long> stock = stockStore.availableStock(cart.keySet());
        List<CartItemView> items = new ArrayList<>();
        BigDecimal total = BigDecimal.ZERO;
        for (var entry : cart.entrySet()) {
            var product = stockStore.snapshot(entry.getKey()).orElse(null);
            if (product == null) {
                continue; // product was removed since it was added - just hide it
            }
            BigDecimal lineTotal = product.price().multiply(BigDecimal.valueOf(entry.getValue()));
            items.add(new CartItemView(product.id(), product.name(), product.price(), entry.getValue(), lineTotal,
                    stock.getOrDefault(product.id(), 0L)));
            total = total.add(lineTotal);
        }
        items.sort(Comparator.comparingLong(CartItemView::productId));
        return new CartView(items, total);
    }

    public CartView setQuantity(long userId, long productId, int quantity) {
        String key = RedisKeys.cart(userId);
        if (quantity == 0) {
            redis.opsForHash().delete(key, String.valueOf(productId));
            return view(userId);
        }
        if (quantity > limits.maxQuantity()) {
            throw ApiException.badRequest("Quantity must be between 1 and " + limits.maxQuantity());
        }
        if (stockStore.snapshot(productId).isEmpty()) {
            throw ApiException.notFound("Product " + productId + " not found");
        }
        boolean isNewLine = !redis.opsForHash().hasKey(key, String.valueOf(productId));
        if (isNewLine && redis.opsForHash().size(key) >= limits.maxLines()) {
            throw ApiException.badRequest("A cart can hold at most " + limits.maxLines() + " different products");
        }
        redis.opsForHash().put(key, String.valueOf(productId), String.valueOf(quantity));
        redis.expire(key, CART_TTL);
        return view(userId);
    }

    public void clear(long userId) {
        redis.delete(RedisKeys.cart(userId));
    }

    public List<OrderItemRequest> itemsForCheckout(long userId) {
        return rawItems(userId).entrySet().stream()
                .map(e -> new OrderItemRequest(e.getKey(), e.getValue()))
                .toList();
    }

    private Map<Long, Integer> rawItems(long userId) {
        Map<Object, Object> raw = redis.opsForHash().entries(RedisKeys.cart(userId));
        Map<Long, Integer> items = new java.util.TreeMap<>();
        raw.forEach((k, v) -> items.put(Long.valueOf((String) k), Integer.valueOf((String) v)));
        return items;
    }
}
