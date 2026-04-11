package com.crypto.dw.flink.model;

import java.math.BigDecimal;

/**
 * 待确认订单数据模型
 */
public class PendingOrder implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    
    public String symbol;
    public String action;           // OPEN/CLOSE
    public String spotOrderId;
    public String swapOrderId;
    public boolean spotFilled;
    public boolean swapFilled;
    public BigDecimal spotFillPrice;
    public BigDecimal swapFillPrice;
    public BigDecimal spotFillSize;     // 现货成交数量
    public BigDecimal swapFillSize;     // 合约成交数量
    public BigDecimal spotFee;          // 现货手续费
    public String spotFeeCcy;           // 现货手续费币种
    public BigDecimal swapFee;          // 合约手续费
    public String swapFeeCcy;           // 合约手续费币种
    public long createTime;
}
