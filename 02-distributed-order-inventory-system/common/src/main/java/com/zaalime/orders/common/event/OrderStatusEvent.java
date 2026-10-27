package com.zaalime.orders.common.event;

import com.zaalime.orders.common.OrderStatus;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderStatusEvent(
        UUID orderId,
        String username,
        OrderStatus status,
        BigDecimal totalAmount,
        String message,
        Instant timestamp) {
}
