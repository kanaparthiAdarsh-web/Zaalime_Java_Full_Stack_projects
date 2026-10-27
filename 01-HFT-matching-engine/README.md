# High-Frequency Trading (HFT) Matching Engine in Java

A high-performance, low-latency Limit Order Book (LOB) and Matching Engine built from scratch in Java. Designed with a focus on low execution latency, zero-allocation memory practices, and robust risk validation.

## 🚀 Key Features

* **Zero-Allocation Order Handling:** Utilizes reusable object patterns and primitive data structures to minimize Garbage Collection (GC) pauses.
* **Price-Time Priority Matching Engine:** Efficiently processes incoming Buy and Sell limit orders, matching them against resting orders using optimized tree structures (`TreeMap`).
* **TCP Socket Networking:** Implements custom server-client architecture (`OrderServer` & `OrderClient`) to ingest and process external orders in real-time.
* **Risk Management System:** Pre-trade validation checks covering order limits, quantity thresholds, and price bounds to ensure safety.
* **Performance & Latency Tracking:** Measures microsecond-level and nanosecond-level execution latencies.
* **Persistent Audit Logging:** Automatically outputs executed trade data to a structured CSV audit log (`trades.csv`).

## 🛠️ Tech Stack
* **Language:** Java
* **Networking:** Java Sockets (`ServerSocket`, `Socket`)
* **Concurrency & Collections:** `TreeMap`, custom memory reuse patterns
* **Build & Version Control:** Git / GitHub

## 📂 Project Architecture
* `com.engine.book` - Contains the Order Book and Price Level management.
* `com.engine.matching` - Core price-time priority matching logic.
* `com.engine.net` - Socket-based server and client for real-time order submission.
* `com.engine.risk` - Pre-trade risk validation filters.
* `com.engine.metrics` - Nanosecond precision latency tracking.
* `com.engine.persistence` - CSV trade logging and persistence.

## ⚙️ How to Run
1. Compile the project files.
2. Run `Main.java` to start the TCP `OrderServer`.
3. Run `OrderClient.java` (or send custom comma-separated TCP socket strings) to submit orders.
