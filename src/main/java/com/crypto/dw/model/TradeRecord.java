package com.crypto.dw.model;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 交易记录
 * 
 * 用于记录套利交易的所有操作
 */
public class TradeRecord implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 交易对(如 BTC-USDT)
     */
    public String symbol;
    
    /**
     * 操作类型
     * - OPEN_PENDING: 下单等待
     * - OPEN_CONFIRMED: 开仓确认
     * - CLOSE: 平仓
     */
    public String action;
    
    /**
     * 套利方向
     * - LONG_SPOT_SHORT_SWAP: 做多现货 + 做空合约
     * - SHORT_SPOT_LONG_SWAP: 做空现货 + 做多合约
     */
    public String direction;
    
    /**
     * 交易金额(USDT)
     */
    public BigDecimal amount;
    
    /**
     * 价差率(%)
     */
    public BigDecimal spreadRate;
    
    /**
     * 现货价格
     */
    public BigDecimal spotPrice;
    
    /**
     * 合约价格
     */
    public BigDecimal swapPrice;
    
    /**
     * 盈亏(USDT),仅平仓时有值
     */
    public BigDecimal profit;
    
    /**
     * 平仓原因,如: 价差回归、止损、超时等
     */
    public String closeReason;
    
    /**
     * 持仓时间(毫秒),仅平仓时有值
     */
    public Long holdTimeMs;
    
    /**
     * 时间戳(毫秒)
     */
    public long timestamp;
    
    @Override
    public String toString() {
        return "TradeRecord{" +
                "symbol='" + symbol + '\'' +
                ", action='" + action + '\'' +
                ", direction='" + direction + '\'' +
                ", amount=" + amount +
                ", spreadRate=" + spreadRate +
                ", spotPrice=" + spotPrice +
                ", swapPrice=" + swapPrice +
                ", profit=" + profit +
                ", closeReason='" + closeReason + '\'' +
                ", holdTimeMs=" + holdTimeMs +
                ", timestamp=" + timestamp +
                '}';
    }
}
