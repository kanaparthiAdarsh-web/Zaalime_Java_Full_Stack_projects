package com.zaalime.orders.service.cart;

import com.zaalime.orders.service.auth.AuthUser;
import com.zaalime.orders.service.cart.CartDtos.CartView;
import com.zaalime.orders.service.cart.CartDtos.SetQuantityRequest;
import com.zaalime.orders.service.checkout.CheckoutDtos.CheckoutResponse;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemRequest;
import com.zaalime.orders.service.checkout.CheckoutService;
import com.zaalime.orders.service.checkout.OrderController;
import com.zaalime.orders.service.web.ApiException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/cart")
public class CartController {

    private final CartService carts;
    private final CheckoutService checkout;

    public CartController(CartService carts, CheckoutService checkout) {
        this.carts = carts;
        this.checkout = checkout;
    }

    @GetMapping
    public CartView view(@AuthenticationPrincipal Jwt jwt) {
        return carts.view(AuthUser.from(jwt).id());
    }

    /** Sets the quantity of one product; 0 removes it. */
    @PutMapping("/items/{productId}")
    public CartView setQuantity(@AuthenticationPrincipal Jwt jwt, @PathVariable long productId,
                                @Valid @RequestBody SetQuantityRequest request) {
        return carts.setQuantity(AuthUser.from(jwt).id(), productId, request.quantity());
    }

    @DeleteMapping("/items/{productId}")
    public CartView remove(@AuthenticationPrincipal Jwt jwt, @PathVariable long productId) {
        return carts.setQuantity(AuthUser.from(jwt).id(), productId, 0);
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void clear(@AuthenticationPrincipal Jwt jwt) {
        carts.clear(AuthUser.from(jwt).id());
    }

    @PostMapping("/checkout")
    public ResponseEntity<CheckoutResponse> checkout(@AuthenticationPrincipal Jwt jwt,
                                                     @RequestHeader(OrderController.IDEMPOTENCY_HEADER) String idempotencyKey) {
        AuthUser user = AuthUser.from(jwt);
        List<OrderItemRequest> items = carts.itemsForCheckout(user.id());
        if (items.isEmpty()) {
            return checkout.previousResult(user, idempotencyKey)
                    .map(OrderController::toResponse)
                    .orElseThrow(() -> ApiException.badRequest("Cart is empty"));
        }
        CheckoutResponse response = checkout.placeOrder(user, idempotencyKey, items);
        carts.clear(user.id());
        return OrderController.toResponse(response);
    }
}
