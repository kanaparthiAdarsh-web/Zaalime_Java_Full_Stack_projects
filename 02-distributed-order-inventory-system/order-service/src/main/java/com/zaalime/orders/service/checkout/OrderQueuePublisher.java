package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.Messaging;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Sends the order to the worker queue and waits for the broker's publisher confirm,
 * so "202 Accepted" really means the message is safely on disk in RabbitMQ.
 * The circuit breaker makes checkout fail fast (and release stock) while the broker is down.
 */
@Component
public class OrderQueuePublisher {

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbit;

    public OrderQueuePublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    @CircuitBreaker(name = "broker")
    public void publish(OrderPlacedEvent event) {
        rabbit.invoke(ops -> {
            ops.convertAndSend(Messaging.ORDER_EXCHANGE, Messaging.ORDER_ROUTING_KEY, event, message -> {
                message.getMessageProperties().setMessageId(event.orderId().toString());
                return message;
            });
            ops.waitForConfirmsOrDie(CONFIRM_TIMEOUT_MS);
            return null;
        });
    }
}
