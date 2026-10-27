package com.engine.net;

import java.io.PrintWriter;
import java.net.Socket;

public class OrderClient {
    public static void main(String[] args) {
        String host = "127.0.0.1";
        int port = 8080;

        try (Socket socket = new Socket(host, port);
             PrintWriter writer = new PrintWriter(socket.getOutputStream(), true)) {
            
            System.out.println("Connected to OrderServer. Sending orders...");

            writer.println("101, BUY, 10050, 10");
            Thread.sleep(100);
            writer.println("102, SELL, 10050, 10");
            
            System.out.println("Orders sent successfully.");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}