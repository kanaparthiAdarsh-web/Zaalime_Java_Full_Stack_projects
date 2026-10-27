package com.engine.book;

public class PriceLevel {
    public long priceTicks;
    public int headIndex = -1;
    public int tailIndex = -1;
    public long totalVolume = 0;

    public PriceLevel(long priceTicks) {
        this.priceTicks = priceTicks;
    }

    public void reset(long priceTicks) {
        this.priceTicks = priceTicks;
        this.headIndex = -1;
        this.tailIndex = -1;
        this.totalVolume = 0;
    }
}