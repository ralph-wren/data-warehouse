package com.crypto.dw.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单更新
 */
public class OrderUpdate implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public String orderId;          // 订单ID
    public String symbol;           // 交易对
    public String instType;         // 产品类型(SPOT/SWAP)
    public String side;             // 买卖方向(buy/sell)
    public String state;            // 订单状态(filled/canceled)
    public BigDecimal fillPrice;    // 成交价格
    public BigDecimal fillSize;     // 成交数量
    public long timestamp;          // 时间戳
    
    public OrderUpdate() {
    }
}
