package com.zaalime.orders.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.zaalime.orders.common.Messaging;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(TestInfrastructure.class)
class CheckoutApiTest {

    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    TestRestTemplate http;

    @Autowired
    RabbitTemplate rabbit;

    @Autowired
    AmqpAdmin amqpAdmin;

    private String adminToken;
    private String userToken;

    @BeforeEach
    void setUp() {
        amqpAdmin.purgeQueue(Messaging.ORDER_QUEUE, false);
        adminToken = login("admin", "admin12345");
        userToken = register("buyer" + SEQ.incrementAndGet() + "_" + System.nanoTime());
    }

    @Test
    void checkoutIsAcceptedQueuedAndVisibleAsPending() {
        long productId = createProduct(10, "199.99");

        ResponseEntity<JsonNode> response = placeOrder(userToken, newKey(), productId, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        String orderId = response.getBody().get("orderId").asText();
        assertThat(response.getBody().get("status").asText()).isEqualTo("PENDING");
        assertThat(response.getBody().get("totalAmount").decimalValue()).isEqualByComparingTo("399.98");
        assertThat(response.getHeaders().getLocation()).hasToString("/api/orders/" + orderId);

        OrderPlacedEvent queued = (OrderPlacedEvent) rabbit.receiveAndConvert(Messaging.ORDER_QUEUE, 5_000);
        assertThat(queued).isNotNull();
        assertThat(queued.orderId()).hasToString(orderId);
        assertThat(queued.lines()).singleElement().satisfies(l -> assertThat(l.quantity()).isEqualTo(2));

        JsonNode order = get("/api/orders/" + orderId, userToken).getBody();
        assertThat(order.get("status").asText()).isEqualTo("PENDING");
        assertThat(availableStock(productId)).isEqualTo(8);
    }

    @Test
    void retryWithSameIdempotencyKeyDoesNotCreateSecondOrder() {
        long productId = createProduct(10, "50.00");
        String key = newKey();

        ResponseEntity<JsonNode> first = placeOrder(userToken, key, productId, 1);
        ResponseEntity<JsonNode> retry = placeOrder(userToken, key, productId, 1);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getHeaders().getFirst("Idempotent-Replayed")).isEqualTo("true");
        assertThat(retry.getBody().get("orderId").asText()).isEqualTo(first.getBody().get("orderId").asText());

        assertThat(rabbit.receive(Messaging.ORDER_QUEUE, 2_000)).isNotNull();
        assertThat(rabbit.receive(Messaging.ORDER_QUEUE, 500)).as("only one message queued").isNull();
        assertThat(availableStock(productId)).isEqualTo(9);
    }

    @Test
    void reusingKeyForDifferentOrderIsRejected() {
        long productId = createProduct(10, "50.00");
        String key = newKey();

        placeOrder(userToken, key, productId, 1);
        ResponseEntity<JsonNode> other = placeOrder(userToken, key, productId, 3);

        assertThat(other.getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
    }

    @Test
    void sameKeyFromDifferentUsersAreIndependent() {
        long productId = createProduct(10, "50.00");
        String key = newKey();
        String otherUser = register("other_" + System.nanoTime());

        String a = placeOrder(userToken, key, productId, 1).getBody().get("orderId").asText();
        String b = placeOrder(otherUser, key, productId, 1).getBody().get("orderId").asText();

        assertThat(a).isNotEqualTo(b);
        assertThat(get("/api/orders/" + a, otherUser).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void outOfStockReturnsConflictAndReservesNothing() {
        long productId = createProduct(1, "10.00");

        ResponseEntity<JsonNode> response = placeOrder(userToken, newKey(), productId, 2);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(response.getBody().get("title").asText()).isEqualTo("Out of stock");
        assertThat(availableStock(productId)).isEqualTo(1);
    }

    @Test
    void requestValidation() {
        long productId = createProduct(5, "10.00");

        assertThat(placeOrder(userToken, null, productId, 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(placeOrder(userToken, "short", productId, 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(placeOrder(userToken, newKey(), productId, 0).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(placeOrder(userToken, newKey(), productId, 11).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(placeOrder(userToken, newKey(), 987_654, 1).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(availableStock(productId)).isEqualTo(5);
    }

    @Test
    void securityRules() {
        assertThat(placeOrder(null, newKey(), 1, 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        assertThat(placeOrder("not-a-jwt", newKey(), 1, 1).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);

        Map<String, Object> product = Map.of("sku", "SKU" + System.nanoTime(), "name", "x", "price", "1.00", "initialStock", 1);
        assertThat(exchange(HttpMethod.POST, "/api/products", userToken, product, null).getStatusCode())
                .isEqualTo(HttpStatus.FORBIDDEN);

        assertThat(http.getForEntity("/api/products", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);

        ResponseEntity<JsonNode> badLogin = http.postForEntity("/api/auth/login",
                Map.of("username", "admin", "password", "wrong-password"), JsonNode.class);
        assertThat(badLogin.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void cartCheckoutPlacesOrderAndEmptiesCart() {
        long productId = createProduct(10, "20.00");
        exchange(HttpMethod.PUT, "/api/cart/items/" + productId, userToken, Map.of("quantity", 3), null);

        JsonNode cart = get("/api/cart", userToken).getBody();
        assertThat(cart.get("total").decimalValue()).isEqualByComparingTo("60.00");

        String key = newKey();
        ResponseEntity<JsonNode> response = exchange(HttpMethod.POST, "/api/cart/checkout", userToken, null, key);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(get("/api/cart", userToken).getBody().get("items")).isEmpty();

        // network retry after the cart was cleared still gets the original order back
        ResponseEntity<JsonNode> retry = exchange(HttpMethod.POST, "/api/cart/checkout", userToken, null, key);
        assertThat(retry.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(retry.getBody().get("orderId")).isEqualTo(response.getBody().get("orderId"));
        assertThat(availableStock(productId)).isEqualTo(7);
    }

    @Test
    void restockAddsToLiveStock() {
        long productId = createProduct(2, "20.00");

        ResponseEntity<JsonNode> response = exchange(HttpMethod.POST, "/api/products/" + productId + "/restock",
                adminToken, Map.of("quantity", 5), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().get("committedStock").asInt()).isEqualTo(7);
        assertThat(availableStock(productId)).isEqualTo(7);
    }

    @Test
    void flashSaleOverHttpSellsExactlyTheStock() throws Exception {
        long productId = createProduct(50, "449.99");
        List<String> buyers = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            buyers.add(register("flash" + i + "_" + System.nanoTime()));
        }

        int requests = 300;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<HttpStatus>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(50)) {
            for (int i = 0; i < requests; i++) {
                String token = buyers.get(i % buyers.size());
                results.add(pool.submit(() -> {
                    start.await();
                    return HttpStatus.valueOf(placeOrder(token, newKey(), productId, 1).getStatusCode().value());
                }));
            }
            start.countDown();

            int accepted = 0;
            int soldOut = 0;
            for (Future<HttpStatus> r : results) {
                HttpStatus status = r.get();
                if (status == HttpStatus.ACCEPTED) {
                    accepted++;
                } else if (status == HttpStatus.CONFLICT) {
                    soldOut++;
                }
            }
            assertThat(accepted).isEqualTo(50);
            assertThat(soldOut).isEqualTo(250);
        }
        assertThat(availableStock(productId)).isZero();
    }

    // --- helpers ---

    private ResponseEntity<JsonNode> placeOrder(String token, String key, long productId, int quantity) {
        Map<String, Object> body = Map.of("items", List.of(Map.of("productId", productId, "quantity", quantity)));
        return exchange(HttpMethod.POST, "/api/orders", token, body, key);
    }

    private long createProduct(int stock, String price) {
        Map<String, Object> body = Map.of(
                "sku", "TEST-" + SEQ.incrementAndGet() + "-" + System.nanoTime() % 100000,
                "name", "Test product",
                "price", price,
                "initialStock", stock);
        ResponseEntity<JsonNode> response = exchange(HttpMethod.POST, "/api/products", adminToken, body, null);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody().get("id").asLong();
    }

    private long availableStock(long productId) {
        return http.getForObject("/api/products/" + productId, JsonNode.class).get("availableStock").asLong();
    }

    private String register(String username) {
        ResponseEntity<JsonNode> r = http.postForEntity("/api/auth/register",
                Map.of("username", username, "password", "password123"), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return r.getBody().get("token").asText();
    }

    private String login(String username, String password) {
        ResponseEntity<JsonNode> r = http.postForEntity("/api/auth/login",
                Map.of("username", username, "password", password), JsonNode.class);
        assertThat(r.getStatusCode()).isEqualTo(HttpStatus.OK);
        return r.getBody().get("token").asText();
    }

    private ResponseEntity<JsonNode> get(String path, String token) {
        return exchange(HttpMethod.GET, path, token, null, null);
    }

    private ResponseEntity<JsonNode> exchange(HttpMethod method, String path, String token, Object body, String idempotencyKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        return http.exchange(path, method, new HttpEntity<>(body, headers), JsonNode.class);
    }

    private static String newKey() {
        return UUID.randomUUID().toString();
    }
}
