package com.zaalime.orders.common;

/**
 * Names of every exchange / queue in the system, kept in one place so the
 * API and the worker can never drift apart.
 */
public final class Messaging {

    public static final String ORDER_EXCHANGE = "orders.exchange";
    public static final String ORDER_QUEUE = "order-queue";
    public static final String ORDER_ROUTING_KEY = "order.placed";

    public static final String DEAD_LETTER_EXCHANGE = "orders.dlx";
    public static final String DEAD_LETTER_QUEUE = "order-queue.dlq";

    // fanout: every API instance gets a copy and pushes it to its websocket clients
    public static final String EVENTS_EXCHANGE = "order-events.fanout";

    private Messaging() {
    }
}
