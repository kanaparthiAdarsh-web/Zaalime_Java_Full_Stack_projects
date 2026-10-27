package com.zaalime.orders.service.realtime;

import com.zaalime.orders.common.Messaging;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Publishes dashboard events to the fanout exchange.
 * <p>
 * These are notifications only, so they are handed to a small background pool and
 * the checkout thread returns straight away instead of paying for 2-3 extra
 * broker round trips. If the pool's queue is full (broker slow or down) events are
 * dropped and counted - the dashboard resyncs from the REST API on reconnect.
 */
@Component
public class EventPublisher {

    private static final Logger log = LoggerFactory.getLogger(EventPublisher.class);

    private final RabbitTemplate rabbit;
    private final ThreadPoolExecutor executor;
    private final AtomicLong dropped = new AtomicLong();

    public EventPublisher(RabbitTemplate rabbit) {
        this.rabbit = rabbit;
        AtomicInteger seq = new AtomicInteger();
        this.executor = new ThreadPoolExecutor(4, 4, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(10_000),
                r -> {
                    Thread t = new Thread(r, "live-events-" + seq.incrementAndGet());
                    t.setDaemon(true);
                    return t;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public void publish(Object event) {
        try {
            executor.execute(() -> send(event));
        } catch (RejectedExecutionException e) {
            long n = dropped.incrementAndGet();
            if (n % 1000 == 1) {
                log.warn("Live event queue full, {} events dropped so far", n);
            }
        }
    }

    private void send(Object event) {
        try {
            rabbit.convertAndSend(Messaging.EVENTS_EXCHANGE, "", event);
        } catch (AmqpException e) {
            log.warn("Could not publish live event {}: {}", event.getClass().getSimpleName(), e.getMessage());
        }
    }

    @PreDestroy
    void shutdown() throws InterruptedException {
        executor.shutdown();
        executor.awaitTermination(5, TimeUnit.SECONDS);
    }
}
