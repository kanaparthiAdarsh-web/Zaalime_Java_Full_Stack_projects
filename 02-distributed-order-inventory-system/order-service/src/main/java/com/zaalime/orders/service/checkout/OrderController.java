package com.zaalime.orders.service.checkout;

import com.zaalime.orders.service.auth.AuthUser;
import com.zaalime.orders.service.checkout.CheckoutDtos.CheckoutResponse;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderView;
import com.zaalime.orders.service.checkout.CheckoutDtos.PlaceOrderRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final CheckoutService checkout;
    private final OrderQueryService queries;

    public OrderController(CheckoutService checkout, OrderQueryService queries) {
        this.checkout = checkout;
        this.queries = queries;
    }

    /** Direct checkout with an explicit item list (what the load test uses). */
    @PostMapping
    public ResponseEntity<CheckoutResponse> placeOrder(@AuthenticationPrincipal Jwt jwt,
                                                       @RequestHeader(IDEMPOTENCY_HEADER) String idempotencyKey,
                                                       @Valid @RequestBody PlaceOrderRequest request) {
        CheckoutResponse response = checkout.placeOrder(AuthUser.from(jwt), idempotencyKey, request.items());
        return toResponse(response);
    }

    @GetMapping
    public Page<OrderView> history(@AuthenticationPrincipal Jwt jwt,
                                   @RequestParam(defaultValue = "0") int page,
                                   @RequestParam(defaultValue = "20") int size) {
        PageRequest pageRequest = PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100));
        return queries.history(AuthUser.from(jwt), pageRequest);
    }

    @GetMapping("/{orderId}")
    public OrderView get(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID orderId) {
        return queries.get(AuthUser.from(jwt), orderId);
    }

    public static ResponseEntity<CheckoutResponse> toResponse(CheckoutResponse response) {
        if (response.replayed()) {
            return ResponseEntity.ok().header("Idempotent-Replayed", "true").body(response);
        }
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create(response.statusUrl()))
                .body(response);
    }
}
