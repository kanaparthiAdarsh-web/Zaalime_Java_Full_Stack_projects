package com.engine.matching;

import com.engine.book.OrderBook;
import com.engine.book.PriceLevel;
import com.engine.model.Order;
import com.engine.model.OrderType;
import com.engine.model.Trade;

import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public class MatchingEngine {
    private final OrderBook orderBook;
    private final Consumer<Trade> tradeConsumer;
    private long nextMatchId = 1;
    private final Trade reusableTrade = new Trade();

    public MatchingEngine(OrderBook orderBook, Consumer<Trade> tradeConsumer) {
        this.orderBook = orderBook;
        this.tradeConsumer = tradeConsumer;
    }

    public void processOrder(Order incomingOrder) {
        if (incomingOrder.orderType == OrderType.BUY) {
            matchBuyOrder(incomingOrder);
        } else {
            matchSellOrder(incomingOrder);
        }
    }

    private void matchBuyOrder(Order buy) {
        TreeMap<Long, PriceLevel> asks = orderBook.getAsks();

        while (buy.remainingQty > 0 && !asks.isEmpty()) {
            Map.Entry<Long, PriceLevel> bestAskEntry = asks.firstEntry();
            long bestAskPrice = bestAskEntry.getKey();

            if (buy.priceTicks < bestAskPrice) {
                break;
            }

            PriceLevel askLevel = bestAskEntry.getValue();
            executeAtLevel(buy, askLevel, asks, false);
        }

        if (buy.remainingQty > 0) {
            addOrderToBook(buy, orderBook.getBids());
        }
    }

    private void matchSellOrder(Order sell) {
        TreeMap<Long, PriceLevel> bids = orderBook.getBids();

        while (sell.remainingQty > 0 && !bids.isEmpty()) {
            Map.Entry<Long, PriceLevel> bestBidEntry = bids.firstEntry();
            long bestBidPrice = bestBidEntry.getKey();

            if (sell.priceTicks > bestBidPrice) {
                break;
            }

            PriceLevel bidLevel = bestBidEntry.getValue();
            executeAtLevel(sell, bidLevel, bids, true);
        }

        if (sell.remainingQty > 0) {
            addOrderToBook(sell, orderBook.getAsks());
        }
    }

    private void executeAtLevel(Order incoming, PriceLevel level, TreeMap<Long, PriceLevel> bookSide, boolean isBidSide) {
        long tradeQty = Math.min(incoming.remainingQty, level.totalVolume);
        
        incoming.remainingQty -= tradeQty;
        level.totalVolume -= tradeQty;

        reusableTrade.reset(
            nextMatchId++,
            isBidSide ? 0 : incoming.orderId,
            isBidSide ? incoming.orderId : 0,
            level.priceTicks,
            tradeQty,
            System.nanoTime()
        );

        tradeConsumer.accept(reusableTrade);

        if (level.totalVolume <= 0) {
            bookSide.remove(level.priceTicks);
        }
    }

    private void addOrderToBook(Order order, TreeMap<Long, PriceLevel> bookSide) {
        PriceLevel level = bookSide.computeIfAbsent(order.priceTicks, PriceLevel::new);
        level.totalVolume += order.remainingQty;
    }
}