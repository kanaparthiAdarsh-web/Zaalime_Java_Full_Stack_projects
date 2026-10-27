package com.engine.persistence;

import com.engine.model.Trade;
import java.io.FileWriter;
import java.io.PrintWriter;
import java.io.IOException;

public class TradeLogger {
    private final String filePath;

    public TradeLogger(String filePath) {
        this.filePath = filePath;
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, false))) {
            writer.println("MatchID,PriceTicks,Quantity,Timestamp");
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public void logTrade(Trade trade) {
        try (PrintWriter writer = new PrintWriter(new FileWriter(filePath, true))) {
            writer.println(trade.matchId + "," + trade.executedPriceTicks + "," + trade.executedQuantity + "," + System.currentTimeMillis());
            System.out.println("Trade persisted to CSV log: " + filePath);
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}