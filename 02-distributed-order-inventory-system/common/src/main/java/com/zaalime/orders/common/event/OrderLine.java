package com.zaalime.orders.common.event;

import java.math.BigDecimal;

public record OrderLine(long productId, String productName, int quantity, BigDecimal unitPrice) {

    public BigDecimal lineTotal() {
        return unitPrice.multiply(BigDecimal.valueOf(quantity));
    }
}
