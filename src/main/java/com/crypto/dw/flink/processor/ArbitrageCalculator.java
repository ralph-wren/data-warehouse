package com.crypto.dw.flink.processor;

import com.crypto.dw.flink.model.ArbitrageOpportunity;
import com.crypto.dw.flink.model.FuturesPrice;
import com.crypto.dw.flink.model.SpotPrice;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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
    
    private static final Logger logger = LoggerFactory.getLogger(ArbitrageCalculator.class);
    
    // 套利阈值：价差超过 0.5% 才认为有套利机会
    private static final BigDecimal ARBITRAGE_THRESHOLD = new BigDecimal("0.005");
    
    // 时间差阈值：2 秒（毫秒）
    private static final long TIME_DIFF_THRESHOLD_MS = 4000;
    
    @Override
    public void processElement(
            SpotPrice spot,
            FuturesPrice futures,
            Context ctx,
            Collector<ArbitrageOpportunity> out) {
        
        // ========== 时间差检测 ==========
        long currentTime = System.currentTimeMillis();
        long spotTime = spot.timestamp;
        long futuresTime = futures.timestamp;
        
        // 1. 检测现货和合约数据的时间差
        long spotFuturesTimeDiff = Math.abs(spotTime - futuresTime);
        if (spotFuturesTimeDiff > TIME_DIFF_THRESHOLD_MS) {
            logger.warn("⚠️ 现货和合约时间差超过 2 秒: symbol={}, 现货时间={}, 合约时间={}, 时间差={}ms",
                    spot.symbol, spotTime, futuresTime, spotFuturesTimeDiff);
        }
        
        // 2. 检测系统时间与现货数据的时间差
        long currentSpotTimeDiff = Math.abs(currentTime - spotTime);
        if (currentSpotTimeDiff > TIME_DIFF_THRESHOLD_MS) {
            logger.debug("⚠️ 系统时间与现货数据时间差超过 2 秒: symbol={}, 系统时间={}, 现货时间={}, 时间差={}ms",
                    spot.symbol, currentTime, spotTime, currentSpotTimeDiff);
        }
        
        // 3. 检测系统时间与合约数据的时间差
        long currentFuturesTimeDiff = Math.abs(currentTime - futuresTime);
        if (currentFuturesTimeDiff > TIME_DIFF_THRESHOLD_MS) {
            logger.debug("⚠️ 系统时间与合约数据时间差超过 2 秒: symbol={}, 系统时间={}, 合约时间={}, 时间差={}ms",
                    spot.symbol, currentTime, futuresTime, currentFuturesTimeDiff);
        }
        
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
                // 合约价格 > 现货价格：做空合约，做多现货（策略 A）
                opportunity.arbitrageDirection = "做多现货/做空合约";
            } else {
                // 现货价格 > 合约价格：做多合约，做空现货（策略 B）
                opportunity.arbitrageDirection = "做空现货/做多合约";
            }
            
            // 估算利润（假设交易 1 个单位，扣除 0.1% 手续费）
            BigDecimal fee = spot.price.multiply(new BigDecimal("0.001"));
            opportunity.profitEstimate = spread.abs().subtract(fee.multiply(new BigDecimal("2")));
            
            opportunity.timestamp = System.currentTimeMillis();
            
            // 输出套利机会(不输出日志,由 TradingDecisionProcessor 统一管理)
            out.collect(opportunity);
        }
    }
}
