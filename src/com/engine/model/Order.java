package com.engine.model;

public class Order {
    public long orderId;
    public OrderType orderType;
    public long priceTicks;
    public long quantity;
    public long remainingQty;
    public long timestamp;
    
    public int nextIndex = -1;
    public int prevIndex = -1;

    public void reset(long orderId, OrderType orderType, long priceTicks, long quantity, long timestamp) {
        this.orderId = orderId;
        this.orderType = orderType;
        this.priceTicks = priceTicks;
        this.quantity = quantity;
        this.remainingQty = quantity;
        this.timestamp = timestamp;
        this.nextIndex = -1;
        this.prevIndex = -1;
    }
}