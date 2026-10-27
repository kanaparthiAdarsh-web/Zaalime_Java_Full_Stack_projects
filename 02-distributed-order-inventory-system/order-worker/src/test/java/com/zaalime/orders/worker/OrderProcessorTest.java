package com.zaalime.orders.worker;

import com.zaalime.orders.common.Messaging;
import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.common.event.OrderLine;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import com.zaalime.orders.common.event.OrderStatusEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.FanoutExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@SpringBootTest
@Import(TestInfrastructure.class)
class OrderProcessorTest {

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin amqpAdmin;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    StringRedisTemplate redis;

    private long userId;
    private Queue statusFeed;

    @AfterEach
    void tearDown() {
        amqpAdmin.deleteQueue(statusFeed.getName());
    }

    @BeforeEach
    void setUp() {
        userId = jdbc.queryForObject(
                "INSERT INTO users (username, password_hash) VALUES (?, 'x') RETURNING id",
                Long.class, "worker-test-" + UUID.randomUUID());
        // listen on the fanout like an API instance would
        statusFeed = new Queue("test-feed-" + UUID.randomUUID(), false, false, false);
        amqpAdmin.declareQueue(statusFeed);
        amqpAdmin.declareBinding(BindingBuilder.bind(statusFeed).to(new FanoutExchange(Messaging.EVENTS_EXCHANGE)));
    }

    @Test
    void commitsOrderAndDecrementsInventory() {
        long productId = product(10);
        OrderPlacedEvent order = order(productId, 3);

        send(order);

        awaitStatus(order.orderId(), OrderStatus.COMPLETED);
        assertThat(committedStock(productId)).isEqualTo(7);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM order_items WHERE order_id = ?", Integer.class, order.orderId()))
                .isEqualTo(1);
        assertThat(redis.hasKey(RedisKeys.pendingOrder(order.orderId()))).isFalse();
        assertThat(statusesBroadcastFor(order.orderId()))
                .containsSubsequence(OrderStatus.PROCESSING, OrderStatus.COMPLETED);
    }

    @Test
    void redeliveredOrderIsNotAppliedTwice() {
        long productId = product(10);
        OrderPlacedEvent order = order(productId, 4);

        send(order);
        awaitStatus(order.orderId(), OrderStatus.COMPLETED);
        send(order); // at-least-once delivery: the same message shows up again
        send(order);

        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(committedStock(productId)).isEqualTo(6));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM orders WHERE id = ?", Integer.class, order.orderId()))
                .isEqualTo(1);
    }

    @Test
    void orderFailsAndReservationIsReleasedWhenDatabaseHasNoStock() {
        // Redis believed there were 5, Postgres only has 1 (e.g. Redis was restored from an old snapshot)
        long productId = product(1);
        redis.opsForValue().set(RedisKeys.stock(productId), "3"); // 5 minus the 2 reserved by this order
        OrderPlacedEvent order = order(productId, 2);

        send(order);

        awaitStatus(order.orderId(), OrderStatus.FAILED);
        assertThat(committedStock(productId)).isEqualTo(1);
        // the FAILED row is written before the reservation is handed back, so give it a moment
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> assertThat(redis.opsForValue().get(RedisKeys.stock(productId))).isEqualTo("5"));
        assertThat(jdbc.queryForObject("SELECT failure_reason FROM orders WHERE id = ?", String.class, order.orderId()))
                .contains("Insufficient stock");
    }

    @Test
    void multiItemOrderIsAllOrNothingInPostgres() {
        long plenty = product(10);
        long scarce = product(1);
        redis.opsForValue().set(RedisKeys.stock(plenty), "8");
        redis.opsForValue().set(RedisKeys.stock(scarce), "0");
        OrderPlacedEvent order = new OrderPlacedEvent(UUID.randomUUID(), userId, "tester", UUID.randomUUID().toString(),
                List.of(new OrderLine(plenty, "Plenty", 2, new BigDecimal("1.00")),
                        new OrderLine(scarce, "Scarce", 2, new BigDecimal("1.00"))),
                new BigDecimal("4.00"), Instant.now());

        send(order);

        awaitStatus(order.orderId(), OrderStatus.FAILED);
        assertThat(committedStock(plenty)).as("first line rolled back").isEqualTo(10);
        assertThat(committedStock(scarce)).isEqualTo(1);
    }

    // --- helpers ---

    private long product(int committed) {
        long id = jdbc.queryForObject(
                "INSERT INTO products (sku, name, price) VALUES (?, 'Worker test product', 9.99) RETURNING id",
                Long.class, "W-" + UUID.randomUUID().toString().substring(0, 12));
        jdbc.update("INSERT INTO inventory (product_id, quantity) VALUES (?, ?)", id, committed);
        redis.opsForValue().set(RedisKeys.stock(id), String.valueOf(committed));
        return id;
    }

    private OrderPlacedEvent order(long productId, int quantity) {
        BigDecimal price = new BigDecimal("9.99");
        return new OrderPlacedEvent(UUID.randomUUID(), userId, "tester", UUID.randomUUID().toString(),
                List.of(new OrderLine(productId, "Worker test product", quantity, price)),
                price.multiply(BigDecimal.valueOf(quantity)), Instant.now());
    }

    private void send(OrderPlacedEvent order) {
        rabbit.convertAndSend(Messaging.ORDER_EXCHANGE, Messaging.ORDER_ROUTING_KEY, order);
    }

    private void awaitStatus(UUID orderId, OrderStatus expected) {
        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> assertThat(
                jdbc.query("SELECT status FROM orders WHERE id = ?", (rs, i) -> rs.getString(1), orderId))
                .containsExactly(expected.name()));
    }

    private int committedStock(long productId) {
        return jdbc.queryForObject("SELECT quantity FROM inventory WHERE product_id = ?", Integer.class, productId);
    }

    private List<OrderStatus> statusesBroadcastFor(UUID orderId) {
        List<OrderStatus> seen = new ArrayList<>();
        await().atMost(Duration.ofSeconds(5)).until(() -> {
            Object msg;
            while ((msg = rabbit.receiveAndConvert(statusFeed.getName(), 200)) != null) {
                if (msg instanceof OrderStatusEvent e && e.orderId().equals(orderId)) {
                    seen.add(e.status());
                }
            }
            return seen.contains(OrderStatus.COMPLETED);
        });
        return seen;
    }
}
