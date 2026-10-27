package com.zaalime.orders.worker;

/**
 * A business reason the order cannot be committed (as opposed to an infrastructure error).
 * Thrown inside the transaction so it rolls back, and ignored by the circuit breaker.
 */
public class OrderRejectedException extends RuntimeException {

    private final boolean recordable;

    /**
     * @param recordable whether a FAILED row can be written for this order. Not the case when
     *                   the idempotency key is already taken by another order.
     */
    public OrderRejectedException(String message, boolean recordable) {
        super(message);
        this.recordable = recordable;
    }

    public boolean isRecordable() {
        return recordable;
    }
}
