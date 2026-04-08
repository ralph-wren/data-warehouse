package com.crypto.dw.processor;

import com.crypto.dw.model.ArbitrageOpportunity;
import com.crypto.dw.model.FuturesPrice;
import com.crypto.dw.model.SpotPrice;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.util.Collector;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 套利机会计算器 - Interval Join 处理函数
 * 
 * 功能：
 * 1. 接收现货和期货价格
 * 2. 计算价差和价差率
 * 3. 判断套利方向
 * 4. 估算利润
 * 
 * 性能优化：
 * - 使用 Interval Join 而非 Window Join
 * - 减少状态存储
 * - 提高吞吐量
 */
public class ArbitrageCalculator 
        extends ProcessJoinFunction<SpotPrice, FuturesPrice, ArbitrageOpportunity> {
    
    // 套利阈值：价差超过 0.5% 才认为有套利机会
    private static final BigDecimal ARBITRAGE_THRESHOLD = new BigDecimal("0.005");
    
    @Override
    public void processElement(
            SpotPrice spot,
            FuturesPrice futures,
            Context ctx,
            Collector<ArbitrageOpportunity> out) {
        
        // 计算价差
        BigDecimal spread = futures.price.subtract(spot.price);
        
        // 计算价差率（相对于现货价格）
        BigDecimal spreadRate = spread.divide(spot.price, 6, RoundingMode.HALF_UP);
        
        // 判断是否有套利机会（价差率绝对值超过阈值）
        if (spreadRate.abs().compareTo(ARBITRAGE_THRESHOLD) > 0) {
            ArbitrageOpportunity opportunity = new ArbitrageOpportunity();
            opportunity.symbol = spot.symbol;
            opportunity.spotPrice = spot.price;
            opportunity.futuresPrice = futures.price;
            opportunity.spread = spread;
            opportunity.spreadRate = spreadRate.multiply(new BigDecimal("100"));  // 转换为百分比
            
            // 判断套利方向
            if (spread.compareTo(BigDecimal.ZERO) > 0) {
                // 合约价格 > 现货价格：做空合约，做多现货
                opportunity.arbitrageDirection = "做空合约/做多现货";
            } else {
                // 现货价格 > 合约价格：做多合约，做空现货
                opportunity.arbitrageDirection = "做多合约/做空现货";
            }
            
            // 估算利润（假设交易 1 个单位，扣除 0.01% 手续费）
            BigDecimal fee = spot.price.multiply(new BigDecimal("0.001"));
            opportunity.profitEstimate = spread.abs().subtract(fee.multiply(new BigDecimal("2")));
            
            opportunity.timestamp = System.currentTimeMillis();
            
            // 输出套利机会(不输出日志,由 TradingDecisionProcessor 统一管理)
            out.collect(opportunity);
        }
    }
}
