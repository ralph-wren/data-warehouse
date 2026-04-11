package com.crypto.dw.processor;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.async.SyncCsvWriter;
import com.crypto.dw.flink.model.ArbitrageOpportunity;
import com.crypto.dw.flink.model.OrderUpdate;
import com.crypto.dw.flink.model.PendingOrder;
import com.crypto.dw.model.TradeRecord;
import com.crypto.dw.trading.MarginSupportCache;
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
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;



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
    private final int leverage;  // 杠杆倍数
    private final BigDecimal openThreshold;
    private final BigDecimal closeThreshold;
    private final long maxHoldTimeMs;
    private final BigDecimal maxLossPerTrade;
    
    // 黑名单配置
    private final boolean blacklistEnabled;
    private final long blacklistDurationMs;
    private final int maxFailures;
    
    private transient OKXTradingService tradingService;
    private transient MarginSupportCache marginCache;  // 杠杆支持信息缓存
    private transient SyncCsvWriter orderDetailWriter;  // 同步订单明细日志（使用线程池）
    private transient SyncCsvWriter positionWriter;     // 同步持仓日志（使用线程池）
    private transient SyncCsvWriter summaryWriter;      // 同步交易汇总日志（使用线程池）
    
    private transient ValueState<PositionState> positionState;
    private transient MapState<String, PendingOrder> pendingOrders;
    private transient ValueState<OpportunityTracker> opportunityTracker;
    private transient ValueState<Long> lastStatusLogTime;  // 最后一次状态日志输出时间
    private transient ValueState<String> currentSymbol;    // 当前币对名称
    
    // 失败黑名单状态
    private transient MapState<String, Integer> failureCount;  // 失败次数统计
    private transient MapState<String, Long> blacklistExpiry;  // 黑名单过期时间
    
    // 状态日志输出间隔: 30秒
    private static final long STATUS_LOG_INTERVAL_MS = 30000;
    
    public TradingDecisionProcessor(ConfigLoader config) {
        this.config = config;
        this.tradingEnabled = config.getBoolean("arbitrage.trading.enabled", false);
        this.tradeAmount = new BigDecimal(config.getString("arbitrage.trading.trade-amount-usdt", "100"));
        this.leverage = config.getInt("arbitrage.trading.leverage", 1);  // 读取杠杆倍数配置,默认1倍
        this.openThreshold = new BigDecimal(config.getString("arbitrage.trading.open-threshold", "0.005"));
        this.closeThreshold = new BigDecimal(config.getString("arbitrage.trading.close-threshold", "0.002"));
        
        int maxHoldMinutes = config.getInt("arbitrage.trading.max-hold-time-minutes", 60);
        this.maxHoldTimeMs = maxHoldMinutes * 60 * 1000L;
        
        this.maxLossPerTrade = new BigDecimal(config.getString("arbitrage.trading.max-loss-per-trade", "10"));
        
        // 读取黑名单配置
        this.blacklistEnabled = config.getBoolean("arbitrage.trading.blacklist.enabled", true);
        int blacklistDurationMinutes = config.getInt("arbitrage.trading.blacklist.duration-minutes", 60);
        this.blacklistDurationMs = blacklistDurationMinutes * 60 * 1000L;
        this.maxFailures = config.getInt("arbitrage.trading.blacklist.max-failures", 3);
    }
    
    @Override
    public void open(Configuration parameters) {
        // 初始化杠杆支持信息缓存（从 Redis 加载到内存）
        marginCache = new MarginSupportCache(config);
        
        // 初始化交易服务（传入杠杆缓存）
        tradingService = new OKXTradingService(config, marginCache);
        
        // 初始化同步 CSV 写入器（使用线程池）
        String logDir = config.getString("arbitrage.trading.log-dir", "./logs/trading");
        
        // 订单明细日志表头
        String[] orderHeaders = {
            "timestamp", "symbol", "action", "direction", "order_type",
            "order_id", "price", "amount", "status", "message"
        };
        orderDetailWriter = new SyncCsvWriter(logDir, "order_detail", orderHeaders, 2);
        
        // 持仓日志表头
        String[] positionHeaders = {
            "timestamp", "symbol", "action", "direction", "amount",
            "entry_spot_price", "entry_swap_price", "exit_spot_price", "exit_swap_price",
            "profit", "profit_rate", "hold_time_seconds", "unrealized_profit"
        };
        positionWriter = new SyncCsvWriter(logDir, "position", positionHeaders, 2);
        
        // 交易汇总日志表头
        String[] summaryHeaders = {
            "timestamp", "symbol", "direction", "result", "profit", "profit_rate",
            "hold_time_seconds", "entry_spot_price", "entry_swap_price",
            "exit_spot_price", "exit_swap_price", "amount"
        };
        summaryWriter = new SyncCsvWriter(logDir, "summary", summaryHeaders, 2);
        
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
        
        // 初始化失败黑名单状态
        MapStateDescriptor<String, Integer> failureCountDescriptor = new MapStateDescriptor<>(
            "failure-count",
            String.class,
            Integer.class
        );
        failureCount = getRuntimeContext().getMapState(failureCountDescriptor);
        
        MapStateDescriptor<String, Long> blacklistExpiryDescriptor = new MapStateDescriptor<>(
            "blacklist-expiry",
            String.class,
            Long.class
        );
        blacklistExpiry = getRuntimeContext().getMapState(blacklistExpiryDescriptor);
        
        logger.info("TradingDecisionProcessor 初始化完成");
        logger.info("  交易开关: {}", tradingEnabled ? "开启" : "关闭");
        logger.info("  交易金额: {} USDT", tradeAmount);
        logger.info("  开仓阈值: {}%", openThreshold.multiply(new BigDecimal("100")));
        logger.info("  平仓阈值: {}%", closeThreshold.multiply(new BigDecimal("100")));
        logger.info("  杠杆支持信息: {} 个币种已加载", marginCache.size());
        logger.info("  黑名单机制: {}", blacklistEnabled ? "启用" : "禁用");
        if (blacklistEnabled) {
            logger.info("  黑名单持续时间: {} 分钟", blacklistDurationMs / 60000);
            logger.info("  最大失败次数: {}", maxFailures);
        }
    }
    
    @Override
    public void close() throws Exception {
        // 关闭同步 CSV 写入器
        if (orderDetailWriter != null) {
            orderDetailWriter.close();
        }
        if (positionWriter != null) {
            positionWriter.close();
        }
        if (summaryWriter != null) {
            summaryWriter.close();
        }
        
        // 关闭杠杆支持信息缓存
        if (marginCache != null) {
            marginCache.close();
        }
        
        logger.info("TradingDecisionProcessor 已关闭");
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
            logger.debug("⏰ 已启动状态日志定时器,每 {} 秒输出一次", STATUS_LOG_INTERVAL_MS / 1000);
        }
        
        // 决策 1: 开仓逻辑
        if (position == null || !position.isOpen()) {
            // 检查是否在黑名单中
            if (isInBlacklist(opportunity.symbol)) {
                // 在黑名单中,清除跟踪器并跳过
                if (tracker != null && tracker.isActive()) {
                    opportunityTracker.clear();
                }
                return;
            }
            
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
        } else if ("canceled".equals(order.state) || "failed".equals(order.state)) {
            // 处理订单取消或失败
            handleOrderFailed(order, out);
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
        
        // 检查是否在黑名单中
        if (isInBlacklist(symbol)) {
            // 在黑名单中,不输出状态日志
            // 注册下一个定时器
            long nextTimerTime = now + STATUS_LOG_INTERVAL_MS;
            ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
            return;
        }
        
        // 只有持仓状态的币对才输出状态日志
        if (position != null && position.isOpen()) {
            printStatusLog(symbol, position, tracker, now);
        }
        
        // 注册下一个定时器,实现持续定时输出
        long nextTimerTime = now + STATUS_LOG_INTERVAL_MS;
        ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
    }
    
    private boolean shouldOpen(ArbitrageOpportunity opp) {
        BigDecimal spreadRate = opp.spreadRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        return spreadRate.abs().compareTo(openThreshold) > 0;
    }
    
    /**
     * 检查币对是否在黑名单中
     */
    private boolean isInBlacklist(String symbol) throws Exception {
        if (!blacklistEnabled) {
            return false;
        }
        
        Long expiry = blacklistExpiry.get(symbol);
        if (expiry == null) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        if (now > expiry) {
            // 黑名单已过期,清除
            blacklistExpiry.remove(symbol);
            failureCount.remove(symbol);
            logger.info("⏰ 黑名单过期: {} | 已移出黑名单", symbol);
            return false;
        }
        
        return true;
    }
    
    /**
     * 记录失败并检查是否需要加入黑名单
     */
    private void recordFailure(String symbol, String reason) throws Exception {
        if (!blacklistEnabled) {
            return;
        }
        
        // 增加失败次数
        Integer count = failureCount.get(symbol);
        count = (count == null) ? 1 : count + 1;
        failureCount.put(symbol, count);
        
        logger.warn("⚠ 交易失败: {} | 失败次数: {} / {} | 原因: {}", 
            symbol, count, maxFailures, reason);
        
        // 检查是否需要加入黑名单
        if (count >= maxFailures) {
            long expiry = System.currentTimeMillis() + blacklistDurationMs;
            blacklistExpiry.put(symbol, expiry);
            
            long expiryMinutes = blacklistDurationMs / 60000;
            logger.error("🚫 加入黑名单: {} | 失败次数: {} | 持续时间: {} 分钟 | 原因: {}", 
                symbol, count, expiryMinutes, reason);
            
            // 注意: 不再写入 BLACKLIST 日志到 order_detail.csv
        }
    }
    
    /**
     * 清除失败计数(成功交易后调用)
     */
    private void clearFailureCount(String symbol) throws Exception {
        if (!blacklistEnabled) {
            return;
        }
        
        Integer count = failureCount.get(symbol);
        if (count != null && count > 0) {
            failureCount.remove(symbol);
            logger.info("✓ 清除失败计数: {} | 之前失败次数: {}", symbol, count);
        }
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
            // 根据 USDT 金额和现货价格计算币的数量（用于合约下单）
            BigDecimal coinAmount = tradeAmount.divide(opp.spotPrice, 8, RoundingMode.UP);
            logger.info("💰 交易金额: {} USDT | 现货价格: {} | 计算币数量: {} {}", 
                tradeAmount, opp.spotPrice, coinAmount, opp.symbol);
            
            if (opp.arbitrageDirection.contains("做多现货")) {
                // 策略 A: 做多现货 + 做空合约（使用配置的杠杆倍数）
                direction = "LONG_SPOT_SHORT_SWAP";
                
                // 先下现货单（传入 USDT 金额）
                spotOrderId = tradingService.buySpot(opp.symbol, tradeAmount, opp.spotPrice);
                
                // 检查现货单是否成功
                if (spotOrderId == null) {
                    logger.error("❌ 现货下单失败,取消合约下单");
                    recordFailure(opp.symbol, "现货下单失败");
                    
                    // 写入失败日志
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    String errorLog = String.format("%s,%s,OPEN,%s,SPOT,N/A,N/A,N/A,FAILED,现货下单失败",
                        timestamp, opp.symbol, direction);
                    orderDetailWriter.write(errorLog);
                    return;
                }
                
                // 现货单成功,再下合约单（传入币数量）
                swapOrderId = tradingService.shortSwap(opp.symbol, coinAmount, opp.futuresPrice, this.leverage);
                
            } else {
                // 策略 B: 做空现货 + 做多合约（使用配置的杠杆倍数）
                direction = "SHORT_SPOT_LONG_SWAP";
                
                // 先下现货单（传入 USDT 金额，使用配置的杠杆倍数）
                spotOrderId = tradingService.sellSpot(opp.symbol, tradeAmount, opp.spotPrice, this.leverage);
                
                // 检查现货单是否成功
                if (spotOrderId == null) {
                    logger.error("❌ 现货下单失败,取消合约下单");
                    
                    // 获取详细错误信息
                    String errorMsg = tradingService.getLastErrorMessage();
                    if (errorMsg != null) {
                        if (errorMsg.contains("Insufficient") || errorMsg.contains("51008")) {
                            recordFailure(opp.symbol, "余额不足或借币额度不足");
                        } else if (errorMsg.contains("51001")) {
                            recordFailure(opp.symbol, "订单数量不符合要求");
                        } else if (errorMsg.contains("51004")) {
                            recordFailure(opp.symbol, "交易对不存在或已下线");
                        } else {
                            recordFailure(opp.symbol, errorMsg);
                        }
                    } else {
                        recordFailure(opp.symbol, "现货下单失败");
                    }
                    
                    // 写入失败日志
                    String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                    String errorLog = String.format("%s,%s,OPEN,%s,SPOT,N/A,N/A,N/A,FAILED,%s",
                        timestamp, opp.symbol, direction, 
                        errorMsg != null ? errorMsg.replace(",", ";") : "现货下单失败");
                    orderDetailWriter.write(errorLog);
                    return;
                }
                
                // 现货单成功,再下合约单（传入币数量）
                swapOrderId = tradingService.longSwap(opp.symbol, coinAmount, opp.futuresPrice, this.leverage);
            }
            
            if (spotOrderId != null && swapOrderId != null) {
                // 两个订单都成功
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
                tempPosition.setAmount(coinAmount);  // 保存币的数量（用于平仓）
                tempPosition.setEntrySpotPrice(opp.spotPrice);
                tempPosition.setEntrySwapPrice(opp.futuresPrice);
                tempPosition.setOpenTime(System.currentTimeMillis());
                tempPosition.setSpotOrderId(spotOrderId);
                tempPosition.setSwapOrderId(swapOrderId);
                tempPosition.setLastLogTime(System.currentTimeMillis());
                positionState.update(tempPosition);
                
                // 同步写入订单明细日志
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                
                // 现货订单日志
                String spotLog = String.format("%s,%s,OPEN,%s,SPOT,%s,%s,%s,PENDING,订单已提交",
                    timestamp, opp.symbol, direction, spotOrderId, 
                    opp.spotPrice, coinAmount);  // 日志记录币的数量
                orderDetailWriter.write(spotLog);
                
                // 合约订单日志
                String swapLog = String.format("%s,%s,OPEN,%s,SWAP,%s,%s,%s,PENDING,订单已提交",
                    timestamp, opp.symbol, direction, swapOrderId, 
                    opp.futuresPrice, coinAmount);  // 日志记录币的数量
                orderDetailWriter.write(swapLog);
                
                logger.info("📝 订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
            } else if (spotOrderId != null && swapOrderId == null) {
                // 现货单成功但合约单失败
                logger.error("❌ 合约下单失败,现货单已提交: {}", spotOrderId);
                
                // 获取合约下单失败的详细错误信息
                String errorMsg = tradingService.getLastErrorMessage();
                String failureReason = "合约下单失败";
                
                if (errorMsg != null) {
                    if (errorMsg.contains("51121")) {
                        failureReason = "合约数量不符合lot size要求: " + errorMsg;
                        recordFailure(opp.symbol, "合约数量不符合lot size要求");
                    } else if (errorMsg.contains("Insufficient") || errorMsg.contains("51008")) {
                        failureReason = "合约余额不足: " + errorMsg;
                        recordFailure(opp.symbol, "合约余额不足");
                    } else {
                        failureReason = "合约下单失败: " + errorMsg;
                        recordFailure(opp.symbol, "合约下单失败:" + errorMsg);
                    }
                } else {
                    recordFailure(opp.symbol, "合约下单失败");
                }
                
                // ⚠️ 关键修复：创建临时持仓状态，防止继续下单导致持仓超过1个
                // 虽然合约下单失败，但现货已经提交，需要标记为"持仓中"避免重复下单
                PositionState tempPosition = new PositionState();
                tempPosition.setSymbol(opp.symbol);
                tempPosition.setOpen(true);  // 标记为持仓中
                tempPosition.setDirection(direction);
                tempPosition.setAmount(coinAmount);  // 保存币的数量（用于平仓）
                tempPosition.setEntrySpotPrice(opp.spotPrice);
                tempPosition.setEntrySwapPrice(BigDecimal.ZERO);  // 合约未成交，设置为0
                tempPosition.setOpenTime(System.currentTimeMillis());
                tempPosition.setSpotOrderId(spotOrderId);
                tempPosition.setSwapOrderId(null);  // 合约订单失败，设置为null
                tempPosition.setLastLogTime(System.currentTimeMillis());
                positionState.update(tempPosition);
                
                logger.warn("⚠️ 已创建临时持仓状态（现货成功，合约失败），防止重复下单");
                
                // 写入现货订单成功日志
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String spotLog = String.format("%s,%s,OPEN,%s,SPOT,%s,%s,%s,SUCCESS,现货订单已提交",
                    timestamp, opp.symbol, direction, spotOrderId, 
                    opp.spotPrice, coinAmount);  // 日志记录币的数量
                orderDetailWriter.write(spotLog);
                
                logger.info("📝 已写入现货成功日志: {}", spotLog);
                
                // 写入合约订单失败日志（包含详细错误信息）
                String swapErrorLog = String.format("%s,%s,OPEN,%s,SWAP,N/A,%s,%s,FAILED,%s",
                    timestamp, opp.symbol, direction, 
                    opp.futuresPrice, coinAmount,  // 日志记录币的数量
                    failureReason.replace(",", ";"));
                orderDetailWriter.write(swapErrorLog);
                
                logger.info("📝 已写入合约失败日志: {}", swapErrorLog);
            } else {
                logger.warn("⚠ 订单提交失败,未创建持仓状态");
            }
        } catch (Exception e) {
            logger.error("❌ 开仓失败: {}", e.getMessage(), e);
            
            // 记录失败
            String errorMsg = e.getMessage();
            if (errorMsg != null) {
                // 检查是否是余额不足等致命错误
                if (errorMsg.contains("Insufficient") || errorMsg.contains("51008")) {
                    recordFailure(opp.symbol, "余额不足");
                } else if (errorMsg.contains("51001")) {
                    recordFailure(opp.symbol, "订单数量不符合要求");
                } else if (errorMsg.contains("51004")) {
                    recordFailure(opp.symbol, "交易对不存在或已下线");
                } else {
                    recordFailure(opp.symbol, errorMsg);
                }
            } else {
                recordFailure(opp.symbol, "未知错误");
            }
            
            // 同步写入失败日志
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String errorLog = String.format("%s,%s,OPEN,%s,ERROR,N/A,N/A,N/A,FAILED,%s",
                timestamp, opp.symbol, direction != null ? direction : "UNKNOWN", 
                errorMsg != null ? errorMsg.replace(",", ";") : "未知错误");
            orderDetailWriter.write(errorLog);
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
                // 平仓时的 sellSpot 不需要杠杆，但为了保持接口一致，传入配置的杠杆倍数
                spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice, this.leverage);
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
                
                // 同步写入订单明细日志（使用线程池）
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                
                // 现货订单日志
                String spotLog = String.format("%s,%s,CLOSE,%s,SPOT,%s,%s,%s,PENDING,平仓订单已提交",
                    timestamp, pos.getSymbol(), pos.getDirection(), spotOrderId, 
                    opp.spotPrice, pos.getAmount());
                orderDetailWriter.write(spotLog);
                
                // 合约订单日志
                String swapLog = String.format("%s,%s,CLOSE,%s,SWAP,%s,%s,%s,PENDING,平仓订单已提交",
                    timestamp, pos.getSymbol(), pos.getDirection(), swapOrderId, 
                    opp.futuresPrice, pos.getAmount());
                orderDetailWriter.write(swapLog);
                
                logger.info("📝 平仓订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
            }
        } catch (Exception e) {
            logger.error("❌ 平仓失败: {}", e.getMessage(), e);
            
            // 同步写入失败日志（使用线程池）
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String errorLog = String.format("%s,%s,CLOSE,%s,ERROR,N/A,N/A,N/A,FAILED,%s",
                timestamp, pos.getSymbol(), pos.getDirection(), 
                e.getMessage().replace(",", ";"));
            orderDetailWriter.write(errorLog);
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
    
    /**
     * 处理订单失败或取消
     * 
     * 功能：
     * 1. 检查是否是待确认订单
     * 2. 如果是开仓订单失败，清除临时持仓状态
     * 3. 记录失败日志
     * 4. 加入黑名单
     */
    private void handleOrderFailed(OrderUpdate order, Collector<TradeRecord> out) 
            throws Exception {
        
        PendingOrder pending = pendingOrders.get(order.orderId);
        if (pending == null) {
            return;
        }
        
        logger.warn("⚠️ 订单失败: orderId={}, symbol={}, state={}", 
            order.orderId, pending.symbol, order.state);
        
        // 如果是开仓订单失败
        if ("OPEN".equals(pending.action)) {
            // 清除临时持仓状态
            PositionState position = positionState.value();
            if (position != null && position.isOpen() && pending.symbol.equals(position.getSymbol())) {
                logger.warn("⚠️ 清除临时持仓状态: symbol={}", pending.symbol);
                positionState.clear();
            }
            
            // 加入黑名单
            String failureReason = "订单失败: " + order.state;
            recordFailure(pending.symbol, failureReason);
            
            // 写入失败日志
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            // 判断是现货还是合约订单失败
            String orderType = order.orderId.equals(pending.spotOrderId) ? "SPOT" : "SWAP";
            String errorLog = String.format("%s,%s,OPEN,%s,%s,%s,N/A,N/A,FAILED,%s",
                timestamp, pending.symbol, "UNKNOWN", orderType, order.orderId, failureReason.replace(",", ";"));
            orderDetailWriter.write(errorLog);
        }
        
        // 清理待确认订单
        pendingOrders.remove(pending.spotOrderId);
        pendingOrders.remove(pending.swapOrderId);
    }
    
    private void confirmOpen(PendingOrder pending, Collector<TradeRecord> out) 
            throws Exception {
        
        logger.info("✅ 开仓确认: {}", pending.symbol);
        
        // 清除失败计数
        clearFailureCount(pending.symbol);
        
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
        
        // 同步写入持仓日志（使用线程池）
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String posLog = String.format("%s,%s,OPEN,%s,%s,%s,%s,N/A,N/A,N/A,N/A,N/A,N/A",
            timestamp, pending.symbol, "LONG_SPOT_SHORT_SWAP", tradeAmount,
            pending.spotFillPrice, pending.swapFillPrice);
        positionWriter.write(posLog);
        
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
        BigDecimal profitRate = profit.divide(position.getAmount(), 6, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
        
        long holdTimeMs = System.currentTimeMillis() - position.getOpenTime();
        long holdTimeSeconds = holdTimeMs / 1000;
        
        // 同步写入持仓日志（使用线程池）
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        String posLog = String.format("%s,%s,CLOSE,%s,%s,%s,%s,%s,%s,%s,%s,%d,N/A",
            timestamp, pending.symbol, position.getDirection(), position.getAmount(),
            position.getEntrySpotPrice(), position.getEntrySwapPrice(),
            pending.spotFillPrice, pending.swapFillPrice,
            profit, profitRate, holdTimeSeconds);
        positionWriter.write(posLog);
        
        // 同步写入交易汇总日志（使用线程池）
        String result = profit.compareTo(BigDecimal.ZERO) > 0 ? "PROFIT" : "LOSS";
        String summaryLog = String.format("%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%s",
            timestamp, pending.symbol, position.getDirection(), result,
            profit.setScale(4, RoundingMode.HALF_UP),
            profitRate.setScale(2, RoundingMode.HALF_UP),
            holdTimeSeconds,
            position.getEntrySpotPrice(),
            position.getEntrySwapPrice(),
            pending.spotFillPrice,
            pending.swapFillPrice,
            position.getAmount());
        summaryWriter.write(summaryLog);
        
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
        record.holdTimeMs = holdTimeMs;
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
