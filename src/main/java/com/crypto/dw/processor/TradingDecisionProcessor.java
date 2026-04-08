package com.crypto.dw.processor;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.model.ArbitrageOpportunity;
import com.crypto.dw.model.OrderUpdate;
import com.crypto.dw.model.PendingOrder;
import com.crypto.dw.model.TradeRecord;
import com.crypto.dw.trading.OKXTradingService;
import com.crypto.dw.trading.OpportunityTracker;
import com.crypto.dw.trading.PositionState;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 交易决策处理器
 * 
 * 功能:
 * 1. 接收套利机会,判断是否开仓/平仓
 * 2. 接收订单更新,确认开仓/平仓
 * 3. 维护持仓状态
 * 4. 输出交易明细
 */
public class TradingDecisionProcessor 
        extends KeyedCoProcessFunction<String, ArbitrageOpportunity, OrderUpdate, TradeRecord> {
    
    private static final Logger logger = LoggerFactory.getLogger(TradingDecisionProcessor.class);
    
    private final ConfigLoader config;
    private final boolean tradingEnabled;
    private final BigDecimal tradeAmount;
    private final BigDecimal openThreshold;
    private final BigDecimal closeThreshold;
    private final long maxHoldTimeMs;
    private final BigDecimal maxLossPerTrade;
    
    private transient OKXTradingService tradingService;
    private transient ValueState<PositionState> positionState;
    private transient MapState<String, PendingOrder> pendingOrders;
    private transient ValueState<OpportunityTracker> opportunityTracker;
    
    public TradingDecisionProcessor(ConfigLoader config) {
        this.config = config;
        this.tradingEnabled = config.getBoolean("arbitrage.trading.enabled", false);
        this.tradeAmount = new BigDecimal(config.getString("arbitrage.trading.trade-amount", "100"));
        this.openThreshold = new BigDecimal(config.getString("arbitrage.trading.open-threshold", "0.005"));
        this.closeThreshold = new BigDecimal(config.getString("arbitrage.trading.close-threshold", "0.002"));
        
        int maxHoldMinutes = config.getInt("arbitrage.trading.max-hold-time-minutes", 60);
        this.maxHoldTimeMs = maxHoldMinutes * 60 * 1000L;
        
        this.maxLossPerTrade = new BigDecimal(config.getString("arbitrage.trading.max-loss-per-trade", "10"));
    }
    
    @Override
    public void open(Configuration parameters) {
        // 初始化交易服务
        tradingService = new OKXTradingService(config);
        
        // 初始化持仓状态
        ValueStateDescriptor<PositionState> positionDescriptor = new ValueStateDescriptor<>(
            "position-state-v3",
            PositionState.class
        );
        positionState = getRuntimeContext().getState(positionDescriptor);
        
        // 初始化待确认订单
        MapStateDescriptor<String, PendingOrder> pendingDescriptor = new MapStateDescriptor<>(
            "pending-orders",
            String.class,
            PendingOrder.class
        );
        pendingOrders = getRuntimeContext().getMapState(pendingDescriptor);
        
        // 初始化套利机会跟踪器
        ValueStateDescriptor<OpportunityTracker> trackerDescriptor = new ValueStateDescriptor<>(
            "opportunity-tracker",
            OpportunityTracker.class
        );
        opportunityTracker = getRuntimeContext().getState(trackerDescriptor);
        
        logger.info("TradingDecisionProcessor 初始化完成");
        logger.info("  交易开关: {}", tradingEnabled ? "开启" : "关闭");
        logger.info("  交易金额: {} USDT", tradeAmount);
        logger.info("  开仓阈值: {}%", openThreshold.multiply(new BigDecimal("100")));
        logger.info("  平仓阈值: {}%", closeThreshold.multiply(new BigDecimal("100")));
    }
    
    @Override
    public void processElement1(
            ArbitrageOpportunity opportunity,
            Context ctx,
            Collector<TradeRecord> out) throws Exception {
        
        if (!tradingEnabled || !tradingService.isConfigured()) {
            return;
        }
        
        PositionState position = positionState.value();
        OpportunityTracker tracker = opportunityTracker.value();
        long now = System.currentTimeMillis();
        
        // 决策 1: 开仓逻辑
        if (position == null || !position.isOpen()) {
            if (shouldOpen(opportunity)) {
                // 检查是否是新的套利机会
                if (tracker == null || !tracker.isActive()) {
                    // 首次发现套利机会,开始跟踪
                    tracker = new OpportunityTracker();
                    tracker.setFirstSeenTime(now);
                    tracker.setLastSeenTime(now);
                    tracker.setActive(true);
                    tracker.setSpreadRate(opportunity.spreadRate);
                    tracker.setLastLogTime(now);
                    opportunityTracker.update(tracker);
                    
                    logger.info("🔍 发现新套利机会: {} | 价差率: {}% | 开始观察...", 
                        opportunity.symbol, opportunity.spreadRate);
                } else {
                    // 套利机会持续存在,更新最后看到时间
                    tracker.setLastSeenTime(now);
                    tracker.setSpreadRate(opportunity.spreadRate);
                    
                    // 检查是否持续超过5秒
                    long duration = tracker.getLastSeenTime() - tracker.getFirstSeenTime();
                    if (duration >= 5000) {
                        // 持续超过5秒,执行开仓
                        double durationSec = duration / 1000.0;
                        logger.info("⏰ 套利机会持续 {} 秒,满足开仓条件", String.format("%.1f", durationSec));
                        openPosition(opportunity, out);
                        
                        // 清除跟踪器
                        opportunityTracker.clear();
                    } else {
                        // 每2秒输出一次观察日志
                        if (now - tracker.getLastLogTime() >= 2000) {
                            double durationSec = duration / 1000.0;
                            logger.info("⏳ 套利机会持续中: {} | 价差率: {}% | 已持续 {} 秒 / 需要 5 秒",
                                opportunity.symbol, 
                                opportunity.spreadRate,
                                String.format("%.1f", durationSec));
                            tracker.setLastLogTime(now);
                        }
                    }
                    
                    opportunityTracker.update(tracker);
                }
            } else {
                // 套利机会消失,清除跟踪器
                if (tracker != null && tracker.isActive()) {
                    long duration = now - tracker.getFirstSeenTime();
                    double durationSec = duration / 1000.0;
                    logger.info("❌ 套利机会消失: {} | 持续时间 {} 秒(不足5秒)", 
                        opportunity.symbol, String.format("%.1f", durationSec));
                    opportunityTracker.clear();
                }
            }
        }
        // 决策 2: 持仓状态下的逻辑
        else {
            // 清除跟踪器(已经持仓,不需要再跟踪)
            if (tracker != null) {
                opportunityTracker.clear();
            }
            
            // 更新预估利润
            updateUnrealizedProfit(opportunity, position, out);
            
            // 检查是否需要平仓
            if (shouldClose(opportunity, position)) {
                closePosition(opportunity, position, out);
            }
        }
    }
    
    @Override
    public void processElement2(
            OrderUpdate order,
            Context ctx,
            Collector<TradeRecord> out) throws Exception {
        
        // 处理订单更新
        if ("filled".equals(order.state)) {
            handleOrderFilled(order, out);
        }
    }
    
    private boolean shouldOpen(ArbitrageOpportunity opp) {
        BigDecimal spreadRate = opp.spreadRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return spreadRate.abs().compareTo(openThreshold) > 0;
    }
    
    private boolean shouldClose(ArbitrageOpportunity opp, PositionState pos) {
        BigDecimal spreadRate = opp.spreadRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        
        // 条件 1: 价差回归
        boolean spreadConverged = spreadRate.abs().compareTo(closeThreshold) <= 0;
        
        // 条件 2: 超时
        long holdTime = System.currentTimeMillis() - pos.getOpenTime();
        boolean timeout = holdTime > maxHoldTimeMs;
        
        // 条件 3: 止损
        BigDecimal entrySpread = pos.getEntrySwapPrice().subtract(pos.getEntrySpotPrice());
        BigDecimal currentSpread = opp.futuresPrice.subtract(opp.spotPrice);
        BigDecimal spreadDiff = currentSpread.subtract(entrySpread);
        
        if ("SHORT_SPOT_LONG_SWAP".equals(pos.getDirection())) {
            spreadDiff = spreadDiff.negate();
        }
        
        BigDecimal fee = pos.getAmount().multiply(new BigDecimal("0.002"));
        BigDecimal unrealizedProfit = spreadDiff.subtract(fee);
        boolean stopLoss = unrealizedProfit.compareTo(maxLossPerTrade.negate()) < 0;
        
        return spreadConverged || timeout || stopLoss;
    }
    
    /**
     * 更新未实现利润
     */
    private void updateUnrealizedProfit(
            ArbitrageOpportunity opp,
            PositionState pos,
            Collector<TradeRecord> out) throws Exception {
        
        // 计算当前价差
        BigDecimal entrySpread = pos.getEntrySwapPrice().subtract(pos.getEntrySpotPrice());
        BigDecimal currentSpread = opp.futuresPrice.subtract(opp.spotPrice);
        BigDecimal spreadDiff = currentSpread.subtract(entrySpread);
        
        // 根据方向调整价差差异
        if ("SHORT_SPOT_LONG_SWAP".equals(pos.getDirection())) {
            spreadDiff = spreadDiff.negate();
        }
        
        // 计算手续费
        BigDecimal fee = pos.getAmount().multiply(new BigDecimal("0.002"));
        
        // 计算未实现利润
        BigDecimal unrealizedProfit = spreadDiff.subtract(fee);
        
        // 计算利润率
        BigDecimal profitRate = unrealizedProfit.divide(pos.getAmount(), 6, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        
        // 更新持仓状态
        pos.setUnrealizedProfit(unrealizedProfit);
        pos.setLastUpdateTime(System.currentTimeMillis());
        positionState.update(pos);
        
        // 定期输出日志(每10秒输出一次)
        long now = System.currentTimeMillis();
        if (now - pos.getLastLogTime() > 10000) {
            logger.info("📊 持仓更新: {} | 方向: {} | 未实现利润: {} USDT ({} %) | 持仓时间: {} 秒",
                pos.getSymbol(),
                pos.getDirection(),
                unrealizedProfit.setScale(4, RoundingMode.HALF_UP),
                profitRate.setScale(2, RoundingMode.HALF_UP),
                (now - pos.getOpenTime()) / 1000);
            pos.setLastLogTime(now);
            positionState.update(pos);
        }
    }
    
    private void openPosition(ArbitrageOpportunity opp, Collector<TradeRecord> out) 
            throws Exception {
        
        logger.info("🎯 开仓: {} | 价差率: {}%", opp.symbol, opp.spreadRate);
        
        String spotOrderId = null;
        String swapOrderId = null;
        String direction = null;
        
        try {
            if (opp.arbitrageDirection.contains("做多现货")) {
                // 策略 A: 做多现货 + 做空合约
                direction = "LONG_SPOT_SHORT_SWAP";
                spotOrderId = tradingService.buySpot(opp.symbol, tradeAmount);
                swapOrderId = tradingService.shortSwap(opp.symbol, tradeAmount, 1);
            } else {
                // 策略 B: 做空现货 + 做多合约
                direction = "SHORT_SPOT_LONG_SWAP";
                spotOrderId = tradingService.sellSpot(opp.symbol, tradeAmount);
                swapOrderId = tradingService.longSwap(opp.symbol, tradeAmount, 1);
            }
            
            if (spotOrderId != null && swapOrderId != null) {
                // 保存待确认订单
                PendingOrder pending = new PendingOrder();
                pending.symbol = opp.symbol;
                pending.action = "OPEN";
                pending.spotOrderId = spotOrderId;
                pending.swapOrderId = swapOrderId;
                pending.createTime = System.currentTimeMillis();
                
                pendingOrders.put(spotOrderId, pending);
                pendingOrders.put(swapOrderId, pending);
                
                // 立即创建临时持仓状态,防止重复开仓
                PositionState tempPosition = new PositionState();
                tempPosition.setSymbol(opp.symbol);
                tempPosition.setOpen(true);
                tempPosition.setDirection(direction);
                tempPosition.setAmount(tradeAmount);
                tempPosition.setEntrySpotPrice(opp.spotPrice);
                tempPosition.setEntrySwapPrice(opp.futuresPrice);
                tempPosition.setOpenTime(System.currentTimeMillis());
                tempPosition.setSpotOrderId(spotOrderId);
                tempPosition.setSwapOrderId(swapOrderId);
                tempPosition.setLastLogTime(System.currentTimeMillis());
                positionState.update(tempPosition);
                
                logger.info("📝 订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
            } else {
                logger.warn("⚠ 订单提交失败,未创建持仓状态");
            }
        } catch (Exception e) {
            logger.error("❌ 开仓失败: {}", e.getMessage(), e);
        }
    }
    
    private void closePosition(
            ArbitrageOpportunity opp, 
            PositionState pos, 
            Collector<TradeRecord> out) throws Exception {
        
        logger.info("🔄 平仓: {} | 价差率: {}%", opp.symbol, opp.spreadRate);
        
        try {
            String spotOrderId = null;
            String swapOrderId = null;
            
            if ("LONG_SPOT_SHORT_SWAP".equals(pos.getDirection())) {
                spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount());
                swapOrderId = tradingService.closeShortSwap(pos.getSymbol(), pos.getAmount());
            } else {
                spotOrderId = tradingService.buySpot(pos.getSymbol(), pos.getAmount());
                swapOrderId = tradingService.closeLongSwap(pos.getSymbol(), pos.getAmount());
            }
            
            if (spotOrderId != null && swapOrderId != null) {
                // 保存待确认订单
                PendingOrder pending = new PendingOrder();
                pending.symbol = pos.getSymbol();
                pending.action = "CLOSE";
                pending.spotOrderId = spotOrderId;
                pending.swapOrderId = swapOrderId;
                pending.createTime = System.currentTimeMillis();
                
                pendingOrders.put(spotOrderId, pending);
                pendingOrders.put(swapOrderId, pending);
                
                logger.info("📝 平仓订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
            }
        } catch (Exception e) {
            logger.error("❌ 平仓失败: {}", e.getMessage(), e);
        }
    }
    
    private void handleOrderFilled(OrderUpdate order, Collector<TradeRecord> out) 
            throws Exception {
        
        PendingOrder pending = pendingOrders.get(order.orderId);
        if (pending == null) {
            return;
        }
        
        // 更新订单状态
        if (order.orderId.equals(pending.spotOrderId)) {
            pending.spotFilled = true;
            pending.spotFillPrice = order.fillPrice;
        } else if (order.orderId.equals(pending.swapOrderId)) {
            pending.swapFilled = true;
            pending.swapFillPrice = order.fillPrice;
        }
        
        // 检查是否都成交
        if (pending.spotFilled && pending.swapFilled) {
            if ("OPEN".equals(pending.action)) {
                confirmOpen(pending, out);
            } else {
                confirmClose(pending, out);
            }
            
            // 清理待确认订单
            pendingOrders.remove(pending.spotOrderId);
            pendingOrders.remove(pending.swapOrderId);
        }
    }
    
    private void confirmOpen(PendingOrder pending, Collector<TradeRecord> out) 
            throws Exception {
        
        logger.info("✅ 开仓确认: {}", pending.symbol);
        
        // 更新持仓状态
        PositionState position = new PositionState();
        position.setSymbol(pending.symbol);
        position.setOpen(true);
        position.setDirection("LONG_SPOT_SHORT_SWAP");
        position.setAmount(tradeAmount);
        position.setEntrySpotPrice(pending.spotFillPrice);
        position.setEntrySwapPrice(pending.swapFillPrice);
        position.setOpenTime(System.currentTimeMillis());
        
        positionState.update(position);
        
        // 输出交易明细
        TradeRecord record = new TradeRecord();
        record.symbol = pending.symbol;
        record.action = "OPEN";
        record.direction = "LONG_SPOT_SHORT_SWAP";
        record.amount = tradeAmount;
        record.spotPrice = pending.spotFillPrice;
        record.swapPrice = pending.swapFillPrice;
        record.timestamp = System.currentTimeMillis();
        
        out.collect(record);
    }
    
    private void confirmClose(PendingOrder pending, Collector<TradeRecord> out) 
            throws Exception {
        
        logger.info("✅ 平仓确认: {}", pending.symbol);
        
        PositionState position = positionState.value();
        
        // 计算盈亏
        BigDecimal entrySpread = position.getEntrySwapPrice().subtract(position.getEntrySpotPrice());
        BigDecimal exitSpread = pending.swapFillPrice.subtract(pending.spotFillPrice);
        BigDecimal profit = exitSpread.subtract(entrySpread);
        
        // 更新持仓状态
        position.setOpen(false);
        positionState.update(position);
        
        // 输出交易明细
        TradeRecord record = new TradeRecord();
        record.symbol = pending.symbol;
        record.action = "CLOSE";
        record.direction = position.getDirection();
        record.amount = position.getAmount();
        record.spotPrice = pending.spotFillPrice;
        record.swapPrice = pending.swapFillPrice;
        record.profit = profit;
        record.holdTimeMs = System.currentTimeMillis() - position.getOpenTime();
        record.timestamp = System.currentTimeMillis();
        
        out.collect(record);
    }
}
