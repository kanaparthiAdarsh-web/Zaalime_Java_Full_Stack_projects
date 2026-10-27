package com.zaalime.orders.common;

import java.util.UUID;

public final class RedisKeys {

    private RedisKeys() {
    }

    /**
     * Live, sellable stock for a product. Decremented atomically by the checkout Lua script.
     * The {stock} hash tag keeps every stock key in one slot, so the multi-key script
     * keeps working if Redis is ever moved to cluster mode.
     */
    public static String stock(long productId) {
        return "{stock}:" + productId;
    }

    /** Hash with name + price, so checkout never has to touch Postgres. */
    public static String product(long productId) {
        return "product:" + productId;
    }

    /** Set of all product ids - lets the catalog be served from Redis if Postgres is down. */
    public static final String CATALOG_INDEX = "catalog:ids";

    public static String cart(long userId) {
        return "cart:" + userId;
    }

    public static String idempotency(long userId, String key) {
        return "idem:" + userId + ":" + key;
    }

    /** Short-lived snapshot of an order that has been accepted but not yet persisted by the worker. */
    public static String pendingOrder(UUID orderId) {
        return "order:" + orderId;
    }
}
