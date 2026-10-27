package com.zaalime.orders.service.realtime;

import com.zaalime.orders.common.Messaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes dashboard events to the fanout exchange. These are notifications only,
 * so a failure here is logged and never fails the business operation.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbit;

    public EventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
    }

    public void publish(Object event) {
        try {
            rabbit.convertAndSend(Messaging.EVENTS_EXCHANGE, "", event);
        } catch (AmqpException e) {
            log.warn("Could not publish live event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }
}
