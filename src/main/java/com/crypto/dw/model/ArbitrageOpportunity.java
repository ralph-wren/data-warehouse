package com.crypto.dw.model;

import java.math.BigDecimal;
import com.crypto.dw.utils.SpreadRateUtils;

/**
 * 套利机会数据模型
 * 
 * 包含两个阶段的数据:
 * 1. 发现阶段: 套利机会的基本信息(价差、价差率等)
 * 2. 开仓阶段: 实际成交信息(成交价、成交量、手续费等)
 */
public class ArbitrageOpportunity {
    // ========== 发现阶段: 套利机会基本信息 ==========
    public String symbol;                    // 交易对符号(如 BTC)
    public BigDecimal spotPrice;             // 现货价格(发现时)
    public BigDecimal futuresPrice;          // 合约价格(发现时)
    public BigDecimal spread;                // 价差(合约价格 - 现货价格)
    public BigDecimal spreadRate;            // 价差率(%)
    public String arbitrageDirection;        // 套利方向(做多现货/做空合约 或 做空现货/做多合约)
    public BigDecimal unitProfitEstimate;    // 单位预估利润
    public BigDecimal profitEstimate;        // 预估利润(发现时)
    public long timestamp;                   // 发现时间戳
    
    // ========== 开仓阶段: 现货交易明细 ==========
    public String spotOrderId;               // 现货订单ID
    public BigDecimal spotFillPrice;         // 现货成交价格(实际成交价)
    public BigDecimal spotFillQuantity;      // 现货成交数量(实际成交量)
    public BigDecimal spotFee;               // 现货手续费金额
    public String spotFeeCurrency;           // 现货手续费币种(如 USDT 或 BTC)
    public BigDecimal spotCost;              // 现货成本(成交价 × 成交量)
    
    // ========== 开仓阶段: 合约交易明细 ==========
    public String futuresOrderId;            // 合约订单ID
    public BigDecimal futuresFillPrice;      // 合约成交价格(实际成交价)
    public BigDecimal futuresFillQuantity;   // 合约成交数量(实际成交量,单位:张)
    public BigDecimal futuresFee;            // 合约手续费金额
    public String futuresFeeCurrency;        // 合约手续费币种(如 USDT)
    public BigDecimal futuresCost;           // 合约成本(成交价 × 成交量 × 合约面值)
    
    // ========== 开仓阶段: 总成本和费用 ==========
    public BigDecimal totalCost;             // 总成本(现货成本 + 合约成本)
    public BigDecimal totalFee;              // 总手续费(现货手续费 + 合约手续费,统一转换为 USDT)
    public BigDecimal totalExpense;          // 总费用(总成本 + 总手续费)
    
    // ========== 实时计算: 预估利润(基于实时价格) ==========
    public BigDecimal currentSpotPrice;      // 当前现货价格(实时)
    public BigDecimal currentFuturesPrice;   // 当前合约价格(实时)
    public BigDecimal currentSpread;         // 当前价差(实时)
    public BigDecimal currentSpreadRate;     // 当前价差率(%,实时)
    public BigDecimal estimatedProfit;       // 预估利润(基于实时价格计算)
    public BigDecimal estimatedProfitRate;   // 预估利润率(%,预估利润 / 总费用)
    public long updateTimestamp;             // 更新时间戳(实时价格更新时间)
    
    /**
     * 计算总成本
     * 总成本 = 现货成本 + 合约成本
     */
    public void calculateTotalCost() {
        if (spotCost != null && futuresCost != null) {
            this.totalCost = spotCost.add(futuresCost);
        }
    }
    
    /**
     * 计算总手续费
     * 总手续费 = 现货手续费 + 合约手续费
     * 注意: 需要将不同币种的手续费统一转换为 USDT
     */
    public void calculateTotalFee() {
        if (spotFee != null && futuresFee != null) {
            // 简化处理: 假设手续费都是 USDT
            // 实际应该根据 spotFeeCurrency 和 futuresFeeCurrency 进行转换
            this.totalFee = spotFee.add(futuresFee);
        }
    }
    
    /**
     * 计算总费用
     * 总费用 = 总成本 + 总手续费
     */
    public void calculateTotalExpense() {
        if (totalCost != null && totalFee != null) {
            this.totalExpense = totalCost.add(totalFee);
        }
    }
    
    /**
     * 计算预估利润(基于实时价格)
     * 
     * 策略A(做空现货/做多合约):
     *   预估利润 = (当前合约价 - 开仓合约价) × 合约数量 - (开仓现货价 - 当前现货价) × 现货数量 - 总手续费
     * 
     * 策略B(做多现货/做空合约):
     *   预估利润 = (当前现货价 - 开仓现货价) × 现货数量 - (开仓合约价 - 当前合约价) × 合约数量 - 总手续费
     * 
     * @param currentSpotPrice 当前现货价格
     * @param currentFuturesPrice 当前合约价格
     */
    public void calculateEstimatedProfit(BigDecimal currentSpotPrice, BigDecimal currentFuturesPrice) {
        if (spotFillPrice == null || spotFillQuantity == null || 
            futuresFillPrice == null || futuresFillQuantity == null || 
            totalFee == null) {
            return;
        }
        
        this.currentSpotPrice = currentSpotPrice;
        this.currentFuturesPrice = currentFuturesPrice;
        
        // 计算当前价差
        this.currentSpread = currentFuturesPrice.subtract(currentSpotPrice);
        
        // 统一价差率口径：|swap-spot| / min(spot,swap)
        this.currentSpreadRate = SpreadRateUtils.calculateSpreadRatePercent(
            currentSpotPrice,
            currentFuturesPrice,
            6
        );
        
        // 根据套利方向计算预估利润
        if (arbitrageDirection != null && arbitrageDirection.contains("做空现货")) {
            // 策略A: 做空现货/做多合约
            // 现货盈亏 = (开仓价 - 当前价) × 数量 (做空,价格下跌盈利)
            BigDecimal spotPnL = spotFillPrice.subtract(currentSpotPrice).multiply(spotFillQuantity);
            
            // 合约盈亏 = (当前价 - 开仓价) × 数量 (做多,价格上涨盈利)
            BigDecimal futuresPnL = currentFuturesPrice.subtract(futuresFillPrice).multiply(futuresFillQuantity);
            
            // 预估利润 = 现货盈亏 + 合约盈亏 - 总手续费
            this.estimatedProfit = spotPnL.add(futuresPnL).subtract(totalFee);
            
        } else {
            // 策略B: 做多现货/做空合约
            // 现货盈亏 = (当前价 - 开仓价) × 数量 (做多,价格上涨盈利)
            BigDecimal spotPnL = currentSpotPrice.subtract(spotFillPrice).multiply(spotFillQuantity);
            
            // 合约盈亏 = (开仓价 - 当前价) × 数量 (做空,价格下跌盈利)
            BigDecimal futuresPnL = futuresFillPrice.subtract(currentFuturesPrice).multiply(futuresFillQuantity);
            
            // 预估利润 = 现货盈亏 + 合约盈亏 - 总手续费
            this.estimatedProfit = spotPnL.add(futuresPnL).subtract(totalFee);
        }
        
        // 计算预估利润率
        if (totalExpense != null && totalExpense.compareTo(BigDecimal.ZERO) > 0) {
            this.estimatedProfitRate = estimatedProfit.divide(
                totalExpense, 
                6, 
                BigDecimal.ROUND_HALF_UP
            ).multiply(new BigDecimal("100"));
        }
        
        // 更新时间戳
        this.updateTimestamp = System.currentTimeMillis();
    }
}
