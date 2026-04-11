package com.crypto.dw.flink.model;

import java.math.BigDecimal;

/**
 * 期货价格数据模型
 */
public class FuturesPrice {
    public String symbol;
    public BigDecimal price;
    public long timestamp;
}
