package com.engine.book;

import com.engine.model.Order;
import com.engine.model.OrderType;

import java.util.TreeMap;

public class OrderBook {
    private final TreeMap<Long, PriceLevel> bids = new TreeMap<>((a, b) -> Long.compare(b, a));
    private final TreeMap<Long, PriceLevel> asks = new TreeMap<>();
    
    private final Order[] orderPool;
    private final int[] freeIndices;
    private int freeIndexTop;

    public OrderBook(int maxOrders) {
        orderPool = new Order[maxOrders];
        freeIndices = new int[maxOrders];
        for (int i = 0; i < maxOrders; i++) {
            orderPool[i] = new Order();
            freeIndices[i] = maxOrders - 1 - i;
        }
        freeIndexTop = maxOrders - 1;
    }

    public Order acquireOrder(long orderId, OrderType orderType, long priceTicks, long quantity, long timestamp) {
        if (freeIndexTop < 0) {
            throw new IllegalStateException("Order pool exhausted!");
        }
        int index = freeIndices[freeIndexTop--];
        Order order = orderPool[index];
        order.reset(orderId, orderType, priceTicks, quantity, timestamp);
        return order;
    }

    public void releaseOrder(int index) {
        freeIndices[++freeIndexTop] = index;
    }

    public Order getOrder(int index) {
        if (index < 0 || index >= orderPool.length) {
            return null;
        }
        return orderPool[index];
    }

    public TreeMap<Long, PriceLevel> getBids() {
        return bids;
    }

    public TreeMap<Long, PriceLevel> getAsks() {
        return asks;
    }
}