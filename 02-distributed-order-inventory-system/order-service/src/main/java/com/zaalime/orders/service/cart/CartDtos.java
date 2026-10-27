package com.zaalime.orders.service.cart;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import java.math.BigDecimal;
import java.util.List;

public final class CartDtos {

    private CartDtos() {
    }

    public record SetQuantityRequest(@Min(0) @Max(1000) int quantity) {
    }

    public record CartItemView(long productId, String productName, BigDecimal unitPrice, int quantity,
                               BigDecimal lineTotal, long availableStock) {
    }

    public record CartView(List<CartItemView> items, BigDecimal total) {
    }
}
