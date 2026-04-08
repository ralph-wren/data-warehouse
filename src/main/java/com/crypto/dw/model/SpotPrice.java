package com.crypto.dw.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 现货价格
 */
public class SpotPrice implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public String symbol;
    public BigDecimal price;
    public long timestamp;
    
    public SpotPrice() {
    }
    
    public SpotPrice(String symbol, BigDecimal price, long timestamp) {
        this.symbol = symbol;
        this.price = price;
        this.timestamp = timestamp;
    }
}
