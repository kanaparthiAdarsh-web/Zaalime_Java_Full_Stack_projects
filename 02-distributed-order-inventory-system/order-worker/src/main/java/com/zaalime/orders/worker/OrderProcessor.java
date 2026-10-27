package com.zaalime.orders.worker;

import com.zaalime.orders.common.Messaging;
import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.event.InventoryEvent;
import com.zaalime.orders.common.event.OrderLine;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import com.zaalime.orders.common.event.OrderStatusEvent;
import com.zaalime.orders.worker.OrderPersistenceService.AlreadyProcessed;
import com.zaalime.orders.worker.OrderPersistenceService.Committed;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Pulls accepted orders off order-queue and makes them durable.
 * <p>
 * Delivery is at-least-once, so every step is safe to repeat: the order id is the
 * primary key, and a redelivered order that is already in Postgres just has its
 * final status re-announced. Infrastructure errors are thrown so the container
 * retries with backoff and finally dead-letters the message; business rejections
 * are handled here and never retried.
 */
@Component
public class OrderProcessor {

    private static final Logger log = LoggerFactory.getLogger(OrderProcessor.class);

    private final OrderPersistenceService persistence;
    private final OrderJdbcRepository orders;
    private final LiveStock liveStock;
    private final WorkerEventPublisher events;

    public OrderProcessor(OrderPersistenceService persistence, OrderJdbcRepository orders,
                          LiveStock liveStock, WorkerEventPublisher events) {
        this.persistence = persistence;
        this.orders = orders;
        this.liveStock = liveStock;
        this.events = events;
    }

    @RabbitListener(queues = Messaging.ORDER_QUEUE)
    public void onOrderPlaced(OrderPlacedEvent order) {
        Optional<OrderStatus> existing = orders.findStatus(order.orderId());
        if (existing.isPresent()) {
            announceDuplicate(order, existing.get());
            return;
        }

        status(order, OrderStatus.PROCESSING, "Worker picked up the order");

        try {
            var result = persistence.commit(order);
            if (result instanceof AlreadyProcessed done) {
                announceDuplicate(order, done.status());
                return;
            }
            var committed = (Committed) result;
            liveStock.clearPendingSnapshot(order.orderId());
            status(order, OrderStatus.COMPLETED, "Order confirmed");
            for (OrderLine line : order.lines()) {
                events.publish(new InventoryEvent(line.productId(), line.productName(),
                        liveStock.available(line.productId()),
                        committed.remaining().get(line.productId()).longValue(), "COMMITTED", Instant.now()));
            }
            log.debug("Order {} committed", order.orderId());
        } catch (OrderRejectedException rejected) {
            fail(order, rejected);
        }
    }

    private void fail(OrderPlacedEvent order, OrderRejectedException reason) {
        log.warn("Order {} rejected: {}", order.orderId(), reason.getMessage());
        if (reason.isRecordable()) {
            persistence.recordFailure(order, reason.getMessage());
        }
        // the reservation in Redis was for an order that will never ship - give it back
        List<Long> levels = liveStock.release(order.lines());
        liveStock.clearPendingSnapshot(order.orderId());
        status(order, OrderStatus.FAILED, reason.getMessage());
        for (int i = 0; i < order.lines().size(); i++) {
            OrderLine line = order.lines().get(i);
            if (levels.get(i) >= 0) {
                events.publish(new InventoryEvent(line.productId(), line.productName(), levels.get(i), null,
                        "RELEASED", Instant.now()));
            }
        }
    }

    private void announceDuplicate(OrderPlacedEvent order, OrderStatus status) {
        log.info("Order {} already processed ({}), skipping redelivery", order.orderId(), status);
        liveStock.clearPendingSnapshot(order.orderId());
        if (status.isFinal()) {
            status(order, status, "Order " + status.name().toLowerCase());
        }
    }

    private void status(OrderPlacedEvent order, OrderStatus status, String message) {
        events.publish(new OrderStatusEvent(order.orderId(), order.username(), status, order.totalAmount(),
                message, Instant.now()));
    }
}
