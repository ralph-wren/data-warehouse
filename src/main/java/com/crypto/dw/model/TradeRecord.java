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

    /**
     * 事件类型：DISCOVER/OPEN_PENDING/OPENED/POSITION_STATUS/CLOSE_PENDING/CLOSED/REJECTED/ERROR
     */
    public String eventType;

    /**
     * 事件阶段：DISCOVERY/ORDER/POSITION/CLOSE/SUMMARY
     */
    public String eventStage;

    /**
     * 持仓状态：OPEN/CLOSED/N/A
     */
    public String positionStatus;

    /**
     * 现货/合约产品ID
     */
    public String spotInstId;
    public String swapInstId;

    /**
     * 发现阶段行情
     */
    public BigDecimal discoverSpotPrice;
    public BigDecimal discoverSwapPrice;
    public BigDecimal discoverSpread;
    public BigDecimal discoverSpreadRate;
    public BigDecimal unitProfitEstimate;
    public BigDecimal discoverProfitEstimate;

    /**
     * 配置快照
     */
    public Boolean tradingEnabled;
    public BigDecimal tradeAmountUsdt;
    public BigDecimal openThreshold;
    public BigDecimal closeThreshold;
    public Integer maxHoldTimeMinutes;
    public BigDecimal maxLossUsdt;
    public Integer leverageConfig;

    /**
     * 订单与成交明细
     */
    public String spotOrderId;
    public String swapOrderId;
    public String spotOrderType;
    public String swapOrderType;
    public String spotOrderSide;
    public String swapOrderSide;
    public String spotPosSide;
    public String swapPosSide;
    public String spotOrderState;
    public String swapOrderState;
    public BigDecimal orderSpotPrice;
    public BigDecimal orderSwapPrice;
    public BigDecimal entrySpreadRate;
    public BigDecimal actualSpotPrice;
    public BigDecimal actualSwapPrice;
    public BigDecimal actualSpotFilledQty;
    public BigDecimal actualSwapFilledContracts;
    public BigDecimal actualSwapFilledCoin;
    public BigDecimal ctVal;

    /**
     * 成本与费用
     */
    public BigDecimal amountCoin;
    public BigDecimal amountUsdt;
    public BigDecimal spotCost;
    public BigDecimal swapCost;
    public BigDecimal totalCost;
    public BigDecimal spotFee;
    public String spotFeeCcy;
    public BigDecimal swapFee;
    public String swapFeeCcy;
    public BigDecimal totalFee;
    public BigDecimal totalExpense;

    /**
     * 持仓状态
     */
    public Long holdTimeSeconds;
    public BigDecimal currentSpotPrice;
    public BigDecimal currentSwapPrice;
    public BigDecimal currentSpread;
    public BigDecimal currentSpreadRate;
    public BigDecimal hedgedCoinQty;
    public BigDecimal unhedgedCoinQty;
    public BigDecimal unrealizedProfit;
    public BigDecimal detailedProfitRate;

    /**
     * 跟踪器状态
     */
    public Boolean trackerActive;
    public BigDecimal trackerSpreadRate;
    public Long trackerDurationSeconds;

    /**
     * 平仓结果
     */
    public BigDecimal closeSpotPrice;
    public BigDecimal closeSwapPrice;
    public BigDecimal closeSpotFee;
    public BigDecimal closeSwapFee;
    public BigDecimal realizedProfit;
    public BigDecimal realizedProfitRate;

    /**
     * 错误与日志摘要
     */
    public String errorCode;
    public String errorMessage;
    public String statusMessage;
    public String logSource;
    public String rawPayloadJson;
    public String extJson;
    
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
