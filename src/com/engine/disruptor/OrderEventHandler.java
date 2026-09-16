package com.engine.disruptor;

import com.engine.book.OrderBook;
import com.engine.matching.MatchingEngine;
import com.engine.model.Order;

public class OrderEventHandler {
    private final OrderBook orderBook;
    private final MatchingEngine matchingEngine;

    public OrderEventHandler(OrderBook orderBook, MatchingEngine matchingEngine) {
        this.orderBook = orderBook;
        this.matchingEngine = matchingEngine;
    }

    public void onEvent(OrderEvent event, long sequence, boolean endOfBatch) {
        Order order = orderBook.acquireOrder(
            event.orderId,
            event.orderType,
            event.priceTicks,
            event.quantity,
            event.timestamp
        );

        matchingEngine.processOrder(order);
    }
}