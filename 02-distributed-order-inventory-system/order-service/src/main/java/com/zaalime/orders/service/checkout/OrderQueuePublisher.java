package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.Messaging;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Sends the order to the worker queue and waits for the broker's publisher confirm,
 * so "202 Accepted" really means the message is safely on disk in RabbitMQ.
 * <p>
 * Confirms are correlated (async): the channel goes straight back to the cache and
 * the request thread just waits on a future. The older blocking waitForConfirms()
 * pins virtual threads on Java 21 and stalled the service under load.
 * <p>
 * The circuit breaker makes checkout fail fast (and release stock) while the broker is down.
 */
@Component
public class OrderQueuePublisher {

    private static final long CONFIRM_TIMEOUT_MS = 5_000;

    private final RabbitTemplate rabbit;

    public OrderQueuePublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    /**
     * @throws AmqpException           the broker refused the message or it could not be sent - safe to undo
     * @throws ConfirmTimeoutException the message was sent but no confirm arrived in time - outcome unknown
     */
    @CircuitBreaker(name = "broker")
    public void publish(OrderPlacedEvent event) {
        CorrelationData correlation = new CorrelationData(event.orderId().toString());
        rabbit.convertAndSend(Messaging.ORDER_EXCHANGE, Messaging.ORDER_ROUTING_KEY, event, message -> {
            message.getMessageProperties().setMessageId(event.orderId().toString());
            return message;
        }, correlation);

        CorrelationData.Confirm confirm;
        try {
            confirm = correlation.getFuture().get(CONFIRM_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new ConfirmTimeoutException(event);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ConfirmTimeoutException(event);
        } catch (ExecutionException e) {
            throw new AmqpException("Publisher confirm failed", e.getCause());
        }

        if (!confirm.isAck()) {
            throw new AmqpException("Broker rejected order " + event.orderId() + ": " + confirm.getReason());
        }
        if (correlation.getReturned() != null) {
            throw new AmqpException("Order " + event.orderId() + " was unroutable: " + correlation.getReturned().getReplyText());
        }
    }

    public static class ConfirmTimeoutException extends AmqpException {
        ConfirmTimeoutException(OrderPlacedEvent event) {
            super("No publisher confirm for order " + event.orderId() + " within " + CONFIRM_TIMEOUT_MS + " ms");
        }
    }
}
