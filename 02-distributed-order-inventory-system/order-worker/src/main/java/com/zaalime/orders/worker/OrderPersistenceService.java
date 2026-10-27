package com.zaalime.orders.worker;

import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.event.OrderLine;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * All Postgres writes for an order. The circuit breaker wraps the transaction: when
 * the database keeps failing, calls fail immediately instead of piling up on a
 * dead connection pool, and the listener's retry/backoff takes over.
 */
@Service
public class OrderPersistenceService {

    private final OrderJdbcRepository orders;

    public OrderPersistenceService(OrderJdbcRepository orders) {
        this.orders = orders;
    }

    public sealed interface CommitResult permits Committed, AlreadyProcessed {
    }

    /** @param remaining committed stock left per product id */
    public record Committed(Map<Long, Integer> remaining) implements CommitResult {
    }

    public record AlreadyProcessed(OrderStatus status) implements CommitResult {
    }

    /**
     * Writes the order and decrements committed inventory in one transaction.
     *
     * @throws OrderRejectedException if Postgres does not have the stock (rolled back)
     */
    @CircuitBreaker(name = "orderDb")
    @Transactional
    public CommitResult commit(OrderPlacedEvent event) {
        if (!orders.insertOrder(event, OrderStatus.PROCESSING, null)) {
            var existing = orders.findStatus(event.orderId());
            if (existing.isPresent()) {
                return new AlreadyProcessed(existing.get()); // redelivered message
            }
            throw new OrderRejectedException("Idempotency key already used by another order", false);
        }

        // lock rows in a fixed order so two multi-item orders can't deadlock each other
        List<OrderLine> lines = event.lines().stream()
                .sorted(Comparator.comparingLong(OrderLine::productId))
                .toList();

        Map<Long, Integer> remaining = new LinkedHashMap<>();
        for (OrderLine line : lines) {
            int left = orders.decrementStock(line.productId(), line.quantity())
                    .orElseThrow(() -> new OrderRejectedException("Insufficient stock for '" + line.productName() + "'", true));
            remaining.put(line.productId(), left);
        }

        orders.insertItems(event.orderId(), event.lines());
        orders.updateStatus(event.orderId(), OrderStatus.COMPLETED);
        return new Committed(remaining);
    }

    @CircuitBreaker(name = "orderDb")
    @Transactional
    public void recordFailure(OrderPlacedEvent event, String reason) {
        if (orders.insertOrder(event, OrderStatus.FAILED, reason)) {
            orders.insertItems(event.orderId(), event.lines());
        }
    }
}
