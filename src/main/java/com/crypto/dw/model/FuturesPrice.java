package com.crypto.dw.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 期货价格
 */
public class FuturesPrice implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public String symbol;
    public BigDecimal price;
    public long timestamp;
    
    public FuturesPrice() {
    }
    
    public FuturesPrice(String symbol, BigDecimal price, long timestamp) {
        this.symbol = symbol;
        this.price = price;
        this.timestamp = timestamp;
    }
}
