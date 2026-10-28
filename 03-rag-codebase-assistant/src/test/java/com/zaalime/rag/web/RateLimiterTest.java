package com.zaalime.rag.web;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RateLimiterTest {

    @Test
    void allowsTheBudgetThenAsksToWait() {
        RateLimiter limiter = new RateLimiter(2);
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isZero();
        assertThat(limiter.tryAcquire("a")).isPositive();   // third call within the minute
        assertThat(limiter.tryAcquire("b")).isZero();       // other clients are unaffected
    }
}
