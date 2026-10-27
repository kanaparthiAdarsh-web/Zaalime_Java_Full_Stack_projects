package com.zaalime.orders.service;

import com.zaalime.orders.common.RedisKeys;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemRequest;
import com.zaalime.orders.service.checkout.StockReservationService;
import com.zaalime.orders.service.checkout.StockReservationService.OutOfStock;
import com.zaalime.orders.service.checkout.StockReservationService.Reserved;
import com.zaalime.orders.service.checkout.StockReservationService.UnknownProduct;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestInfrastructure.class)
class StockReservationConcurrencyTest {

    @Autowired
    StockReservationService reservations;

    @Autowired
    StringRedisTemplate redis;

    @Test
    void thousandConcurrentBuyersNeverOversell() throws Exception {
        long productId = 9_001;
        redis.opsForValue().set(RedisKeys.stock(productId), "100");

        int buyers = 1_000;
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(64)) {
            for (int i = 0; i < buyers; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return reservations.reserve(List.of(new OrderItemRequest(productId, 1))) instanceof Reserved;
                }));
            }
            start.countDown();
            int sold = 0;
            for (Future<Boolean> r : results) {
                if (r.get()) {
                    sold++;
                }
            }
            assertThat(sold).isEqualTo(100);
        }
        assertThat(redis.opsForValue().get(RedisKeys.stock(productId))).isEqualTo("0");
    }

    @Test
    void multiItemReservationIsAllOrNothing() {
        redis.opsForValue().set(RedisKeys.stock(9_101), "5");
        redis.opsForValue().set(RedisKeys.stock(9_102), "1");

        var result = reservations.reserve(List.of(
                new OrderItemRequest(9_101, 2),
                new OrderItemRequest(9_102, 2)));

        assertThat(result).isInstanceOf(OutOfStock.class);
        assertThat(((OutOfStock) result).line().productId()).isEqualTo(9_102);
        // the first line must not have been touched
        assertThat(redis.opsForValue().get(RedisKeys.stock(9_101))).isEqualTo("5");
        assertThat(redis.opsForValue().get(RedisKeys.stock(9_102))).isEqualTo("1");
    }

    @Test
    void unknownProductIsReportedAndNothingIsReserved() {
        redis.opsForValue().set(RedisKeys.stock(9_201), "5");

        var result = reservations.reserve(List.of(
                new OrderItemRequest(9_201, 1),
                new OrderItemRequest(9_999_999, 1)));

        assertThat(result).isInstanceOf(UnknownProduct.class);
        assertThat(redis.opsForValue().get(RedisKeys.stock(9_201))).isEqualTo("5");
    }

    @Test
    void releaseGivesStockBack() {
        redis.opsForValue().set(RedisKeys.stock(9_301), "3");
        List<OrderItemRequest> lines = List.of(new OrderItemRequest(9_301, 3));

        assertThat(reservations.reserve(lines)).isInstanceOf(Reserved.class);
        assertThat(reservations.release(lines)).containsExactly(3L);
    }
}
