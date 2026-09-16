package com.engine.model;

public class Trade {
    public long matchId;
    public long buyOrderId;
    public long sellOrderId;
    public long executedPriceTicks;
    public long executedQuantity;
    public long timestamp;

    public void reset(long matchId, long buyOrderId, long sellOrderId, long executedPriceTicks, long executedQuantity, long timestamp) {
        this.matchId = matchId;
        this.buyOrderId = buyOrderId;
        this.sellOrderId = sellOrderId;
        this.executedPriceTicks = executedPriceTicks;
        this.executedQuantity = executedQuantity;
        this.timestamp = timestamp;
    }
}