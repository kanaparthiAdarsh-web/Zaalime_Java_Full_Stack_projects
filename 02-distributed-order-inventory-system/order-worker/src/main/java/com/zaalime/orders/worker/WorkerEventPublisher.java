package com.zaalime.orders.worker;

import com.zaalime.orders.common.Messaging;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

@Component
public class WorkerEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(WorkerEventPublisher.class);

    private final RabbitTemplate rabbit;

    public WorkerEventPublisher(RabbitTemplate rabbit) {
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
