package com.crypto.dw.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 待确认订单
 */
public class PendingOrder implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    public String symbol;
    public String action;           // OPEN/CLOSE
    public String spotOrderId;
    public String swapOrderId;
    public boolean spotFilled;
    public boolean swapFilled;
    public BigDecimal spotFillPrice;
    public BigDecimal swapFillPrice;
    public long createTime;
    
    public PendingOrder() {
    }
}
