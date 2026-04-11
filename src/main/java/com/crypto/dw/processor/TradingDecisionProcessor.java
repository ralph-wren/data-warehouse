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
    private transient ValueState<Long> lastStatusLogTime;  // 最后一次状态日志输出时间
    private transient ValueState<String> currentSymbol;    // 当前币对名称
    
    // 状态日志输出间隔: 30秒
    private static final long STATUS_LOG_INTERVAL_MS = 30000;
    
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
        
        // 初始化最后状态日志时间
        ValueStateDescriptor<Long> lastStatusLogDescriptor = new ValueStateDescriptor<>(
            "last-status-log-time",
            Long.class
        );
        lastStatusLogTime = getRuntimeContext().getState(lastStatusLogDescriptor);
        
        // 初始化币对名称状态
        ValueStateDescriptor<String> symbolDescriptor = new ValueStateDescriptor<>(
            "current-symbol",
            String.class
        );
        currentSymbol = getRuntimeContext().getState(symbolDescriptor);
        
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
        
        // 保存当前币对名称
        currentSymbol.update(opportunity.symbol);
        
        // 注册定时器(只在第一次处理时注册)
        Long lastLogTime = lastStatusLogTime.value();
        if (lastLogTime == null) {
            // 首次处理,注册第一个定时器
            long nextTimerTime = now + STATUS_LOG_INTERVAL_MS;
            ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
            lastStatusLogTime.update(now);
            logger.info("⏰ 已启动状态日志定时器,每 {} 秒输出一次", STATUS_LOG_INTERVAL_MS / 1000);
        }
        
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
    
    @Override
    public void onTimer(
            long timestamp,
            OnTimerContext ctx,
            Collector<TradeRecord> out) throws Exception {
        
        // 定时器触发,输出状态日志
        long now = System.currentTimeMillis();
        
        // 获取当前状态
        PositionState position = positionState.value();
        OpportunityTracker tracker = opportunityTracker.value();
        String symbol = currentSymbol.value();
        
        // 如果没有币对信息,跳过本次输出
        if (symbol == null || symbol.isEmpty()) {
            // 注册下一个定时器
            long nextTimerTime = now + STATUS_LOG_INTERVAL_MS;
            ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
            return;
        }
        
        // 输出状态日志
        printStatusLog(symbol, position, tracker, now);
        
        // 注册下一个定时器,实现持续定时输出
        long nextTimerTime = now + STATUS_LOG_INTERVAL_MS;
        ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
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
                spotOrderId = tradingService.buySpot(opp.symbol, tradeAmount, opp.spotPrice);
                swapOrderId = tradingService.shortSwap(opp.symbol, tradeAmount, 1);
            } else {
                // 策略 B: 做空现货 + 做多合约
                direction = "SHORT_SPOT_LONG_SWAP";
                spotOrderId = tradingService.sellSpot(opp.symbol, tradeAmount, opp.spotPrice);
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
                spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice);
                swapOrderId = tradingService.closeShortSwap(pos.getSymbol(), pos.getAmount());
            } else {
                spotOrderId = tradingService.buySpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice);
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


    
    /**
     * 定期输出状态日志
     * 每30秒输出一次当前币对的状态信息
     */
    private void printStatusLog(String symbol, PositionState position, OpportunityTracker tracker, long now) throws Exception {
        // 构建状态信息
        StringBuilder status = new StringBuilder();
        status.append("\n========================================\n");
        status.append(String.format("📊 币对状态报告: %s\n", symbol));
        status.append(String.format("⏰ 时间: %s\n", new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss").format(new java.util.Date(now))));
        status.append("========================================\n");
        
        // 1. 持仓状态
        if (position != null && position.isOpen()) {
            long holdTime = now - position.getOpenTime();
            long holdSeconds = holdTime / 1000;
            long holdMinutes = holdSeconds / 60;
            long holdHours = holdMinutes / 60;
            
            status.append("📈 持仓状态: 已开仓\n");
            status.append(String.format("  方向: %s\n", position.getDirection()));
            status.append(String.format("  金额: %s USDT\n", position.getAmount()));
            status.append(String.format("  开仓价格: 现货=%s, 合约=%s\n", 
                position.getEntrySpotPrice(), position.getEntrySwapPrice()));
            status.append(String.format("  开仓价差率: %s%%\n", position.getEntrySpreadRate()));
            
            if (position.getUnrealizedProfit() != null) {
                BigDecimal profitRate = position.getUnrealizedProfit()
                    .divide(position.getAmount(), 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
                status.append(String.format("  未实现利润: %s USDT (%s%%)\n", 
                    position.getUnrealizedProfit().setScale(4, RoundingMode.HALF_UP),
                    profitRate.setScale(2, RoundingMode.HALF_UP)));
            }
            
            if (holdHours > 0) {
                status.append(String.format("  持仓时间: %d小时%d分钟\n", holdHours, holdMinutes % 60));
            } else if (holdMinutes > 0) {
                status.append(String.format("  持仓时间: %d分钟%d秒\n", holdMinutes, holdSeconds % 60));
            } else {
                status.append(String.format("  持仓时间: %d秒\n", holdSeconds));
            }
            
            status.append(String.format("  订单ID: 现货=%s, 合约=%s\n", 
                position.getSpotOrderId(), position.getSwapOrderId()));
        } else {
            status.append("📉 持仓状态: 未持仓\n");
        }
        
        // 2. 套利机会跟踪状态
        if (tracker != null && tracker.isActive()) {
            long duration = now - tracker.getFirstSeenTime();
            double durationSec = duration / 1000.0;
            
            status.append("\n🔍 套利机会跟踪:\n");
            status.append(String.format("  状态: 观察中\n"));
            status.append(String.format("  价差率: %s%%\n", tracker.getSpreadRate()));
            status.append(String.format("  持续时间: %.1f秒 / 需要 5秒\n", durationSec));
            
            if (durationSec >= 5.0) {
                status.append("  ⚠ 已满足开仓条件,等待执行\n");
            } else {
                status.append(String.format("  ⏳ 还需等待 %.1f秒\n", 5.0 - durationSec));
            }
        } else {
            status.append("\n🔍 套利机会跟踪: 无活跃跟踪\n");
        }
        
        // 3. 交易配置
        status.append("\n⚙️ 交易配置:\n");
        status.append(String.format("  交易开关: %s\n", tradingEnabled ? "✅ 开启" : "❌ 关闭"));
        status.append(String.format("  交易金额: %s USDT\n", tradeAmount));
        status.append(String.format("  开仓阈值: %s%%\n", openThreshold.multiply(new BigDecimal("100"))));
        status.append(String.format("  平仓阈值: %s%%\n", closeThreshold.multiply(new BigDecimal("100"))));
        status.append(String.format("  最大持仓时间: %d分钟\n", maxHoldTimeMs / 60000));
        status.append(String.format("  最大亏损: %s USDT\n", maxLossPerTrade));
        
        status.append("========================================\n");
        
        // 输出日志
        logger.info(status.toString());
        
        // 更新最后日志时间
        lastStatusLogTime.update(now);
    }
}
