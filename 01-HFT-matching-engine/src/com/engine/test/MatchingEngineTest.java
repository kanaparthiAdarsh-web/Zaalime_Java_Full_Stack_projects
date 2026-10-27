package com.engine.test;

import com.engine.book.OrderBook;
import com.engine.disruptor.OrderEvent;
import com.engine.disruptor.OrderEventHandler;
import com.engine.matching.MatchingEngine;
import com.engine.model.OrderType;
import com.engine.model.Trade;

import java.util.ArrayList;
import java.util.List;

public class MatchingEngineTest {
    public static void main(String[] args) {
        System.out.println("Running Matching Engine Unit Tests...");

        testExactMatch();
        testPartialMatch();

        System.out.println("All automated tests completed successfully!");
    }

    private static void testExactMatch() {
        OrderBook book = new OrderBook(100);
        List<Trade> trades = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(book, trades::add);
        OrderEventHandler handler = new OrderEventHandler(book, engine);

        OrderEvent buyEvent = new OrderEvent();
        buyEvent.set(1L, OrderType.BUY, 10050L, 10L, System.nanoTime());
        handler.onEvent(buyEvent, 0, true);

        OrderEvent sellEvent = new OrderEvent();
        sellEvent.set(2L, OrderType.SELL, 10050L, 10L, System.nanoTime());
        handler.onEvent(sellEvent, 0, true);

        assert trades.size() == 1 : "Test Failed: Expected 1 trade execution!";
        assert trades.get(0).executedQuantity == 10L : "Test Failed: Expected quantity 10!";
        assert trades.get(0).executedPriceTicks == 10050L : "Test Failed: Expected price 10050!";
        
        System.out.println("[PASS] testExactMatch passed successfully.");
    }

    private static void testPartialMatch() {
        OrderBook book = new OrderBook(100);
        List<Trade> trades = new ArrayList<>();
        MatchingEngine engine = new MatchingEngine(book, trades::add);
        OrderEventHandler handler = new OrderEventHandler(book, engine);

        OrderEvent buyEvent = new OrderEvent();
        buyEvent.set(1L, OrderType.BUY, 10050L, 50L, System.nanoTime());
        handler.onEvent(buyEvent, 0, true);

        OrderEvent sellEvent = new OrderEvent();
        sellEvent.set(2L, OrderType.SELL, 10050L, 20L, System.nanoTime());
        handler.onEvent(sellEvent, 0, true);

        assert trades.size() == 1 : "Test Failed: Expected 1 trade execution!";
        assert trades.get(0).executedQuantity == 20L : "Test Failed: Expected partial execution quantity 20!";
        
        System.out.println("[PASS] testPartialMatch passed successfully.");
    }
}