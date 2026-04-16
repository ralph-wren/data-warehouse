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
     * @deprecated 使用 spotSize 和 contractSize 代替
     */
    @Deprecated
    private BigDecimal amount;
    
    /**
     * 现货数量（币的数量）
     * 例如: 5 BTC, 100 ETH
     */
    private BigDecimal spotSize;
    
    /**
     * 合约张数
     * 例如: 50 张, 100 张
     */
    private BigDecimal contractSize;
    
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
    
    // ⭐ 新增: 实际成交价格和成本字段（用于精确计算利润）
    
    /**
     * 现货实际成交价格（从订单详情获取）
     */
    private BigDecimal actualSpotPrice;
    
    /**
     * 合约实际成交价格（从订单详情获取）
     */
    private BigDecimal actualSwapPrice;
    
    /**
     * 现货成本（USDT）= 实际成交价 × 成交数量
     */
    private BigDecimal spotCost;

    /**
     * 现货实际成交数量（币）
     * 说明：与 amount（目标下单数量）区分，避免部分成交时计算失真
     */
    private BigDecimal actualSpotFilledQuantity;
    
    /**
     * 合约成本（USDT）= 实际成交价 × 成交数量
     */
    private BigDecimal futuresCost;

    /**
     * 合约实际成交数量（张）
     * 说明：OKX 合约成交量 accFillSz 单位是“张”
     */
    private BigDecimal actualSwapFilledContracts;

    /**
     * 合约实际成交数量折算为币数量
     * 计算公式：actualSwapFilledContracts × ctVal
     */
    private BigDecimal actualSwapFilledCoin;
    
    /**
     * 总成本（USDT）= 现货成本 + 合约成本
     */
    private BigDecimal totalCost;
    
    /**
     * 现货手续费（USDT）
     */
    private BigDecimal spotFee;

    /**
     * 现货手续费币种
     */
    private String spotFeeCurrency;
    
    /**
     * 合约手续费（USDT）
     */
    private BigDecimal futuresFee;

    /**
     * 合约手续费币种
     */
    private String futuresFeeCurrency;
    
    /**
     * 总手续费（USDT）= 现货手续费 + 合约手续费
     */
    private BigDecimal totalFee;
    
    /**
     * 总费用（USDT）= 总成本 + 总手续费
     */
    private BigDecimal totalExpense;

    /**
     * 合约规格信息
     */
    private BigDecimal ctVal;
    private BigDecimal lotSz;
    private BigDecimal minSz;
    private Integer instrumentMaxLeverage;

    /**
     * 订单维度字段（来自 OKX 订单详情）
     */
    private String spotOrderType;
    private String spotOrderSide;
    private String spotPosSide;
    private String spotOrderState;
    private String spotTdMode;

    private String swapOrderType;
    private String swapOrderSide;
    private String swapPosSide;
    private String swapOrderState;
    private String swapTdMode;

    /**
     * 最近一次查询到的订单详情原始 JSON（裁剪后）
     * 用于在 Doris 宽表中排障追溯
     */
    private String lastOrderDetailsRawJson;
    
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
    
    public BigDecimal getSpotSize() {
        return spotSize;
    }
    
    public void setSpotSize(BigDecimal spotSize) {
        this.spotSize = spotSize;
    }
    
    public BigDecimal getContractSize() {
        return contractSize;
    }
    
    public void setContractSize(BigDecimal contractSize) {
        this.contractSize = contractSize;
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
    
    // ⭐ 新增字段的 Getters 和 Setters
    
    public BigDecimal getActualSpotPrice() {
        return actualSpotPrice;
    }
    
    public void setActualSpotPrice(BigDecimal actualSpotPrice) {
        this.actualSpotPrice = actualSpotPrice;
    }
    
    public BigDecimal getActualSwapPrice() {
        return actualSwapPrice;
    }
    
    public void setActualSwapPrice(BigDecimal actualSwapPrice) {
        this.actualSwapPrice = actualSwapPrice;
    }
    
    public BigDecimal getSpotCost() {
        return spotCost;
    }
    
    public void setSpotCost(BigDecimal spotCost) {
        this.spotCost = spotCost;
    }

    public BigDecimal getActualSpotFilledQuantity() {
        return actualSpotFilledQuantity;
    }

    public void setActualSpotFilledQuantity(BigDecimal actualSpotFilledQuantity) {
        this.actualSpotFilledQuantity = actualSpotFilledQuantity;
    }
    
    public BigDecimal getFuturesCost() {
        return futuresCost;
    }
    
    public void setFuturesCost(BigDecimal futuresCost) {
        this.futuresCost = futuresCost;
    }

    public BigDecimal getActualSwapFilledContracts() {
        return actualSwapFilledContracts;
    }

    public void setActualSwapFilledContracts(BigDecimal actualSwapFilledContracts) {
        this.actualSwapFilledContracts = actualSwapFilledContracts;
    }

    public BigDecimal getActualSwapFilledCoin() {
        return actualSwapFilledCoin;
    }

    public void setActualSwapFilledCoin(BigDecimal actualSwapFilledCoin) {
        this.actualSwapFilledCoin = actualSwapFilledCoin;
    }
    
    public BigDecimal getTotalCost() {
        return totalCost;
    }
    
    public void setTotalCost(BigDecimal totalCost) {
        this.totalCost = totalCost;
    }
    
    public BigDecimal getSpotFee() {
        return spotFee;
    }
    
    public void setSpotFee(BigDecimal spotFee) {
        this.spotFee = spotFee;
    }

    public String getSpotFeeCurrency() {
        return spotFeeCurrency;
    }

    public void setSpotFeeCurrency(String spotFeeCurrency) {
        this.spotFeeCurrency = spotFeeCurrency;
    }
    
    public BigDecimal getFuturesFee() {
        return futuresFee;
    }
    
    public void setFuturesFee(BigDecimal futuresFee) {
        this.futuresFee = futuresFee;
    }

    public String getFuturesFeeCurrency() {
        return futuresFeeCurrency;
    }

    public void setFuturesFeeCurrency(String futuresFeeCurrency) {
        this.futuresFeeCurrency = futuresFeeCurrency;
    }
    
    public BigDecimal getTotalFee() {
        return totalFee;
    }
    
    public void setTotalFee(BigDecimal totalFee) {
        this.totalFee = totalFee;
    }
    
    public BigDecimal getTotalExpense() {
        return totalExpense;
    }
    
    public void setTotalExpense(BigDecimal totalExpense) {
        this.totalExpense = totalExpense;
    }

    public BigDecimal getCtVal() {
        return ctVal;
    }

    public void setCtVal(BigDecimal ctVal) {
        this.ctVal = ctVal;
    }

    public BigDecimal getLotSz() {
        return lotSz;
    }

    public void setLotSz(BigDecimal lotSz) {
        this.lotSz = lotSz;
    }

    public BigDecimal getMinSz() {
        return minSz;
    }

    public void setMinSz(BigDecimal minSz) {
        this.minSz = minSz;
    }

    public Integer getInstrumentMaxLeverage() {
        return instrumentMaxLeverage;
    }

    public void setInstrumentMaxLeverage(Integer instrumentMaxLeverage) {
        this.instrumentMaxLeverage = instrumentMaxLeverage;
    }

    public String getSpotOrderType() {
        return spotOrderType;
    }

    public void setSpotOrderType(String spotOrderType) {
        this.spotOrderType = spotOrderType;
    }

    public String getSpotOrderSide() {
        return spotOrderSide;
    }

    public void setSpotOrderSide(String spotOrderSide) {
        this.spotOrderSide = spotOrderSide;
    }

    public String getSpotPosSide() {
        return spotPosSide;
    }

    public void setSpotPosSide(String spotPosSide) {
        this.spotPosSide = spotPosSide;
    }

    public String getSpotOrderState() {
        return spotOrderState;
    }

    public void setSpotOrderState(String spotOrderState) {
        this.spotOrderState = spotOrderState;
    }

    public String getSpotTdMode() {
        return spotTdMode;
    }

    public void setSpotTdMode(String spotTdMode) {
        this.spotTdMode = spotTdMode;
    }

    public String getSwapOrderType() {
        return swapOrderType;
    }

    public void setSwapOrderType(String swapOrderType) {
        this.swapOrderType = swapOrderType;
    }

    public String getSwapOrderSide() {
        return swapOrderSide;
    }

    public void setSwapOrderSide(String swapOrderSide) {
        this.swapOrderSide = swapOrderSide;
    }

    public String getSwapPosSide() {
        return swapPosSide;
    }

    public void setSwapPosSide(String swapPosSide) {
        this.swapPosSide = swapPosSide;
    }

    public String getSwapOrderState() {
        return swapOrderState;
    }

    public void setSwapOrderState(String swapOrderState) {
        this.swapOrderState = swapOrderState;
    }

    public String getSwapTdMode() {
        return swapTdMode;
    }

    public void setSwapTdMode(String swapTdMode) {
        this.swapTdMode = swapTdMode;
    }

    public String getLastOrderDetailsRawJson() {
        return lastOrderDetailsRawJson;
    }

    public void setLastOrderDetailsRawJson(String lastOrderDetailsRawJson) {
        this.lastOrderDetailsRawJson = lastOrderDetailsRawJson;
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
