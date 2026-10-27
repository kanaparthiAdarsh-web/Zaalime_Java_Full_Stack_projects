package com.engine.risk;

import com.engine.model.OrderType;

public class RiskValidator {
    private final long maxOrderQuantity;
    private final long maxPriceTicks;

    public RiskValidator(long maxOrderQuantity, long maxPriceTicks) {
        this.maxOrderQuantity = maxOrderQuantity;
        this.maxPriceTicks = maxPriceTicks;
    }

    public boolean validate(long orderId, OrderType orderType, long priceTicks, long quantity) {
        if (orderId <= 0) 
            return false;
        
        if (orderType == null)
            return false;
        
        if (quantity <= 0 || quantity > maxOrderQuantity)
            return false;
        
        if (priceTicks <= 0 || priceTicks > maxPriceTicks) 
            return false;
        
        return true;
    }
}