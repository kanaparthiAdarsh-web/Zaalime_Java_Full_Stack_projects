package com.zaalime.orders.service.checkout;

import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.event.InventoryEvent;
import com.zaalime.orders.common.event.OrderLine;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import com.zaalime.orders.common.event.OrderStatusEvent;
import com.zaalime.orders.service.auth.AuthUser;
import com.zaalime.orders.service.catalog.StockStore;
import com.zaalime.orders.service.catalog.StockStore.ProductSnapshot;
import com.zaalime.orders.service.checkout.CheckoutDtos.CheckoutResponse;
import com.zaalime.orders.service.checkout.CheckoutDtos.OrderItemRequest;
import com.zaalime.orders.service.checkout.IdempotencyStore.Entry;
import com.zaalime.orders.service.checkout.StockReservationService.OutOfStock;
import com.zaalime.orders.service.checkout.StockReservationService.Reserved;
import com.zaalime.orders.service.checkout.StockReservationService.UnknownProduct;
import com.zaalime.orders.service.config.AppProperties;
import com.zaalime.orders.service.realtime.EventPublisher;
import com.zaalime.orders.service.web.ApiException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * The hot path. A checkout touches only Redis and RabbitMQ:
 * <ol>
 *     <li>claim the Idempotency-Key</li>
 *     <li>atomically reserve stock for every line (Lua)</li>
 *     <li>publish the order to order-queue and wait for the broker confirm</li>
 * </ol>
 * Postgres is written later by the worker, at its own pace.
 */
@Service
public class CheckoutService {

    private static final Logger log = LoggerFactory.getLogger(CheckoutService.class);
    private static final Pattern KEY_FORMAT = Pattern.compile("[A-Za-z0-9_:.-]{8,100}");

    private final IdempotencyStore idempotency;
    private final StockReservationService reservations;
    private final StockStore stockStore;
    private final OrderQueuePublisher queue;
    private final PendingOrderStore pendingOrders;
    private final OrderQueryService orderQueries;
    private final EventPublisher events;
    private final AppProperties.Checkout limits;

    public CheckoutService(IdempotencyStore idempotency, StockReservationService reservations, StockStore stockStore,
                           OrderQueuePublisher queue, PendingOrderStore pendingOrders, OrderQueryService orderQueries,
                           EventPublisher events, AppProperties props) {
        this.idempotency = idempotency;
        this.reservations = reservations;
        this.stockStore = stockStore;
        this.queue = queue;
        this.pendingOrders = pendingOrders;
        this.orderQueries = orderQueries;
        this.events = events;
        this.limits = props.checkout();
    }

    public CheckoutResponse placeOrder(AuthUser user, String idempotencyKey, List<OrderItemRequest> items) {
        if (idempotencyKey == null || !KEY_FORMAT.matcher(idempotencyKey).matches()) {
            throw ApiException.badRequest("Idempotency-Key must be 8-100 characters of [A-Za-z0-9_:.-] (a UUID works)");
        }
        List<OrderItemRequest> lines = normalize(items);
        String fingerprint = fingerprint(lines);
        UUID orderId = UUID.randomUUID();

        var existing = idempotency.tryBegin(user.id(), idempotencyKey, fingerprint, orderId);
        if (existing.isPresent()) {
            return replay(user, existing.get(), fingerprint);
        }

        boolean settled = false;
        try {
            CheckoutResponse response = reserveAndEnqueue(user, idempotencyKey, fingerprint, orderId, lines);
            settled = true;
            return response;
        } catch (ApiException e) {
            settled = e.status() == HttpStatus.CONFLICT; // stock rejection was already recorded
            throw e;
        } finally {
            if (!settled) {
                idempotency.forget(user.id(), idempotencyKey);
            }
        }
    }

    /**
     * A retried cart checkout arrives after the cart was already cleared, so there is
     * nothing to fingerprint - answer from the stored key instead.
     */
    public Optional<CheckoutResponse> previousResult(AuthUser user, String idempotencyKey) {
        return idempotency.find(user.id(), idempotencyKey)
                .filter(e -> e.state() == IdempotencyStore.State.ACCEPTED)
                .map(e -> replay(user, e, e.requestHash()));
    }

    private CheckoutResponse reserveAndEnqueue(AuthUser user, String key, String fingerprint, UUID orderId,
                                               List<OrderItemRequest> lines) {
        List<ProductSnapshot> products = new ArrayList<>(lines.size());
        for (OrderItemRequest line : lines) {
            products.add(stockStore.snapshot(line.productId())
                    .orElseThrow(() -> ApiException.notFound("Product " + line.productId() + " not found")));
        }

        var result = reservations.reserve(lines);
        if (result instanceof UnknownProduct u) {
            throw ApiException.notFound("Product " + u.line().productId() + " is not available");
        }
        if (result instanceof OutOfStock o) {
            String name = products.get(lines.indexOf(o.line())).name();
            String detail = "Not enough stock for '" + name + "' (requested " + o.line().quantity() + ")";
            idempotency.markRejected(user.id(), key, fingerprint, detail);
            throw ApiException.conflict("Out of stock", detail);
        }
        List<Long> remaining = ((Reserved) result).remaining();

        List<OrderLine> orderLines = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            ProductSnapshot p = products.get(i);
            orderLines.add(new OrderLine(p.id(), p.name(), lines.get(i).quantity(), p.price()));
        }
        BigDecimal total = orderLines.stream().map(OrderLine::lineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        OrderPlacedEvent event = new OrderPlacedEvent(orderId, user.id(), user.username(), key, orderLines, total, Instant.now());

        try {
            pendingOrders.save(event);
            queue.publish(event);
        } catch (OrderQueuePublisher.ConfirmTimeoutException e) {
            // The message left this service but the broker never answered. Undoing the
            // reservation now could double-sell if the message did land, so we keep it and
            // treat the order as accepted; the worker is the one that finally decides.
            log.warn("{} - keeping the reservation", e.getMessage());
        } catch (RuntimeException e) {
            // never leave stock reserved for an order nobody will process
            log.error("Could not enqueue order {}, releasing reservation", orderId, e);
            safely(() -> reservations.release(lines));
            safely(() -> pendingOrders.delete(orderId));
            throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "Service unavailable",
                    "Order could not be queued right now, nothing was charged. Please retry with the same Idempotency-Key.");
        }

        idempotency.markAccepted(user.id(), key, fingerprint, orderId);

        events.publish(new OrderStatusEvent(orderId, user.username(), OrderStatus.PENDING, total, "Order queued", Instant.now()));
        for (int i = 0; i < orderLines.size(); i++) {
            OrderLine line = orderLines.get(i);
            events.publish(new InventoryEvent(line.productId(), line.productName(), remaining.get(i), null, "RESERVED", Instant.now()));
        }
        return new CheckoutResponse(orderId, OrderStatus.PENDING, total, "/api/orders/" + orderId, false);
    }

    private CheckoutResponse replay(AuthUser user, Entry entry, String fingerprint) {
        if (!entry.requestHash().equals(fingerprint)) {
            throw new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "Idempotency-Key reused",
                    "This Idempotency-Key was already used for a different order");
        }
        return switch (entry.state()) {
            case IN_PROGRESS -> throw ApiException.conflict("Request in progress",
                    "A request with this Idempotency-Key is still being processed");
            case REJECTED -> throw ApiException.conflict("Out of stock", entry.detail());
            case ACCEPTED -> orderQueries.find(user, entry.orderId())
                    .map(o -> new CheckoutResponse(o.id(), o.status(), o.totalAmount(), "/api/orders/" + o.id(), true))
                    .orElseGet(() -> new CheckoutResponse(entry.orderId(), OrderStatus.PENDING, null,
                            "/api/orders/" + entry.orderId(), true));
        };
    }

    /** Merges duplicate product lines and sorts by product id, so equal carts produce equal fingerprints. */
    private List<OrderItemRequest> normalize(List<OrderItemRequest> items) {
        if (items == null || items.isEmpty()) {
            throw ApiException.badRequest("Order must contain at least one item");
        }
        Map<Long, Integer> merged = items.stream().collect(Collectors.toMap(
                OrderItemRequest::productId, OrderItemRequest::quantity, Integer::sum, TreeMap::new));
        if (merged.size() > limits.maxLines()) {
            throw ApiException.badRequest("An order can contain at most " + limits.maxLines() + " different products");
        }
        merged.forEach((productId, qty) -> {
            if (qty < 1 || qty > limits.maxQuantity()) {
                throw ApiException.badRequest("Quantity for product " + productId + " must be between 1 and " + limits.maxQuantity());
            }
        });
        return merged.entrySet().stream().map(e -> new OrderItemRequest(e.getKey(), e.getValue())).toList();
    }

    private static String fingerprint(List<OrderItemRequest> lines) {
        String canonical = lines.stream().map(l -> l.productId() + "x" + l.quantity()).collect(Collectors.joining(","));
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static void safely(Runnable action) {
        try {
            action.run();
        } catch (RuntimeException e) {
            log.error("Compensation step failed", e);
        }
    }
}
