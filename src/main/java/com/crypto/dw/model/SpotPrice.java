package com.crypto.dw.model;

import java.math.BigDecimal;

/**
 * 现货价格数据模型
 */
public class SpotPrice {
    public String symbol;
    public BigDecimal price;
    public long timestamp;
}
