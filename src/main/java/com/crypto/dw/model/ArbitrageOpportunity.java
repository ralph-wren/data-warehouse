package com.crypto.dw.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 套利机会
 */
public class ArbitrageOpportunity implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public String symbol;
    public BigDecimal spotPrice;
    public BigDecimal futuresPrice;
    public BigDecimal spread;  // 价差
    public BigDecimal spreadRate;  // 价差率（%）
    public String arbitrageDirection;  // 套利方向
    public BigDecimal profitEstimate;  // 预估利润
    public long timestamp;
    
    public ArbitrageOpportunity() {
    }
}
