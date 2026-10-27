package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.OrderStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Positive;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class CheckoutDtos {

    private CheckoutDtos() {
    }

    public record OrderItemRequest(@Positive long productId, @Min(1) int quantity) {
    }

    public record PlaceOrderRequest(@NotEmpty List<@Valid OrderItemRequest> items) {
    }

    public record CheckoutResponse(UUID orderId, OrderStatus status, BigDecimal totalAmount, String statusUrl, boolean replayed) {
    }

    public record OrderItemView(long productId, String productName, int quantity, BigDecimal unitPrice) {
    }

    public record OrderView(UUID id, OrderStatus status, BigDecimal totalAmount, String failureReason,
                            Instant createdAt, Instant updatedAt, List<OrderItemView> items) {
    }
}
