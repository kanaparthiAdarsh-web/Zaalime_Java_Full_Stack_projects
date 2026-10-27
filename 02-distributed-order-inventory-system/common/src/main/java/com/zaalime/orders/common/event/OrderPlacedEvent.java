package com.zaalime.orders.common.event;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Published to order-queue once stock has been reserved in Redis. */
public record OrderPlacedEvent(
        UUID orderId,
        long userId,
        String username,
        String idempotencyKey,
        List<OrderLine> lines,
        BigDecimal totalAmount,
        Instant placedAt) {
}
