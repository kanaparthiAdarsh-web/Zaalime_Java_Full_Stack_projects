package com.zaalime.orders.worker;

import com.zaalime.orders.common.OrderStatus;
import com.zaalime.orders.common.event.OrderLine;
import com.zaalime.orders.common.event.OrderPlacedEvent;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Plain SQL on purpose: the conditional stock update is the whole point and should be visible. */
@Repository
public class OrderJdbcRepository {

    private final JdbcTemplate jdbc;

    public OrderJdbcRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<OrderStatus> findStatus(UUID orderId) {
        return jdbc.query("SELECT status FROM orders WHERE id = ?",
                        (rs, i) -> OrderStatus.valueOf(rs.getString(1)), orderId)
                .stream().findFirst();
    }

    /** @return false if a row with this id or (user, idempotency key) already exists */
    public boolean insertOrder(OrderPlacedEvent e, OrderStatus status, String failureReason) {
        int rows = jdbc.update("""
                        INSERT INTO orders (id, user_id, idempotency_key, status, total_amount, failure_reason, created_at, updated_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, now())
                        ON CONFLICT DO NOTHING
                        """,
                e.orderId(), e.userId(), e.idempotencyKey(), status.name(), e.totalAmount(), failureReason,
                Timestamp.from(e.placedAt()));
        return rows == 1;
    }

    public void insertItems(UUID orderId, List<OrderLine> lines) {
        jdbc.batchUpdate("""
                        INSERT INTO order_items (order_id, product_id, product_name, quantity, unit_price)
                        VALUES (?, ?, ?, ?, ?)
                        """,
                lines, lines.size(), (ps, line) -> {
                    ps.setObject(1, orderId);
                    ps.setLong(2, line.productId());
                    ps.setString(3, line.productName());
                    ps.setInt(4, line.quantity());
                    ps.setBigDecimal(5, line.unitPrice());
                });
    }

    /**
     * Decrements only if enough stock is left. The row lock taken by UPDATE serialises
     * concurrent workers on the same product, and the CHECK (quantity >= 0) constraint
     * backs it up at the schema level.
     *
     * @return remaining committed stock, or empty if there was not enough
     */
    public Optional<Integer> decrementStock(long productId, int quantity) {
        return jdbc.query("""
                        UPDATE inventory SET quantity = quantity - ?, updated_at = now()
                        WHERE product_id = ? AND quantity >= ?
                        RETURNING quantity
                        """,
                (rs, i) -> rs.getInt(1), quantity, productId, quantity).stream().findFirst();
    }

    public void updateStatus(UUID orderId, OrderStatus status) {
        jdbc.update("UPDATE orders SET status = ?, updated_at = now() WHERE id = ?", status.name(), orderId);
    }
}
