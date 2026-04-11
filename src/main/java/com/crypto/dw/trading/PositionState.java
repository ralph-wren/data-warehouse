package com.crypto.dw.trading;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 持仓状态
 * 
 * 用于记录套利交易的持仓信息
 */
public class PositionState implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    /**
     * 交易对（如 BTC-USDT）
     */
    private String symbol;
    
    /**
     * 套利方向
     * - LONG_SPOT_SHORT_SWAP: 做多现货 + 做空合约
     * - SHORT_SPOT_LONG_SWAP: 做空现货 + 做多合约
     */
    private String direction;
    
    /**
     * 持仓金额（USDT）
     */
    private BigDecimal amount;
    
    /**
     * 开仓时的价差率（%）
     */
    private BigDecimal entrySpreadRate;
    
    /**
     * 开仓时的现货价格
     */
    private BigDecimal entrySpotPrice;
    
    /**
     * 开仓时的合约价格
     */
    private BigDecimal entrySwapPrice;
    
    /**
     * 开仓时间（毫秒时间戳）
     */
    private long openTime;
    
    /**
     * 现货订单ID
     */
    private String spotOrderId;
    
    /**
     * 合约订单ID
     */
    private String swapOrderId;
    
    /**
     * 是否持仓中
     */
    private boolean isOpen;
    
    /**
     * 未实现利润（USDT）
     */
    private BigDecimal unrealizedProfit;
    
    /**
     * 最后更新时间（毫秒时间戳）
     */
    private long lastUpdateTime;
    
    /**
     * 最后日志输出时间（毫秒时间戳）
     * 用于控制日志输出频率
     */
    private long lastLogTime;
    
    /**
     * 借币利息率（小时利率）
     * 仅在做空现货时有值,用于计算借币成本
     */
    private BigDecimal borrowInterestRate;
    
    /**
     * 开仓手续费（USDT）
     * 包含现货和合约的开仓手续费总和
     */
    private BigDecimal openFeeUsdt;
    
    // Getters and Setters
    
    public String getSymbol() {
        return symbol;
    }
    
    public void setSymbol(String symbol) {
        this.symbol = symbol;
    }
    
    public String getDirection() {
        return direction;
    }
    
    public void setDirection(String direction) {
        this.direction = direction;
    }
    
    public BigDecimal getAmount() {
        return amount;
    }
    
    public void setAmount(BigDecimal amount) {
        this.amount = amount;
    }
    
    public BigDecimal getEntrySpreadRate() {
        return entrySpreadRate;
    }
    
    public void setEntrySpreadRate(BigDecimal entrySpreadRate) {
        this.entrySpreadRate = entrySpreadRate;
    }
    
    public BigDecimal getEntrySpotPrice() {
        return entrySpotPrice;
    }
    
    public void setEntrySpotPrice(BigDecimal entrySpotPrice) {
        this.entrySpotPrice = entrySpotPrice;
    }
    
    public BigDecimal getEntrySwapPrice() {
        return entrySwapPrice;
    }
    
    public void setEntrySwapPrice(BigDecimal entrySwapPrice) {
        this.entrySwapPrice = entrySwapPrice;
    }
    
    public long getOpenTime() {
        return openTime;
    }
    
    public void setOpenTime(long openTime) {
        this.openTime = openTime;
    }
    
    public String getSpotOrderId() {
        return spotOrderId;
    }
    
    public void setSpotOrderId(String spotOrderId) {
        this.spotOrderId = spotOrderId;
    }
    
    public String getSwapOrderId() {
        return swapOrderId;
    }
    
    public void setSwapOrderId(String swapOrderId) {
        this.swapOrderId = swapOrderId;
    }
    
    public boolean isOpen() {
        return isOpen;
    }
    
    public void setOpen(boolean open) {
        isOpen = open;
    }
    
    public BigDecimal getUnrealizedProfit() {
        return unrealizedProfit;
    }
    
    public void setUnrealizedProfit(BigDecimal unrealizedProfit) {
        this.unrealizedProfit = unrealizedProfit;
    }
    
    public long getLastUpdateTime() {
        return lastUpdateTime;
    }
    
    public void setLastUpdateTime(long lastUpdateTime) {
        this.lastUpdateTime = lastUpdateTime;
    }
    
    public long getLastLogTime() {
        return lastLogTime;
    }
    
    public void setLastLogTime(long lastLogTime) {
        this.lastLogTime = lastLogTime;
    }
    
    public BigDecimal getBorrowInterestRate() {
        return borrowInterestRate;
    }
    
    public void setBorrowInterestRate(BigDecimal borrowInterestRate) {
        this.borrowInterestRate = borrowInterestRate;
    }
    
    public BigDecimal getOpenFeeUsdt() {
        return openFeeUsdt;
    }
    
    public void setOpenFeeUsdt(BigDecimal openFeeUsdt) {
        this.openFeeUsdt = openFeeUsdt;
    }
    
    @Override
    public String toString() {
        return "PositionState{" +
                "symbol='" + symbol + '\'' +
                ", direction='" + direction + '\'' +
                ", amount=" + amount +
                ", entrySpreadRate=" + entrySpreadRate +
                ", entrySpotPrice=" + entrySpotPrice +
                ", entrySwapPrice=" + entrySwapPrice +
                ", openTime=" + openTime +
                ", spotOrderId='" + spotOrderId + '\'' +
                ", swapOrderId='" + swapOrderId + '\'' +
                ", isOpen=" + isOpen +
                '}';
    }
}
