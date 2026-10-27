package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import com.zaalime.orders.service.auth.AuthUser;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemView;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderView;
import com.zaalime.orders.service.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

@Service
public class OrderQueryService {

    private static final Logger log = LoggerFactory.getLogger(OrderQueryService.class);

    private final OrderRecordRepository orders;
    private final PendingOrderStore pendingOrders;
    private final TransactionTemplate readOnlyTx;

    public OrderQueryService(OrderRecordRepository orders, PendingOrderStore pendingOrders,
                             PlatformTransactionManager txManager) {
        this.orders = orders;
        this.pendingOrders = pendingOrders;
        this.readOnlyTx = new TransactionTemplate(txManager);
        this.readOnlyTx.setReadOnly(true);
    }

    public OrderView get(AuthUser user, UUID orderId) {
        return find(user, orderId).orElseThrow(() -> ApiException.notFound("Order " + orderId + " not found"));
    }

    /**
     * Postgres first; if the worker has not got to it yet - or Postgres is down -
     * the Redis snapshot, so a just-placed order can always be looked up.
     */
    public Optional<OrderView> find(AuthUser user, UUID orderId) {
        try {
            Optional<OrderView> persisted = readOnlyTx.execute(tx ->
                    orders.findByIdAndUserId(orderId, user.id()).map(OrderQueryService::toView));
            if (persisted != null && persisted.isPresent()) {
                return persisted;
            }
        } catch (DataAccessException | TransactionException e) {
            log.warn("Order lookup in Postgres failed, trying Redis: {}", e.getMessage());
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
