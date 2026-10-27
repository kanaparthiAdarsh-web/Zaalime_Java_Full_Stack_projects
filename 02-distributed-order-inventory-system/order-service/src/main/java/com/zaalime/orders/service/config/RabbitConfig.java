package com.zaalime.orders.service.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaalime.orders.common.Messaging;
import org.springframework.amqp.core.AnonymousQueue;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Topology is declared by both the API and the worker. Declarations are idempotent,
 * so whichever starts first creates it and the order services start in does not matter.
 */
@Configuration
public class RabbitConfig {

    @Bean
    DirectExchange orderExchange() {
        return new DirectExchange(Messaging.ORDER_EXCHANGE, true, false);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(Messaging.DEAD_LETTER_EXCHANGE, true, false);
    }

    @Bean
    Queue orderQueue() {
        return QueueBuilder.durable(Messaging.ORDER_QUEUE)
                .deadLetterExchange(Messaging.DEAD_LETTER_EXCHANGE)
                .deadLetterRoutingKey(Messaging.DEAD_LETTER_QUEUE)
                .build();
    }

    @Bean
    Queue deadLetterQueue() {
        return QueueBuilder.durable(Messaging.DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding orderBinding() {
        return BindingBuilder.bind(orderQueue()).to(orderExchange()).with(Messaging.ORDER_ROUTING_KEY);
    }

    @Bean
    Binding deadLetterBinding() {
        return BindingBuilder.bind(deadLetterQueue()).to(deadLetterExchange()).with(Messaging.DEAD_LETTER_QUEUE);
    }

    @Bean
    FanoutExchange eventsExchange() {
        return new FanoutExchange(Messaging.EVENTS_EXCHANGE, true, false);
    }

    // one exclusive, auto-delete queue per API instance -> every instance sees every event
    @Bean
    Queue liveEventsQueue() {
        return new AnonymousQueue();
    }

    @Bean
    Binding liveEventsBinding() {
        return BindingBuilder.bind(liveEventsQueue()).to(eventsExchange());
    }

    @Bean
    MessageConverter messageConverter(ObjectMapper objectMapper) {
        Jackson2JsonMessageConverter converter = new Jackson2JsonMessageConverter(objectMapper);
        DefaultJackson2JavaTypeMapper typeMapper = new DefaultJackson2JavaTypeMapper();
        typeMapper.setTrustedPackages("com.zaalime.orders.common.event");
        converter.setJavaTypeMapper(typeMapper);
        return converter;
    }
}
