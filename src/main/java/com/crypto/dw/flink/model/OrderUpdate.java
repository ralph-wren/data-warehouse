package com.crypto.dw.flink.model;

import java.math.BigDecimal;

/**
 * 订单更新数据模型
 */
public class OrderUpdate {
    public String orderId;          // 订单ID
    public String symbol;           // 交易对
    public String instType;         // 产品类型(SPOT/SWAP)
    public String side;             // 买卖方向(buy/sell)
    public String state;            // 订单状态(filled/canceled)
    public BigDecimal fillPrice;    // 成交价格
    public BigDecimal fillSize;     // 成交数量
    public BigDecimal fee;          // 手续费金额
    public String feeCcy;           // 手续费币种
    public long timestamp;          // 时间戳
}
