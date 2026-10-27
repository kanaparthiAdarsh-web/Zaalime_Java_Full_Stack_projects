package com.engine.disruptor;

import com.engine.model.OrderType;

public class OrderEvent {
    public long orderId;
    public OrderType orderType;
    public long priceTicks;
    public long quantity;
    public long timestamp;

    public void set(long orderId, OrderType orderType, long priceTicks, long quantity, long timestamp) {
        this.orderId = orderId;
        this.orderType = orderType;
        this.priceTicks = priceTicks;
        this.quantity = quantity;
        this.timestamp = timestamp;
    }
}