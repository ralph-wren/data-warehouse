package com.crypto.dw.processor;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.async.SyncCsvWriter;
import com.crypto.dw.flink.model.ArbitrageOpportunity;

import com.crypto.dw.model.TradeRecord;
import com.crypto.dw.redis.RedisConnectionManager;
import com.crypto.dw.trading.MarginSupportCache;
import com.crypto.dw.trading.OKXTradingService;
import com.crypto.dw.trading.OpportunityTracker;
import com.crypto.dw.trading.PositionState;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;



/**
 * 交易决策处理器 - 重构版
 * 
 * 功能:
 * 1. 接收套利机会,判断是否开仓/平仓
 * 2. 主动查询订单明细（不依赖 WebSocket）
 * 3. 维护持仓状态
 * 4. 输出交易明细
 */
public class TradingDecisionProcessor 
        extends KeyedProcessFunction<String, ArbitrageOpportunity, TradeRecord> {
    
    private static final Logger logger = LoggerFactory.getLogger(TradingDecisionProcessor.class);
    
    private final ConfigLoader config;
    private final boolean tradingEnabled;
    private final BigDecimal tradeAmount;
    private final int leverage;  // 杠杆倍数
    private final BigDecimal openThreshold;
    private final BigDecimal closeThreshold;
    private final long maxHoldTimeMs;
    private final BigDecimal maxLossPerTrade;
    private final int maxPositions;  // 最大持仓数量
    
    // 黑名单配置
    private final boolean blacklistEnabled;
    private final long blacklistDurationMs;
    private final int maxFailures;
    
    private transient OKXTradingService tradingService;
    private transient MarginSupportCache marginCache;  // 杠杆支持信息缓存
    private transient RedisConnectionManager redisManager;  // Redis 连接管理器（用于全局持仓计数）
    private transient SyncCsvWriter orderDetailWriter;  // 同步订单明细日志（使用线程池）
    private transient SyncCsvWriter positionWriter;     // 同步持仓日志（使用线程池）
    private transient SyncCsvWriter summaryWriter;      // 同步交易汇总日志（使用线程池）
    private transient SyncCsvWriter orderDetailFullWriter;  // 订单详细信息日志（包含成交时间、手续费等）
    private transient java.util.concurrent.ExecutorService orderQueryExecutor;  // 订单查询线程池
    
    // Redis Key 常量
    private static final String REDIS_KEY_POSITION_COUNT = "okx:arbitrage:position:count";  // 全局持仓计数器
    
    private transient ValueState<PositionState> positionState;
    private transient ValueState<OpportunityTracker> opportunityTracker;
    private transient ValueState<Long> lastStatusLogTime;  // 最后一次状态日志输出时间
    private transient ValueState<String> currentSymbol;    // 当前币对名称
    private transient ValueState<ArbitrageOpportunity> currentOpportunity;  // 当前套利机会（用于状态日志）
    
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
        this.maxPositions = config.getInt("arbitrage.trading.max-positions", 1);  // 读取最大持仓数量配置,默认1个
        
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
        
        // 初始化 Redis 连接管理器（用于全局持仓计数）
        redisManager = new RedisConnectionManager(config);
        logger.info("✓ Redis 连接管理器初始化成功");
        
        // ⭐ 清空全局持仓计数器，防止历史遗留数据影响持仓限制
        try {
            long deleted = redisManager.del(REDIS_KEY_POSITION_COUNT);
            if (deleted > 0) {
                logger.info("🧹 已清空全局持仓计数器: {} (删除了 {} 个key)", REDIS_KEY_POSITION_COUNT, deleted);
            } else {
                logger.info("ℹ️ 全局持仓计数器不存在或已为空: {}", REDIS_KEY_POSITION_COUNT);
            }
        } catch (Exception e) {
            logger.error("❌ 清空全局持仓计数器失败: {}", e.getMessage(), e);
        }
        
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
        
        // 订单详细信息日志表头（包含成交时间、手续费等）
        String[] orderDetailFullHeaders = {
            "timestamp", "symbol", "order_id", "inst_type", "inst_id", "side", "pos_side",
            "order_type", "price", "avg_price", "size", "filled_size", "state",
            "fee", "fee_ccy", "fill_time", "create_time", "update_time"
        };
        orderDetailFullWriter = new SyncCsvWriter(logDir, "order_detail_full", orderDetailFullHeaders, 2);
        
        // 初始化订单查询线程池（固定2个线程）
        orderQueryExecutor = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
            Thread t = new Thread(r);
            t.setName("order-query-thread");
            t.setDaemon(true);  // 设置为守护线程
            return t;
        });
        
        // 初始化持仓状态
        ValueStateDescriptor<PositionState> positionDescriptor = new ValueStateDescriptor<>(
            "position-state-v3",
            PositionState.class
        );
        positionState = getRuntimeContext().getState(positionDescriptor);
        
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
        
        // 初始化当前套利机会状态
        ValueStateDescriptor<ArbitrageOpportunity> opportunityDescriptor = new ValueStateDescriptor<>(
            "current-opportunity",
            ArbitrageOpportunity.class
        );
        currentOpportunity = getRuntimeContext().getState(opportunityDescriptor);
        
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
        logger.info("  最大持仓数量: {} 个", maxPositions);
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
        // 关闭订单查询线程池
        if (orderQueryExecutor != null) {
            orderQueryExecutor.shutdown();
            try {
                // 等待最多10秒让任务完成
                if (!orderQueryExecutor.awaitTermination(10, java.util.concurrent.TimeUnit.SECONDS)) {
                    orderQueryExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                orderQueryExecutor.shutdownNow();
            }
        }
        
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
        if (orderDetailFullWriter != null) {
            orderDetailFullWriter.close();
        }
        
        // 关闭杠杆支持信息缓存
        if (marginCache != null) {
            marginCache.close();
        }
        
        // 关闭 Redis 连接管理器
        if (redisManager != null) {
            redisManager.close();
        }
        
        logger.info("TradingDecisionProcessor 已关闭");
    }
    
    @Override
    public void processElement(
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
        
        // 保存当前套利机会（用于状态日志显示当前价差）
        currentOpportunity.update(opportunity);
        
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
                        logger.debug("⏰ 套利机会持续 {} 秒,满足开仓条件", String.format("%.1f", durationSec));
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
            // 检查是否是当前持仓的币种
            if (!opportunity.symbol.equals(position.getSymbol())) {
                // 不是当前持仓的币种,跳过处理
                // 注意: 由于使用 keyBy(symbol),每个币种的数据会路由到不同的 key
                // 但由于状态是按 key 存储的,这里不应该出现不同币种的情况
                // 如果出现,说明状态管理有问题
                logger.warn("⚠️ 收到非持仓币种的数据: 当前持仓={}, 收到数据={}", 
                    position.getSymbol(), opportunity.symbol);
                return;
            }
            
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
        ArbitrageOpportunity opportunity = currentOpportunity.value();  // 获取当前套利机会
        
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
            printStatusLog(symbol, position, tracker, opportunity, now);  // 传入当前套利机会
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
        
        // 计算未实现利润 = 价差变化 × 币数量
        BigDecimal unrealizedProfitFromSpread = spreadDiff.multiply(pos.getAmount());
        
        // 计算手续费（基于 USDT 金额）
        BigDecimal usdtAmount = pos.getAmount().multiply(pos.getEntrySpotPrice());
        BigDecimal fee = usdtAmount.multiply(new BigDecimal("0.002"));
        
        // 最终未实现利润
        BigDecimal unrealizedProfit = unrealizedProfitFromSpread.subtract(fee);
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
        
        // 计算未实现利润 = 价差变化 × 币数量
        BigDecimal unrealizedProfitFromSpread = spreadDiff.multiply(pos.getAmount());
        
        // 计算手续费（基于 USDT 金额）
        BigDecimal usdtAmount = pos.getAmount().multiply(pos.getEntrySpotPrice());
        BigDecimal fee = usdtAmount.multiply(new BigDecimal("0.002"));
        
        // 最终未实现利润 = 价差利润 - 手续费
        BigDecimal unrealizedProfit = unrealizedProfitFromSpread.subtract(fee);
        
        // 计算利润率（基于 USDT 金额）
        BigDecimal profitRate = unrealizedProfit.divide(usdtAmount, 6, RoundingMode.HALF_UP)
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
        
        // ⭐ 第一步：检查策略支持（在检查持仓数量之前）
        // 构造标准交易对格式（如 "BTC-USDT"）
        String standardSymbol = opp.symbol + "-USDT";
        
        // 判断套利方向对应的策略
        boolean isStrategyA = opp.arbitrageDirection.contains("做空现货");  // 策略A：现货卖出 + 合约买入（需要杠杆）
        boolean isStrategyB = opp.arbitrageDirection.contains("做多现货");  // 策略B：现货买入 + 合约卖出（不需要杠杆）
        
        // 检查策略支持
        if (isStrategyA && !marginCache.supportsStrategyA(standardSymbol)) {
            logger.warn("⚠️ 币种 {} 不支持策略A（现货卖出+合约买入，需要杠杆借币），拒绝开仓", opp.symbol);
            
            // 写入拒绝日志
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String rejectLog = String.format("%s,%s,OPEN,REJECTED,N/A,N/A,N/A,N/A,REJECTED,不支持策略A（需要杠杆借币）",
                timestamp, opp.symbol);
            orderDetailWriter.write(rejectLog);
            return;
        }
        
        if (isStrategyB && !marginCache.supportsStrategyB(standardSymbol)) {
            logger.warn("⚠️ 币种 {} 不支持策略B（现货买入+合约卖出），拒绝开仓", opp.symbol);
            
            // 写入拒绝日志
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String rejectLog = String.format("%s,%s,OPEN,REJECTED,N/A,N/A,N/A,N/A,REJECTED,不支持策略B",
                timestamp, opp.symbol);
            orderDetailWriter.write(rejectLog);
            return;
        }
        
        // 记录策略信息
        if (isStrategyA) {
            int leverage = marginCache.getLeverage(standardSymbol);
            logger.info("✅ 策略A检查通过: {} | 杠杆倍数: {}x", opp.symbol, leverage);
        } else if (isStrategyB) {
            logger.info("✅ 策略B检查通过: {} | 不需要杠杆", opp.symbol);
        }
        
        // ⭐ 第二步：检查全局持仓数量
        try {
            long currentPositionCount = redisManager.getCounter(REDIS_KEY_POSITION_COUNT);
            logger.info("📊 当前全局持仓数量: {} / {}", currentPositionCount, maxPositions);
            
            if (currentPositionCount >= maxPositions) {
                logger.debug("⚠️ 全局持仓数量已达上限 ({}/{}),拒绝开仓: {}",
                    currentPositionCount, maxPositions, opp.symbol);
                
                // 写入拒绝日志
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String rejectLog = String.format("%s,%s,OPEN,REJECTED,N/A,N/A,N/A,N/A,REJECTED,全局持仓数量已达上限(%d/%d)",
                    timestamp, opp.symbol, currentPositionCount, maxPositions);
//                orderDetailWriter.write(rejectLog);
                return;
            }
            
            // 先增加全局持仓计数（乐观锁）
            long newCount = redisManager.incr(REDIS_KEY_POSITION_COUNT);
            logger.info("✅ 全局持仓计数已增加: {} -> {}", currentPositionCount, newCount);
            
        } catch (Exception e) {
            logger.error("❌ 检查全局持仓数量失败: {}", e.getMessage(), e);
            // Redis 操作失败不影响主流程,继续执行
        }
        
        String spotOrderId = null;
        String swapOrderId = null;
        String direction = null;
        boolean positionCountIncremented = true;  // 标记是否已增加计数（用于失败时回滚）
        
        try {
            // 计算符合合约要求的币数量
            // 1. 根据 USDT 金额和价格计算币数量
            // 2. 按合约最小下单单位（ctVal）向上取整
            // 3. 确保符合合约 lot size 要求
            BigDecimal coinAmount = tradingService.calculateValidCoinAmount(opp.symbol, tradeAmount, opp.spotPrice);
            
            if (coinAmount == null) {
                logger.error("❌ 无法计算有效的币数量，取消下单");
                recordFailure(opp.symbol, "无法计算有效的币数量");
                
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                String errorLog = String.format("%s,%s,OPEN,%s,ERROR,N/A,N/A,N/A,FAILED,无法计算有效的币数量",
                    timestamp, opp.symbol, "UNKNOWN");
                orderDetailWriter.write(errorLog);
                return;
            }
            
            logger.info("💰 交易金额: {} USDT | 现货价格: {} | 计算币数量: {} {}", 
                tradeAmount, opp.spotPrice, coinAmount, opp.symbol);
            
            if (opp.arbitrageDirection.contains("做多现货")) {
                // 策略 A: 做多现货 + 做空合约（使用配置的杠杆倍数）
                direction = "LONG_SPOT_SHORT_SWAP";
                
                // 现货和合约都使用相同的币数量下单
                // 先下现货单（传入币数量）
                spotOrderId = tradingService.buySpot(opp.symbol, coinAmount, opp.spotPrice);
                
                // 检查现货单是否成功
                if (spotOrderId == null) {
                    logger.error("❌ 现货下单失败,取消合约下单");
                    recordFailure(opp.symbol, "现货下单失败");
                    
                    // ⭐ 回滚全局持仓计数
                    try {
                        long newCount = redisManager.decr(REDIS_KEY_POSITION_COUNT);
                        logger.info("🔄 现货下单失败,已回滚全局持仓计数: {}", newCount);
                    } catch (Exception e) {
                        logger.error("❌ 回滚全局持仓计数失败: {}", e.getMessage());
                    }
                    
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
                
                // 现货和合约都使用相同的币数量下单
                // 先下现货单（传入币数量，使用配置的杠杆倍数）
                spotOrderId = tradingService.sellSpot(opp.symbol, coinAmount, opp.spotPrice, this.leverage);
                
                // 检查现货单是否成功
                if (spotOrderId == null) {
                    logger.error("❌ 现货下单失败,取消合约下单");
                    
                    // ⭐ 回滚全局持仓计数
                    try {
                        long newCount = redisManager.decr(REDIS_KEY_POSITION_COUNT);
                        logger.info("🔄 现货下单失败,已回滚全局持仓计数: {}", newCount);
                    } catch (Exception e) {
                        logger.error("❌ 回滚全局持仓计数失败: {}", e.getMessage());
                    }
                    
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
                // 两个订单都成功,记录订单ID
                logger.info("📝 订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
                
                // 立即创建临时持仓状态,防止重复开仓
                PositionState tempPosition = new PositionState();
                tempPosition.setSymbol(opp.symbol);
                tempPosition.setOpen(true);
                tempPosition.setDirection(direction);
                tempPosition.setAmount(coinAmount);  // 保存币的数量（用于平仓）
                tempPosition.setEntrySpotPrice(opp.spotPrice);
                tempPosition.setEntrySwapPrice(opp.futuresPrice);
                tempPosition.setEntrySpreadRate(opp.spreadRate);  // 保存开仓价差率
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
                
                // ⭐ 立即启动异步任务查询订单明细（不依赖 WebSocket 推送）
                final String finalSymbol = opp.symbol;
                final String finalSpotOrderId = spotOrderId;
                final String finalSwapOrderId = swapOrderId;
                
                // 延迟 2 秒后查询（给订单成交留出时间）
                orderQueryExecutor.submit(() -> {
                    try {
                        Thread.sleep(2000);  // 等待 2 秒，让订单有时间成交
                        
                        logger.info("🔍 开始主动查询订单详情: symbol={}, spotOrderId={}, swapOrderId={}", 
                            finalSymbol, finalSpotOrderId, finalSwapOrderId);
                        
                        // 查询现货订单详情
                        try {
                            logger.info("📡 正在查询现货订单详情: orderId={}", finalSpotOrderId);
                            // 构造现货交易对名称: symbol-USDT (如 BTC-USDT)
                            String spotInstId = finalSymbol + "-USDT";
                            com.fasterxml.jackson.databind.JsonNode spotDetail = 
                                tradingService.queryOrderDetail(finalSpotOrderId, spotInstId, "SPOT");
                            if (spotDetail != null) {
                                logger.info("✅ 现货订单详情查询成功,准备保存到CSV: orderId={}", finalSpotOrderId);
                                saveOrderDetailToCsv(finalSymbol, spotDetail, "SPOT");
                            } else {
                                logger.warn("⚠️ 现货订单详情为空,无法保存: orderId={}", finalSpotOrderId);
                            }
                        } catch (Exception e) {
                            logger.error("❌ 查询现货订单详情失败: orderId={}, error={}", 
                                finalSpotOrderId, e.getMessage(), e);
                        }
                        
                        // 查询合约订单详情
                        try {
                            logger.info("📡 正在查询合约订单详情: orderId={}", finalSwapOrderId);
                            // 构造合约交易对名称: symbol-USDT-SWAP (如 BTC-USDT-SWAP)
                            String swapInstId = finalSymbol + "-USDT-SWAP";
                            com.fasterxml.jackson.databind.JsonNode swapDetail = 
                                tradingService.queryOrderDetail(finalSwapOrderId, swapInstId, "SWAP");
                            if (swapDetail != null) {
                                logger.info("✅ 合约订单详情查询成功,准备保存到CSV: orderId={}", finalSwapOrderId);
                                saveOrderDetailToCsv(finalSymbol, swapDetail, "SWAP");
                            } else {
                                logger.warn("⚠️ 合约订单详情为空,无法保存: orderId={}", finalSwapOrderId);
                            }
                        } catch (Exception e) {
                            logger.error("❌ 查询合约订单详情失败: orderId={}, error={}", 
                                finalSwapOrderId, e.getMessage(), e);
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.error("❌ 订单查询任务被中断", e);
                    }
                });
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
                
                // ⚠️ 注意：现货已成交，不回滚全局持仓计数
                // 创建临时持仓状态，防止继续下单导致持仓超过限制
                PositionState tempPosition = new PositionState();
                tempPosition.setSymbol(opp.symbol);
                tempPosition.setOpen(true);  // 标记为持仓中
                tempPosition.setDirection(direction);
                tempPosition.setAmount(coinAmount);  // 保存币的数量（用于平仓）
                tempPosition.setEntrySpotPrice(opp.spotPrice);
                tempPosition.setEntrySwapPrice(BigDecimal.ZERO);  // 合约未成交，设置为0
                tempPosition.setEntrySpreadRate(opp.spreadRate);  // 保存开仓价差率
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
                
                // ⭐ 回滚全局持仓计数
                if (positionCountIncremented) {
                    try {
                        long newCount = redisManager.decr(REDIS_KEY_POSITION_COUNT);
                        logger.info("🔄 订单提交失败,已回滚全局持仓计数: {}", newCount);
                    } catch (Exception e) {
                        logger.error("❌ 回滚全局持仓计数失败: {}", e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            logger.error("❌ 开仓失败: {}", e.getMessage(), e);
            
            // ⭐ 回滚全局持仓计数
            if (positionCountIncremented) {
                try {
                    long newCount = redisManager.decr(REDIS_KEY_POSITION_COUNT);
                    logger.info("🔄 开仓异常,已回滚全局持仓计数: {}", newCount);
                } catch (Exception ex) {
                    logger.error("❌ 回滚全局持仓计数失败: {}", ex.getMessage());
                }
            }
            
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
                // 订单提交成功,记录日志
                logger.info("📝 平仓订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
                
                // ⭐ 平仓成功,减少全局持仓计数
                try {
                    long newCount = redisManager.decr(REDIS_KEY_POSITION_COUNT);
                    logger.info("✅ 平仓成功,已减少全局持仓计数: {}", newCount);
                } catch (Exception e) {
                    logger.error("❌ 减少全局持仓计数失败: {}", e.getMessage());
                }
                
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
    
    
    /**
     * 保存订单详细信息到CSV文件
     * 包含成交时间、成交价格、手续费等详细信息
     */
    private void saveOrderDetailToCsv(String symbol, com.fasterxml.jackson.databind.JsonNode orderDetail, String instType) {
        try {
            logger.info("📝 开始保存订单详细信息到CSV: symbol={}, instType={}", symbol, instType);
            
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String orderId = orderDetail.path("ordId").asText();
            String instId = orderDetail.path("instId").asText();
            String side = orderDetail.path("side").asText();
            String posSide = orderDetail.path("posSide").asText();
            String orderType = orderDetail.path("ordType").asText();
            String price = orderDetail.path("px").asText();
            String avgPrice = orderDetail.path("avgPx").asText();
            String size = orderDetail.path("sz").asText();
            String filledSize = orderDetail.path("accFillSz").asText();
            String state = orderDetail.path("state").asText();
            String fee = orderDetail.path("fee").asText();
            String feeCcy = orderDetail.path("feeCcy").asText();
            String fillTime = orderDetail.path("fillTime").asText();
            String createTime = orderDetail.path("cTime").asText();
            String updateTime = orderDetail.path("uTime").asText();
            
            // 转换时间戳为可读格式
            String fillTimeStr = convertTimestamp(fillTime);
            String createTimeStr = convertTimestamp(createTime);
            String updateTimeStr = convertTimestamp(updateTime);
            
            // 写入CSV
            String csvLine = String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s",
                timestamp, symbol, orderId, instType, instId, side, posSide,
                orderType, price, avgPrice, size, filledSize, state,
                fee, feeCcy, fillTimeStr, createTimeStr, updateTimeStr);
            
            // 确保 orderDetailFullWriter 不为空
            if (orderDetailFullWriter == null) {
                logger.error("❌ orderDetailFullWriter 为空,无法写入CSV!");
                return;
            }
            
            orderDetailFullWriter.write(csvLine);
            
            logger.info("✅ 已保存订单详细信息到CSV: symbol={}, orderId={}, instType={}, avgPrice={}, fee={} {}", 
                symbol, orderId, instType, avgPrice, fee, feeCcy);
            
        } catch (Exception e) {
            logger.error("❌ 保存订单详细信息失败: symbol={}, instType={}, error={}", 
                symbol, instType, e.getMessage(), e);
        }
    }
    
    /**
     * 转换时间戳为可读格式
     * OKX返回的时间戳是毫秒级的
     */
    private String convertTimestamp(String timestamp) {
        try {
            if (timestamp == null || timestamp.isEmpty() || "0".equals(timestamp)) {
                return "N/A";
            }
            long ts = Long.parseLong(timestamp);
            return LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(ts), 
                java.time.ZoneId.systemDefault()
            ).format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return timestamp;  // 转换失败,返回原始值
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



    
    /**
     * 定期输出状态日志
     * 每30秒输出一次当前币对的状态信息
     */
    /**
     * 打印状态日志（每5秒输出一次）
     * 包含持仓状态、预估利润率、当前价差、套利机会跟踪等信息
     */
    private void printStatusLog(String symbol, PositionState position, OpportunityTracker tracker, 
                                ArbitrageOpportunity opportunity, long now) throws Exception {
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
            // 修复：amount 存储的是币的数量，不是 USDT 金额
            // 计算实际的 USDT 金额 = 币数量 × 现货价格
            BigDecimal positionUsdtAmount = position.getAmount().multiply(position.getEntrySpotPrice());
            status.append(String.format("  金额: %s %s (约 %s USDT)\n", 
                position.getAmount().setScale(4, RoundingMode.HALF_UP), 
                symbol,
                positionUsdtAmount.setScale(2, RoundingMode.HALF_UP)));
            status.append(String.format("  开仓价格: 现货=%s, 合约=%s\n", 
                position.getEntrySpotPrice(), position.getEntrySwapPrice()));
            // 修复：显示实际的开仓价差率（entrySpreadRate 已经是百分比值，不需要再乘以 100）
            if (position.getEntrySpreadRate() != null) {
                status.append(String.format("  开仓价差率: %s%%\n", 
                    position.getEntrySpreadRate().setScale(3, RoundingMode.HALF_UP)));
            } else {
                status.append("  开仓价差率: 未记录\n");
            }
            
            // ⭐ 新增：显示当前价差（如果有最新的套利机会数据）
            if (opportunity != null) {
                // 使用科学计数法或自适应精度显示价格
                String spotPriceStr = formatPrice(opportunity.spotPrice);
                String futuresPriceStr = formatPrice(opportunity.futuresPrice);
                // spreadRate 已经是百分比值，直接显示即可
                status.append(String.format("  📊 当前价差: 现货=%s, 合约=%s, 价差率=%s%%\n",
                    spotPriceStr,
                    futuresPriceStr,
                    opportunity.spreadRate.setScale(3, RoundingMode.HALF_UP)));
            }
            
            // ⭐ 重点：每5秒打印预估利润率
            if (position.getUnrealizedProfit() != null) {
                // 计算利润率（基于 USDT 金额）
                BigDecimal profitRate = position.getUnrealizedProfit()
                    .divide(positionUsdtAmount, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
                status.append(String.format("  💰 预估利润: %s USDT (利润率: %s%%)\n", 
                    position.getUnrealizedProfit().setScale(4, RoundingMode.HALF_UP),
                    profitRate.setScale(2, RoundingMode.HALF_UP)));
            } else {
                status.append("  💰 预估利润: 计算中...\n");
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
    
    /**
     * 格式化价格显示（自适应精度）
     * 对于非常小的价格（如 SATS），使用科学计数法或更高精度
     */
    private String formatPrice(BigDecimal price) {
        if (price == null) {
            return "N/A";
        }
        
        // 保持原始精度,不截断不四舍五入
        // 移除尾部的零,但保留完整的有效数字
        return price.stripTrailingZeros().toPlainString();
    }
}
