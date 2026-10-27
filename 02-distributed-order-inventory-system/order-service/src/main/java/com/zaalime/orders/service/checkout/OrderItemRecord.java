package com.zaalime.orders.service.checkout;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.math.BigDecimal;

@Entity
@Immutable
@Table(name = "order_items")
public class OrderItemRecord {

    @Id
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "order_id")
    private OrderRecord order;

    @Column(name = "product_id")
    private long productId;

    @Column(name = "product_name")
    private String productName;

    private int quantity;

    @Column(name = "unit_price")
    private BigDecimal unitPrice;

    protected OrderItemRecord() {
    }

    public long getProductId() {
        return productId;
    }

    public String getProductName() {
        return productName;
    }

    public int getQuantity() {
        return quantity;
    }

    public BigDecimal getUnitPrice() {
        return unitPrice;
    }
}
