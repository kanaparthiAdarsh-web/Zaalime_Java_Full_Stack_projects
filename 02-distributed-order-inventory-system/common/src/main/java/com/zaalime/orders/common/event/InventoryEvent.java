package com.zaalime.orders.common.event;

import java.time.Instant;

/**
 * A product's stock level changed.
 *
 * @param availableStock live stock in Redis (what customers can still buy)
 * @param committedStock stock in Postgres after the last committed order, null when unknown
 * @param reason         RESERVED, RELEASED, COMMITTED, RESTOCKED or CREATED
 */
public record InventoryEvent(
        long productId,
        String productName,
        long availableStock,
        Long committedStock,
        String reason,
        Instant timestamp) {
}
