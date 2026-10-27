package com.zaalime.orders.common;

public enum OrderStatus {
    PENDING,
    PROCESSING,
    COMPLETED,
    FAILED;

    public boolean isFinal() {
        return this == COMPLETED || this == FAILED;
    }
}
