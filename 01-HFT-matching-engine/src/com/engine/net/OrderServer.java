package com.engine.net;

import com.engine.book.OrderBook;
import com.engine.disruptor.OrderEvent;
import com.engine.disruptor.OrderEventHandler;
import com.engine.matching.MatchingEngine;
import com.engine.metrics.LatencyTracker;
import com.engine.model.OrderType;
import com.engine.risk.RiskValidator;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;

public class OrderServer {
    private final int port;
    private final OrderEventHandler eventHandler;
    private final RiskValidator riskValidator;
    private final LatencyTracker latencyTracker;

    public OrderServer(int port, OrderBook orderBook, MatchingEngine matchingEngine) {
        this.port = port;
        this.eventHandler = new OrderEventHandler(orderBook, matchingEngine);
        this.riskValidator = new RiskValidator(100000, 1000000);
        this.latencyTracker = new LatencyTracker();
    }

    public void start() {
        try (ServerSocket serverSocket = new ServerSocket(port)) {
            System.out.println("OrderServer listening on port " + port);

            while (true) {
                Socket clientSocket = serverSocket.accept();
                new Thread(() -> handleClient(clientSocket)).start();
            }
        } catch (Exception e) {
            e.printStackTrace();
        } finally {
            latencyTracker.printStats();
        }
    }

    private void handleClient(Socket socket) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                long startTime = System.nanoTime();
                
                String[] parts = line.split(",");
                if (parts.length >= 4) {
                    long orderId = Long.parseLong(parts[0].trim());
                    OrderType type = OrderType.valueOf(parts[1].trim().toUpperCase());
                    long price = Long.parseLong(parts[2].trim());
                    long qty = Long.parseLong(parts[3].trim());

                    if (!riskValidator.validate(orderId, type, price, qty)) {
                        System.out.println("REJECTED BY RISK SYSTEM: Order ID " + orderId);
                        continue;
                    }

                    long timestamp = System.nanoTime();
                    OrderEvent event = new OrderEvent();
                    event.set(orderId, type, price, qty, timestamp);
                    eventHandler.onEvent(event, 0, true);

                    latencyTracker.recordLatency(startTime);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}