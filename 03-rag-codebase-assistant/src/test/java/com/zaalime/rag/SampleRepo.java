package com.zaalime.rag;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/** A tiny repository on disk to index in tests. */
public final class SampleRepo {

    public static final String ORDER_SERVICE = """
            package shop;

            /** Places orders and reserves stock so nothing is oversold. */
            public class OrderService {

                private final StockReservation reservations;

                public OrderService(StockReservation reservations) {
                    this.reservations = reservations;
                }

                public Order placeOrder(long productId, int quantity) {
                    if (!reservations.tryReserve(productId, quantity)) {
                        throw new OutOfStockException(productId);
                    }
                    return new Order(productId, quantity);
                }
            }
            """;

    public static final String RATE_LIMITER = """
            import time


            class TokenBucket:
                \"\"\"Limits how many requests a client may send per second.\"\"\"

                def __init__(self, rate: float, capacity: int):
                    self.rate = rate
                    self.capacity = capacity
                    self.tokens = capacity
                    self.updated = time.monotonic()

                def allow(self) -> bool:
                    now = time.monotonic()
                    self.tokens = min(self.capacity, self.tokens + (now - self.updated) * self.rate)
                    self.updated = now
                    if self.tokens >= 1:
                        self.tokens -= 1
                        return True
                    return False
            """;

    public static final String README = """
            # Shop

            ## Deployment

            Run `docker compose up` and open the dashboard on port 8080.

            ## Payments

            Payments are captured only after the order has been confirmed by the warehouse.
            """;

    private SampleRepo() {
    }

    public static Path create(Path root) {
        try {
            Files.createDirectories(root.resolve("shop/src"));
            Files.createDirectories(root.resolve("shop/target"));
            Files.createDirectories(root.resolve("tools"));
            Files.writeString(root.resolve("shop/src/OrderService.java"), ORDER_SERVICE);
            Files.writeString(root.resolve("shop/README.md"), README);
            Files.writeString(root.resolve("tools/rate_limiter.py"), RATE_LIMITER);
            Files.writeString(root.resolve("shop/target/Ignored.java"), "class Ignored {}"); // build output, skipped
            return root;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
