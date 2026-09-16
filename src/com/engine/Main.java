package com.engine;

import com.engine.book.OrderBook;
import com.engine.matching.MatchingEngine;
import com.engine.model.Trade;
import com.engine.net.OrderServer;
import com.engine.persistence.TradeLogger;

public class Main {
    public static void main(String[] args) {
        int maxOrders = 1000;
        OrderBook orderBook = new OrderBook(maxOrders);
        
        TradeLogger tradeLogger = new TradeLogger("trades.csv");

        MatchingEngine matchingEngine = new MatchingEngine(orderBook, (Trade trade) -> {
            System.out.println("TRADE EXECUTED -> Match ID: " + trade.matchId + 
                               ", Price: " + trade.executedPriceTicks + 
                               ", Qty: " + trade.executedQuantity);
            
            tradeLogger.logTrade(trade);
        });

        int port = 8080;
        OrderServer server = new OrderServer(port, orderBook, matchingEngine);
        
        System.out.println("Starting high-frequency trading engine server with CSV logging...");
        server.start();
    }
}