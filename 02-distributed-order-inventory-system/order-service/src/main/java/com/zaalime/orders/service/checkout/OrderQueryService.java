package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import com.zaalime.orders.service.auth.AuthUser;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemView;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderView;
import com.zaalime.orders.service.web.ApiException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

@Service
public class OrderQueryService {

    private final OrderRecordRepository orders;
    private final PendingOrderStore pendingOrders;

    public OrderQueryService(OrderRecordRepository orders, PendingOrderStore pendingOrders) {
        this.orders = orders;
        this.pendingOrders = pendingOrders;
    }

    @Transactional(readOnly = true)
    public OrderView get(AuthUser user, UUID orderId) {
        return find(user, orderId).orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
    }

    /** Postgres first; if the worker has not got to it yet, the Redis snapshot. */
    @Transactional(readOnly = true)
    public Optional<OrderView> find(AuthUser user, UUID orderId) {
        Optional<OrderView> persisted = orders.findByIdAndUserId(orderId, user.id()).map(OrderQueryService::toView);
        if (persisted.isPresent()) {
            return persisted;
        }
        return pendingOrders.find(orderId)
                .filter(e -> e.userId() == user.id())
                .map(OrderQueryService::toView);
    }

    @Transactional(readOnly = true)
    public Page<OrderView> history(AuthUser user, Pageable pageable) {
        return orders.findByUserIdOrderByCreatedAtDesc(user.id(), pageable).map(OrderQueryService::toView);
    }

    private static OrderView toView(OrderRecord o) {
        return new OrderView(o.getId(), o.getStatus(), o.getTotalAmount(), o.getFailureReason(),
                o.getCreatedAt(), o.getUpdatedAt(),
                o.getItems().stream()
                        .map(i -> new OrderItemView(i.getProductId(), i.getProductName(), i.getQuantity(), i.getUnitPrice()))
                        .toList());
    }

    private static OrderView toView(OrderPlacedEvent e) {
        return new OrderView(e.orderId(), OrderStatus.PENDING, e.totalAmount(), null, e.placedAt(), e.placedAt(),
                e.lines().stream()
                        .map(l -> new OrderItemView(l.productId(), l.productName(), l.quantity(), l.unitPrice()))
                        .toList());
    }
}
