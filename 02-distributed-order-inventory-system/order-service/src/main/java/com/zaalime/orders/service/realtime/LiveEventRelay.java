package com.zaalime.orders.service.realtime;

import com.zaalime.orders.common.event.InventoryEvent;
import com.zaalime.orders.common.event.OrderStatusEvent;
import org.springframework.amqp.rabbit.annotation.RabbitHandler;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Component;

/** Takes events off this instance's fanout queue and pushes them to connected browsers. */
@Component
@RabbitListener(queues = "#{liveEventsQueue.name}")
public class LiveEventRelay {

    public static final String INVENTORY_TOPIC = "/topic/inventory";
    public static final String ORDERS_TOPIC = "/topic/orders";

    private final SimpMessagingTemplate websocket;

    public LiveEventRelay(SimpMessagingTemplate websocket) {
        this.websocket = websocket;
    }

    @RabbitHandler
    public void onInventory(InventoryEvent event) {
        websocket.convertAndSend(INVENTORY_TOPIC, event);
    }

    @RabbitHandler
    public void onOrderStatus(OrderStatusEvent event) {
        websocket.convertAndSend(ORDERS_TOPIC, event);
    }
}
