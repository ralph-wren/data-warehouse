package com.crypto.dw.flink.model;

import java.math.BigDecimal;

/**
 * 套利机会数据模型
 */
public class ArbitrageOpportunity {
    public String symbol;
    public BigDecimal spotPrice;
    public BigDecimal futuresPrice;
    public BigDecimal spread;  // 价差
    public BigDecimal spreadRate;  // 价差率（%）
    public String arbitrageDirection;  // 套利方向
    public BigDecimal profitEstimate;  // 预估利润
    public long timestamp;
}
