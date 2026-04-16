package com.crypto.dw.processor;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.async.SyncCsvWriter;
import com.crypto.dw.model.ArbitrageOpportunity;

import com.crypto.dw.model.TradeRecord;
import com.crypto.dw.redis.RedisConnectionManager;
import com.crypto.dw.trading.MarginSupportCache;
import com.crypto.dw.trading.OKXTradingService;
import com.crypto.dw.trading.OpportunityTracker;
import com.crypto.dw.trading.PositionState;
import com.crypto.dw.utils.SpreadRateUtils;
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
    private transient SyncCsvWriter statusLogWriter;    // ⭐ 状态日志（用于验证数据计算准确性）
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
        
        // ⭐ 状态日志表头（用于验证数据计算准确性）
        String[] statusLogHeaders = {
            "timestamp", "symbol", "position_status", "direction", "hold_time_seconds",
            "amount_coin", "amount_usdt", "entry_spot_price", "entry_swap_price", "entry_spread_rate",
            "current_spot_price", "current_swap_price", "current_spread_rate",
            "spot_cost", "futures_cost", "total_cost", "total_fee", "total_expense",
            "unrealized_profit", "profit_rate", "spot_order_id", "swap_order_id",
            "tracker_active", "tracker_spread_rate", "tracker_duration_seconds",
            "trading_enabled", "trade_amount", "open_threshold", "close_threshold",
            "max_hold_time_minutes", "max_loss"
        };
        statusLogWriter = new SyncCsvWriter(logDir, "status_log", statusLogHeaders, 2);
        
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
        if (statusLogWriter != null) {
            statusLogWriter.close();
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
            
            // ⭐ 先按订单ID主动查询并回填真实成交明细（主线程执行，确保Flink状态线程安全）
            syncOrderDetailsFromOrderIds(position);
            
            // ⭐ 兼容旧链路：如果 ArbitrageOpportunity 包含订单详情，也同步到 PositionState
            syncOrderDetailsToPosition(opportunity, position);
            
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
            out.collect(buildPositionStatusRecord(symbol, position, tracker, opportunity, now));
        }
        
        // 注册下一个定时器,实现持续定时输出
        long nextTimerTime = now + STATUS_LOG_INTERVAL_MS;
        ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
    }
    
    /**
     * 根据持仓中的订单ID主动查询订单详情并回填到 PositionState。
     * 说明：必须在 Flink 主线程调用，避免在异步线程直接操作 state。
     */
    private void syncOrderDetailsFromOrderIds(PositionState position) throws Exception {
        if (position == null || !position.isOpen()) {
            return;
        }
        
        // 两边都已有真实成交信息时直接跳过
        if (position.getActualSpotPrice() != null
            && position.getActualSwapPrice() != null
            && position.getActualSpotFilledQuantity() != null
            && position.getActualSwapFilledCoin() != null) {
            return;
        }
        
        String symbol = position.getSymbol();
        String spotOrderId = position.getSpotOrderId();
        String swapOrderId = position.getSwapOrderId();
        if (symbol == null || spotOrderId == null || swapOrderId == null) {
            return;
        }
        
        try {
            // 查询现货订单详情
            String spotInstId = symbol + "-USDT";
            com.fasterxml.jackson.databind.JsonNode spotDetail =
                tradingService.queryOrderDetail(spotOrderId, spotInstId, "SPOT");
            if (spotDetail != null) {
                BigDecimal spotAvgPx = new BigDecimal(spotDetail.path("avgPx").asText("0"));
                BigDecimal spotFillCoin = new BigDecimal(spotDetail.path("accFillSz").asText("0"));
                BigDecimal spotFee = new BigDecimal(spotDetail.path("fee").asText("0")).abs();
                
                if (spotAvgPx.compareTo(BigDecimal.ZERO) > 0 && spotFillCoin.compareTo(BigDecimal.ZERO) > 0) {
                    position.setActualSpotPrice(spotAvgPx);
                    position.setActualSpotFilledQuantity(spotFillCoin);
                    position.setSpotCost(spotAvgPx.multiply(spotFillCoin));
                    position.setSpotFee(spotFee);
                }
            }
            
            // 查询合约订单详情
            String swapInstId = symbol + "-USDT-SWAP";
            com.fasterxml.jackson.databind.JsonNode swapDetail =
                tradingService.queryOrderDetail(swapOrderId, swapInstId, "SWAP");
            if (swapDetail != null) {
                BigDecimal swapAvgPx = new BigDecimal(swapDetail.path("avgPx").asText("0"));
                BigDecimal swapFillContracts = new BigDecimal(swapDetail.path("accFillSz").asText("0"));
                BigDecimal swapFee = new BigDecimal(swapDetail.path("fee").asText("0")).abs();
                
                // 合约成交量是“张”，需要乘 ctVal 折算成币数量
                OKXTradingService.InstrumentInfo info = tradingService.getInstrumentInfo(symbol);
                BigDecimal ctVal = (info != null && info.ctVal != null) ? info.ctVal : BigDecimal.ONE;
                BigDecimal swapFillCoin = swapFillContracts.multiply(ctVal);
                
                if (swapAvgPx.compareTo(BigDecimal.ZERO) > 0 && swapFillContracts.compareTo(BigDecimal.ZERO) > 0) {
                    position.setActualSwapPrice(swapAvgPx);
                    position.setActualSwapFilledContracts(swapFillContracts);
                    position.setActualSwapFilledCoin(swapFillCoin);
                    position.setFuturesCost(swapAvgPx.multiply(swapFillCoin));
                    position.setFuturesFee(swapFee);
                }
            }
            
            // 汇总真实成本与手续费
            if (position.getSpotCost() != null && position.getFuturesCost() != null) {
                position.setTotalCost(position.getSpotCost().add(position.getFuturesCost()));
            }
            if (position.getSpotFee() != null && position.getFuturesFee() != null) {
                position.setTotalFee(position.getSpotFee().add(position.getFuturesFee()));
            }
            if (position.getTotalCost() != null && position.getTotalFee() != null) {
                position.setTotalExpense(position.getTotalCost().add(position.getTotalFee()));
            }
            
            positionState.update(position);
        } catch (Exception e) {
            logger.warn("⚠️ 按订单ID同步真实成交明细失败: symbol={}, error={}", position.getSymbol(), e.getMessage());
        }
    }
    
    /**
     * 同步订单详情到 PositionState
     * 从 ArbitrageOpportunity 中提取订单详情并更新到 PositionState
     * 
     * @param opp ArbitrageOpportunity 对象
     * @param position PositionState 对象
     */
    private void syncOrderDetailsToPosition(ArbitrageOpportunity opp, PositionState position) throws Exception {
        // 检查是否有订单详情需要同步
        boolean hasSpotDetail = opp.spotFillPrice != null && opp.spotFillPrice.compareTo(BigDecimal.ZERO) > 0;
        boolean hasFuturesDetail = opp.futuresFillPrice != null && opp.futuresFillPrice.compareTo(BigDecimal.ZERO) > 0;
        
        // 检查是否已经同步过（避免重复同步）
        boolean alreadySynced = position.getActualSpotPrice() != null && position.getActualSwapPrice() != null;
        
        if ((hasSpotDetail || hasFuturesDetail) && !alreadySynced) {
            logger.info("🔄 开始同步订单详情到 PositionState: symbol={}", opp.symbol);
            
            // 同步现货订单详情
            if (hasSpotDetail) {
                position.setActualSpotPrice(opp.spotFillPrice);
                // 使用订单实际成交数量（币）回填，避免部分成交时仍使用目标数量
                position.setActualSpotFilledQuantity(opp.spotFillQuantity);
                position.setSpotCost(opp.spotCost);
                position.setSpotFee(opp.spotFee);
                logger.info("✅ 现货详情已同步: actualPrice={}, cost={}, fee={}", 
                    opp.spotFillPrice, opp.spotCost, opp.spotFee);
            }
            
            // 同步合约订单详情
            if (hasFuturesDetail) {
                position.setActualSwapPrice(opp.futuresFillPrice);
                // 合约成交量同时保存“张”和“折算币数量”
                position.setActualSwapFilledContracts(opp.futuresFillQuantity);
                if (opp.spotFillQuantity != null && opp.futuresFillQuantity != null
                        && opp.futuresFillQuantity.compareTo(BigDecimal.ZERO) > 0) {
                    BigDecimal inferredCtVal = opp.spotFillQuantity.divide(
                        opp.futuresFillQuantity, 8, RoundingMode.HALF_UP);
                    position.setActualSwapFilledCoin(opp.futuresFillQuantity.multiply(inferredCtVal));
                }
                position.setFuturesCost(opp.futuresCost);
                position.setFuturesFee(opp.futuresFee);
                logger.info("✅ 合约详情已同步: actualPrice={}, cost={}, fee={}", 
                    opp.futuresFillPrice, opp.futuresCost, opp.futuresFee);
            }
            
            // 计算总成本和总费用
            if (hasSpotDetail && hasFuturesDetail) {
                position.setTotalCost(opp.totalCost);
                position.setTotalFee(opp.totalFee);
                position.setTotalExpense(opp.totalExpense);
                logger.info("✅ 总成本已同步: totalCost={}, totalFee={}, totalExpense={}", 
                    opp.totalCost, opp.totalFee, opp.totalExpense);
            }
            
            // 更新状态
            positionState.update(position);
            logger.info("✅ PositionState 已更新: symbol={}", opp.symbol);
        }
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
        
        // 条件 3: 止损（使用真实成交口径）
        BigDecimal spotEntry = pos.getActualSpotPrice() != null ? pos.getActualSpotPrice() : pos.getEntrySpotPrice();
        BigDecimal swapEntry = pos.getActualSwapPrice() != null ? pos.getActualSwapPrice() : pos.getEntrySwapPrice();
        BigDecimal spotFillCoin = pos.getActualSpotFilledQuantity() != null ? pos.getActualSpotFilledQuantity() : pos.getAmount();
        BigDecimal swapFillCoin = pos.getActualSwapFilledCoin() != null ? pos.getActualSwapFilledCoin() : pos.getAmount();
        BigDecimal hedgedCoin = spotFillCoin.min(swapFillCoin);
        
        BigDecimal totalFee = pos.getTotalFee();
        if (totalFee == null) {
            BigDecimal usdtAmountFallback = pos.getAmount().multiply(pos.getEntrySpotPrice());
            totalFee = usdtAmountFallback.multiply(new BigDecimal("0.002"));
        }
        
        BigDecimal unrealizedProfit;
        if ("SHORT_SPOT_LONG_SWAP".equals(pos.getDirection())) {
            BigDecimal spotPnL = spotEntry.subtract(opp.spotPrice).multiply(hedgedCoin);
            BigDecimal swapPnL = opp.futuresPrice.subtract(swapEntry).multiply(hedgedCoin);
            unrealizedProfit = spotPnL.add(swapPnL).subtract(totalFee);
        } else {
            BigDecimal spotPnL = opp.spotPrice.subtract(spotEntry).multiply(hedgedCoin);
            BigDecimal swapPnL = swapEntry.subtract(opp.futuresPrice).multiply(hedgedCoin);
            unrealizedProfit = spotPnL.add(swapPnL).subtract(totalFee);
        }
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
        
        // 优先使用真实成交口径：已对冲数量 + 实际成交价 + 实际手续费
        BigDecimal spotEntry = pos.getActualSpotPrice() != null ? pos.getActualSpotPrice() : pos.getEntrySpotPrice();
        BigDecimal swapEntry = pos.getActualSwapPrice() != null ? pos.getActualSwapPrice() : pos.getEntrySwapPrice();
        BigDecimal spotFillCoin = pos.getActualSpotFilledQuantity() != null ? pos.getActualSpotFilledQuantity() : pos.getAmount();
        BigDecimal swapFillCoin = pos.getActualSwapFilledCoin() != null ? pos.getActualSwapFilledCoin() : pos.getAmount();
        
        // 只按已对冲数量计算套利利润，未对冲数量单独作为风险敞口
        BigDecimal hedgedCoin = spotFillCoin.min(swapFillCoin);
        BigDecimal unhedgedCoin = spotFillCoin.subtract(swapFillCoin).abs();
        
        // 真实手续费优先；缺失时回退旧逻辑
        BigDecimal totalFee = pos.getTotalFee();
        if (totalFee == null) {
            BigDecimal usdtAmountFallback = pos.getAmount().multiply(pos.getEntrySpotPrice());
            totalFee = usdtAmountFallback.multiply(new BigDecimal("0.002"));
        }
        
        BigDecimal unrealizedProfit;
        if ("SHORT_SPOT_LONG_SWAP".equals(pos.getDirection())) {
            BigDecimal spotPnL = spotEntry.subtract(opp.spotPrice).multiply(hedgedCoin);
            BigDecimal swapPnL = opp.futuresPrice.subtract(swapEntry).multiply(hedgedCoin);
            unrealizedProfit = spotPnL.add(swapPnL).subtract(totalFee);
        } else {
            BigDecimal spotPnL = opp.spotPrice.subtract(spotEntry).multiply(hedgedCoin);
            BigDecimal swapPnL = swapEntry.subtract(opp.futuresPrice).multiply(hedgedCoin);
            unrealizedProfit = spotPnL.add(swapPnL).subtract(totalFee);
        }
        
        // 投入金额按真实总成本优先
        BigDecimal investAmount = pos.getTotalCost();
        if (investAmount == null || investAmount.compareTo(BigDecimal.ZERO) <= 0) {
            investAmount = spotEntry.multiply(hedgedCoin);
        }
        BigDecimal profitRate = BigDecimal.ZERO;
        if (investAmount.compareTo(BigDecimal.ZERO) > 0) {
            profitRate = unrealizedProfit.divide(investAmount, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
        }
        
        // 更新持仓状态
        pos.setUnrealizedProfit(unrealizedProfit);
        pos.setLastUpdateTime(System.currentTimeMillis());
        positionState.update(pos);
        
        // 定期输出日志(每10秒输出一次)
        long now = System.currentTimeMillis();
        if (now - pos.getLastLogTime() > 10000) {
            logger.info("📊 持仓更新: {} | 方向: {} | 未实现利润: {} USDT ({} %) | 已对冲: {} | 未对冲: {} | 持仓时间: {} 秒",
                pos.getSymbol(),
                pos.getDirection(),
                unrealizedProfit.setScale(4, RoundingMode.HALF_UP),
                profitRate.setScale(2, RoundingMode.HALF_UP),
                hedgedCoin.setScale(8, RoundingMode.HALF_UP),
                unhedgedCoin.setScale(8, RoundingMode.HALF_UP),
                (now - pos.getOpenTime()) / 1000);
            pos.setLastLogTime(now);
            positionState.update(pos);
        }
    }
    
    private void openPosition(ArbitrageOpportunity opp, Collector<TradeRecord> out) 
            throws Exception {
        
        logger.debug("🎯 开仓: {} | 价差率: {}%", opp.symbol, opp.spreadRate);
        
        // ⭐ 第一步：检查策略支持（在检查持仓数量之前）
        // 构造标准交易对格式（如 "BTC-USDT"）
        String standardSymbol = opp.symbol + "-USDT";
        
        // 判断套利方向对应的策略
        boolean isStrategyA = opp.arbitrageDirection.contains("做空现货");  // 策略A：现货卖出 + 合约买入（需要杠杆）
        boolean isStrategyB = opp.arbitrageDirection.contains("做多现货");  // 策略B：现货买入 + 合约卖出（不需要杠杆）
        
        // 检查策略支持
        if (isStrategyA && !marginCache.supportsStrategyA(standardSymbol)) {
            logger.debug("⚠️ 币种 {} 不支持策略A（现货卖出+合约买入，需要杠杆借币），拒绝开仓", opp.symbol);
            return;
        }
        
        if (isStrategyB && !marginCache.supportsStrategyB(standardSymbol)) {
            logger.warn("⚠️ 币种 {} 不支持策略B（现货买入+合约卖出），拒绝开仓", opp.symbol);
            return;
        }
        
        // ⭐ 第二步：检查全局持仓数量
        try {
            long currentPositionCount = redisManager.getCounter(REDIS_KEY_POSITION_COUNT);
            
            if (currentPositionCount >= maxPositions) {
                logger.debug("⚠️ 全局持仓数量已达上限 ({}/{}),拒绝开仓: {}",
                    currentPositionCount, maxPositions, opp.symbol);
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
                out.collect(buildOpenPendingRecord(opp, direction, spotOrderId, swapOrderId, coinAmount));
                
                // ⭐ 立即启动异步任务查询订单明细（不依赖 WebSocket 推送）
                final String finalSymbol = opp.symbol;
                final String finalSpotOrderId = spotOrderId;
                final String finalSwapOrderId = swapOrderId;
                final ArbitrageOpportunity finalOpp = opp;  // 传递 ArbitrageOpportunity 对象
                final String finalDirection = direction;  // 传递交易方向
                
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
                                logger.info("✅ 现货订单详情查询成功,准备保存到CSV并更新ArbitrageOpportunity: orderId={}", finalSpotOrderId);
                                saveOrderDetailToCsv(finalSymbol, spotDetail, "SPOT");
                                // ⭐ 更新 ArbitrageOpportunity 对象的现货交易明细
                                updateArbitrageOpportunityWithSpotDetail(finalOpp, spotDetail);
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
                                logger.info("✅ 合约订单详情查询成功,准备保存到CSV并更新ArbitrageOpportunity: orderId={}", finalSwapOrderId);
                                saveOrderDetailToCsv(finalSymbol, swapDetail, "SWAP");
                                // ⭐ 更新 ArbitrageOpportunity 对象的合约交易明细
                                updateArbitrageOpportunityWithFuturesDetail(finalOpp, swapDetail);
                                
                                // ⭐ 计算总成本和费用
                                finalOpp.calculateTotalCost();
                                finalOpp.calculateTotalFee();
                                finalOpp.calculateTotalExpense();
                                
                                logger.info("✅ ArbitrageOpportunity 更新完成: symbol={}, totalCost={}, totalFee={}, totalExpense={}", 
                                    finalSymbol, finalOpp.totalCost, finalOpp.totalFee, finalOpp.totalExpense);
                                
                                // ⭐ 打印详细的开仓状态报告
                                printOpenPositionReport(finalOpp, finalDirection);
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
                out.collect(buildFailureRecord(opp, direction, "REJECTED", failureReason, spotOrderId, null, coinAmount));
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
            out.collect(buildFailureRecord(opp, direction != null ? direction : "UNKNOWN", "ERROR",
                errorMsg != null ? errorMsg : "未知错误", null, null, null));
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
                out.collect(buildClosePendingRecord(opp, pos, spotOrderId, swapOrderId));
                
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

                // 提交平仓后先关闭本地持仓，避免重复触发平仓
                pos.setOpen(false);
                pos.setLastUpdateTime(System.currentTimeMillis());
                positionState.update(pos);

                // 异步查询平仓订单详情并汇总本次套利最终盈亏
                final String finalSymbol = pos.getSymbol();
                final String finalDirection = pos.getDirection();
                final String finalSpotOrderId = spotOrderId;
                final String finalSwapOrderId = swapOrderId;
                final PositionState finalPos = pos;

                orderQueryExecutor.submit(() -> {
                    try {
                        Thread.sleep(2000);
                        String closeSpotInstId = finalSymbol + "-USDT";
                        String closeSwapInstId = finalSymbol + "-USDT-SWAP";

                        com.fasterxml.jackson.databind.JsonNode closeSpotDetail =
                            tradingService.queryOrderDetail(finalSpotOrderId, closeSpotInstId, "SPOT");
                        com.fasterxml.jackson.databind.JsonNode closeSwapDetail =
                            tradingService.queryOrderDetail(finalSwapOrderId, closeSwapInstId, "SWAP");

                        if (closeSpotDetail != null) {
                            saveOrderDetailToCsv(finalSymbol, closeSpotDetail, "SPOT");
                        }
                        if (closeSwapDetail != null) {
                            saveOrderDetailToCsv(finalSymbol, closeSwapDetail, "SWAP");
                        }

                        writeFinalSummaryAfterClose(
                            finalPos, finalDirection, closeSpotDetail, closeSwapDetail, opp.spotPrice, opp.futuresPrice
                        );
                    } catch (Exception e) {
                        logger.error("❌ 平仓后查询订单明细并统计最终盈亏失败: symbol={}, error={}",
                            finalSymbol, e.getMessage(), e);
                    }
                });
            }
        } catch (Exception e) {
            logger.error("❌ 平仓失败: {}", e.getMessage(), e);
            
            // 同步写入失败日志（使用线程池）
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String errorLog = String.format("%s,%s,CLOSE,%s,ERROR,N/A,N/A,N/A,FAILED,%s",
                timestamp, pos.getSymbol(), pos.getDirection(), 
                e.getMessage().replace(",", ";"));
            orderDetailWriter.write(errorLog);
            out.collect(buildCloseFailureRecord(opp, pos, e.getMessage()));
        }
    }

    /**
     * 平仓后根据真实订单明细写入最终汇总（已实现盈亏）
     */
    private void writeFinalSummaryAfterClose(
            PositionState pos,
            String direction,
            com.fasterxml.jackson.databind.JsonNode closeSpotDetail,
            com.fasterxml.jackson.databind.JsonNode closeSwapDetail,
            BigDecimal closeSpotPriceFallback,
            BigDecimal closeSwapPriceFallback) {
        try {
            BigDecimal openSpotPrice = pos.getActualSpotPrice() != null ? pos.getActualSpotPrice() : pos.getEntrySpotPrice();
            BigDecimal openSwapPrice = pos.getActualSwapPrice() != null ? pos.getActualSwapPrice() : pos.getEntrySwapPrice();
            BigDecimal openSpotCoin = pos.getActualSpotFilledQuantity() != null ? pos.getActualSpotFilledQuantity() : pos.getAmount();
            BigDecimal openSwapCoin = pos.getActualSwapFilledCoin() != null ? pos.getActualSwapFilledCoin() : pos.getAmount();
            BigDecimal openFee = pos.getTotalFee() != null ? pos.getTotalFee() : BigDecimal.ZERO;

            BigDecimal closeSpotPrice = parseDecimal(closeSpotDetail, "avgPx", closeSpotPriceFallback);
            BigDecimal closeSpotCoin = parseDecimal(closeSpotDetail, "accFillSz", pos.getAmount());
            BigDecimal closeSpotFee = parseAbsDecimal(closeSpotDetail, "fee", BigDecimal.ZERO);

            BigDecimal closeSwapPrice = parseDecimal(closeSwapDetail, "avgPx", closeSwapPriceFallback);
            BigDecimal closeSwapContracts = parseDecimal(closeSwapDetail, "accFillSz", BigDecimal.ZERO);
            BigDecimal closeSwapFee = parseAbsDecimal(closeSwapDetail, "fee", BigDecimal.ZERO);

            // 合约平仓张数折算币数量
            BigDecimal ctVal = BigDecimal.ONE;
            try {
                OKXTradingService.InstrumentInfo info = tradingService.getInstrumentInfo(pos.getSymbol());
                if (info != null && info.ctVal != null) {
                    ctVal = info.ctVal;
                }
            } catch (Exception e) {
                logger.warn("⚠️ 读取 ctVal 失败，回退1: symbol={}, error={}", pos.getSymbol(), e.getMessage());
            }
            BigDecimal closeSwapCoin = closeSwapContracts.multiply(ctVal);

            // 最终套利只按“四腿都成交”的已对冲数量统计
            BigDecimal hedgedCoin = openSpotCoin.min(openSwapCoin).min(closeSpotCoin).min(closeSwapCoin);
            if (hedgedCoin.compareTo(BigDecimal.ZERO) <= 0) {
                logger.warn("⚠️ 平仓汇总跳过：已对冲数量为0, symbol={}", pos.getSymbol());
                return;
            }

            BigDecimal spotPnl;
            BigDecimal swapPnl;
            if ("LONG_SPOT_SHORT_SWAP".equals(direction)) {
                spotPnl = closeSpotPrice.subtract(openSpotPrice).multiply(hedgedCoin);
                swapPnl = openSwapPrice.subtract(closeSwapPrice).multiply(hedgedCoin);
            } else {
                spotPnl = openSpotPrice.subtract(closeSpotPrice).multiply(hedgedCoin);
                swapPnl = closeSwapPrice.subtract(openSwapPrice).multiply(hedgedCoin);
            }

            BigDecimal totalFee = openFee.add(closeSpotFee).add(closeSwapFee);
            BigDecimal realizedProfit = spotPnl.add(swapPnl).subtract(totalFee);

            BigDecimal investAmount = pos.getTotalCost() != null ? pos.getTotalCost() : openSpotPrice.multiply(hedgedCoin);
            BigDecimal profitRate = BigDecimal.ZERO;
            if (investAmount.compareTo(BigDecimal.ZERO) > 0) {
                profitRate = realizedProfit.divide(investAmount, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            }

            long holdSeconds = (System.currentTimeMillis() - pos.getOpenTime()) / 1000;
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            String summaryLine = String.format("%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%s",
                timestamp,
                pos.getSymbol(),
                direction,
                "CLOSED",
                realizedProfit.setScale(8, RoundingMode.HALF_UP),
                profitRate.setScale(4, RoundingMode.HALF_UP),
                holdSeconds,
                openSpotPrice,
                openSwapPrice,
                closeSpotPrice,
                closeSwapPrice,
                hedgedCoin.setScale(8, RoundingMode.HALF_UP)
            );
            summaryWriter.write(summaryLine);

            logger.info("✅ 平仓最终盈亏已统计: symbol={}, realizedProfit={} USDT, profitRate={}%, hedgedCoin={}",
                pos.getSymbol(),
                realizedProfit.setScale(8, RoundingMode.HALF_UP),
                profitRate.setScale(4, RoundingMode.HALF_UP),
                hedgedCoin.setScale(8, RoundingMode.HALF_UP));
        } catch (Exception e) {
            logger.error("❌ 写入平仓最终汇总失败: symbol={}, error={}", pos.getSymbol(), e.getMessage(), e);
        }
    }

    private BigDecimal parseDecimal(com.fasterxml.jackson.databind.JsonNode node, String field, BigDecimal fallback) {
        try {
            if (node == null) {
                return fallback;
            }
            String val = node.path(field).asText();
            if (val == null || val.isEmpty()) {
                return fallback;
            }
            BigDecimal parsed = new BigDecimal(val);
            return parsed.compareTo(BigDecimal.ZERO) > 0 ? parsed : fallback;
        } catch (Exception e) {
            return fallback;
        }
    }

    private BigDecimal parseAbsDecimal(com.fasterxml.jackson.databind.JsonNode node, String field, BigDecimal fallback) {
        try {
            if (node == null) {
                return fallback;
            }
            String val = node.path(field).asText();
            if (val == null || val.isEmpty()) {
                return fallback;
            }
            return new BigDecimal(val).abs();
        } catch (Exception e) {
            return fallback;
        }
    }

    /**
     * 构造持仓状态事件，落到 Doris 宽表中，覆盖控制台状态日志的核心指标。
     */
    private TradeRecord buildPositionStatusRecord(
            String symbol,
            PositionState position,
            OpportunityTracker tracker,
            ArbitrageOpportunity opportunity,
            long now) {
        TradeRecord record = buildBaseRecord(opportunity, position, now);
        record.eventType = "POSITION_STATUS";
        record.eventStage = "POSITION";
        record.action = "STATUS";
        record.positionStatus = position != null && position.isOpen() ? "OPEN" : "CLOSED";
        record.logSource = "STATUS_LOG";
        record.symbol = symbol;
        record.timestamp = now;

        if (position != null) {
            BigDecimal amountCoin = position.getActualSpotFilledQuantity() != null
                ? position.getActualSpotFilledQuantity()
                : position.getAmount();
            BigDecimal amountPrice = position.getActualSpotPrice() != null
                ? position.getActualSpotPrice()
                : position.getEntrySpotPrice();
            record.amountCoin = amountCoin;
            record.amountUsdt = multiplyOrNull(amountCoin, amountPrice);
            record.holdTimeSeconds = (now - position.getOpenTime()) / 1000;
            record.spotOrderId = position.getSpotOrderId();
            record.swapOrderId = position.getSwapOrderId();
            record.entrySpreadRate = position.getEntrySpreadRate();
            record.actualSpotPrice = position.getActualSpotPrice();
            record.actualSwapPrice = position.getActualSwapPrice();
            record.actualSpotFilledQty = position.getActualSpotFilledQuantity();
            record.actualSwapFilledContracts = position.getActualSwapFilledContracts();
            record.actualSwapFilledCoin = position.getActualSwapFilledCoin();
            record.spotCost = position.getSpotCost();
            record.swapCost = position.getFuturesCost();
            record.totalCost = position.getTotalCost();
            record.spotFee = position.getSpotFee();
            record.swapFee = position.getFuturesFee();
            record.totalFee = position.getTotalFee();
            record.totalExpense = position.getTotalExpense();
            record.unrealizedProfit = position.getUnrealizedProfit();
        }

        if (opportunity != null) {
            record.currentSpotPrice = opportunity.spotPrice;
            record.currentSwapPrice = opportunity.futuresPrice;
            record.currentSpread = opportunity.spread;
            record.currentSpreadRate = opportunity.spreadRate;
        }

        if (position != null) {
            BigDecimal spotFillCoin = position.getActualSpotFilledQuantity() != null ? position.getActualSpotFilledQuantity() : position.getAmount();
            BigDecimal swapFillCoin = position.getActualSwapFilledCoin() != null ? position.getActualSwapFilledCoin() : position.getAmount();
            record.hedgedCoinQty = spotFillCoin != null && swapFillCoin != null ? spotFillCoin.min(swapFillCoin) : null;
            record.unhedgedCoinQty = spotFillCoin != null && swapFillCoin != null ? spotFillCoin.subtract(swapFillCoin).abs() : null;
            if (record.unrealizedProfit != null && record.amountUsdt != null
                && record.amountUsdt.compareTo(BigDecimal.ZERO) > 0) {
                record.detailedProfitRate = record.unrealizedProfit
                    .divide(record.amountUsdt, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
            }
        }

        if (tracker != null) {
            record.trackerActive = tracker.isActive();
            record.trackerSpreadRate = tracker.getSpreadRate();
            record.trackerDurationSeconds = tracker.getFirstSeenTime() > 0
                ? (now - tracker.getFirstSeenTime()) / 1000
                : 0L;
        } else {
            record.trackerActive = false;
        }

        record.statusMessage = String.format(
            "持仓状态=%s, 方向=%s, 当前价差率=%s, 未实现利润=%s",
            record.positionStatus,
            record.direction,
            record.currentSpreadRate != null ? record.currentSpreadRate.setScale(3, RoundingMode.HALF_UP).toPlainString() : "N/A",
            record.unrealizedProfit != null ? record.unrealizedProfit.setScale(4, RoundingMode.HALF_UP).toPlainString() : "N/A"
        );
        return record;
    }

    private TradeRecord buildOpenPendingRecord(
            ArbitrageOpportunity opp,
            String direction,
            String spotOrderId,
            String swapOrderId,
            BigDecimal coinAmount) {
        TradeRecord record = buildBaseRecord(opp, null, System.currentTimeMillis());
        record.eventType = "OPEN_PENDING";
        record.eventStage = "ORDER";
        record.action = "OPEN";
        record.positionStatus = "OPEN";
        record.direction = direction;
        record.logSource = "ORDER_DETAIL";
        record.spotOrderId = spotOrderId;
        record.swapOrderId = swapOrderId;
        record.orderSpotPrice = opp.spotPrice;
        record.orderSwapPrice = opp.futuresPrice;
        record.amountCoin = coinAmount;
        record.amountUsdt = multiplyOrNull(coinAmount, opp.spotPrice);
        record.statusMessage = "开仓订单已提交";
        return record;
    }

    private TradeRecord buildClosePendingRecord(
            ArbitrageOpportunity opp,
            PositionState pos,
            String spotOrderId,
            String swapOrderId) {
        TradeRecord record = buildBaseRecord(opp, pos, System.currentTimeMillis());
        record.eventType = "CLOSE_PENDING";
        record.eventStage = "CLOSE";
        record.action = "CLOSE";
        record.positionStatus = "CLOSED";
        record.spotOrderId = spotOrderId;
        record.swapOrderId = swapOrderId;
        record.orderSpotPrice = opp != null ? opp.spotPrice : null;
        record.orderSwapPrice = opp != null ? opp.futuresPrice : null;
        record.holdTimeSeconds = pos != null ? (System.currentTimeMillis() - pos.getOpenTime()) / 1000 : null;
        record.statusMessage = "平仓订单已提交";
        return record;
    }

    private TradeRecord buildFailureRecord(
            ArbitrageOpportunity opp,
            String direction,
            String eventType,
            String errorMessage,
            String spotOrderId,
            String swapOrderId,
            BigDecimal coinAmount) {
        TradeRecord record = buildBaseRecord(opp, null, System.currentTimeMillis());
        record.eventType = eventType;
        record.eventStage = "ORDER";
        record.action = "OPEN";
        record.positionStatus = "N/A";
        record.direction = direction;
        record.logSource = "ERROR_LOG";
        record.errorMessage = errorMessage;
        record.spotOrderId = spotOrderId;
        record.swapOrderId = swapOrderId;
        record.amountCoin = coinAmount;
        record.amountUsdt = multiplyOrNull(coinAmount, opp != null ? opp.spotPrice : null);
        record.statusMessage = errorMessage;
        return record;
    }

    private TradeRecord buildCloseFailureRecord(
            ArbitrageOpportunity opp,
            PositionState pos,
            String errorMessage) {
        TradeRecord record = buildBaseRecord(opp, pos, System.currentTimeMillis());
        record.eventType = "ERROR";
        record.eventStage = "CLOSE";
        record.action = "CLOSE";
        record.positionStatus = pos != null && pos.isOpen() ? "OPEN" : "CLOSED";
        record.logSource = "ERROR_LOG";
        record.errorMessage = errorMessage;
        record.statusMessage = errorMessage;
        return record;
    }

    private TradeRecord buildBaseRecord(ArbitrageOpportunity opp, PositionState position, long eventTimeMs) {
        TradeRecord record = new TradeRecord();
        record.timestamp = eventTimeMs;
        record.symbol = opp != null ? opp.symbol : (position != null ? position.getSymbol() : null);
        record.spotInstId = record.symbol != null ? record.symbol + "-USDT" : null;
        record.swapInstId = record.symbol != null ? record.symbol + "-USDT-SWAP" : null;
        record.direction = opp != null ? opp.arbitrageDirection : (position != null ? position.getDirection() : null);
        record.discoverSpotPrice = opp != null ? opp.spotPrice : (position != null ? position.getEntrySpotPrice() : null);
        record.discoverSwapPrice = opp != null ? opp.futuresPrice : (position != null ? position.getEntrySwapPrice() : null);
        record.discoverSpread = opp != null ? opp.spread : null;
        record.discoverSpreadRate = opp != null ? opp.spreadRate : (position != null ? position.getEntrySpreadRate() : null);
        record.unitProfitEstimate = opp != null ? opp.unitProfitEstimate : null;
        record.discoverProfitEstimate = opp != null ? opp.profitEstimate : null;
        record.tradingEnabled = tradingEnabled;
        record.tradeAmountUsdt = tradeAmount;
        record.openThreshold = openThreshold;
        record.closeThreshold = closeThreshold;
        record.maxHoldTimeMinutes = (int) (maxHoldTimeMs / 60000L);
        record.maxLossUsdt = maxLossPerTrade;
        record.leverageConfig = leverage;

        if (position != null) {
            record.positionStatus = position.isOpen() ? "OPEN" : "CLOSED";
            record.spotOrderId = position.getSpotOrderId();
            record.swapOrderId = position.getSwapOrderId();
            record.entrySpreadRate = position.getEntrySpreadRate();
            record.actualSpotPrice = position.getActualSpotPrice();
            record.actualSwapPrice = position.getActualSwapPrice();
            record.actualSpotFilledQty = position.getActualSpotFilledQuantity();
            record.actualSwapFilledContracts = position.getActualSwapFilledContracts();
            record.actualSwapFilledCoin = position.getActualSwapFilledCoin();
            record.amountCoin = position.getActualSpotFilledQuantity() != null ? position.getActualSpotFilledQuantity() : position.getAmount();
            BigDecimal amountPrice = position.getActualSpotPrice() != null ? position.getActualSpotPrice() : position.getEntrySpotPrice();
            record.amountUsdt = multiplyOrNull(record.amountCoin, amountPrice);
            record.spotCost = position.getSpotCost();
            record.swapCost = position.getFuturesCost();
            record.totalCost = position.getTotalCost();
            record.spotFee = position.getSpotFee();
            record.swapFee = position.getFuturesFee();
            record.totalFee = position.getTotalFee();
            record.totalExpense = position.getTotalExpense();
            record.unrealizedProfit = position.getUnrealizedProfit();
        }
        return record;
    }

    private BigDecimal multiplyOrNull(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.multiply(right);
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
            // 控制台状态报告与 CSV 状态日志统一口径：优先使用真实成交价，否则回退委托价
            BigDecimal amountPrice = position.getActualSpotPrice() != null
                ? position.getActualSpotPrice()
                : position.getEntrySpotPrice();
            BigDecimal positionUsdtAmount = position.getAmount().multiply(amountPrice);
            String entrySpotPrice = position.getActualSpotPrice() != null
                ? formatPrice(position.getActualSpotPrice())
                : formatPrice(position.getEntrySpotPrice());
            String entrySwapPrice = position.getActualSwapPrice() != null
                ? formatPrice(position.getActualSwapPrice())
                : formatPrice(position.getEntrySwapPrice());
            status.append(String.format("  金额: %s %s (约 %s USDT)\n", 
                position.getAmount().setScale(4, RoundingMode.HALF_UP), 
                symbol,
                positionUsdtAmount.setScale(2, RoundingMode.HALF_UP)));
            status.append(String.format("  开仓价格: 现货=%s, 合约=%s\n", 
                entrySpotPrice, entrySwapPrice));
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
        
        // ⭐ 写入状态日志到 CSV 文件（用于验证数据计算准确性）
        writeStatusLogToCsv(symbol, position, tracker, opportunity, now);
        
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
    
    /**
     * 将状态日志写入 CSV 文件
     * 用于验证数据计算准确性
     * 
     * CSV 格式:
     * timestamp, symbol, position_status, direction, hold_time_seconds,
     * amount_coin, amount_usdt, entry_spot_price, entry_swap_price, entry_spread_rate,
     * current_spot_price, current_swap_price, current_spread_rate,
     * spot_cost, futures_cost, total_cost, total_fee, total_expense,
     * unrealized_profit, profit_rate, spot_order_id, swap_order_id,
     * tracker_active, tracker_spread_rate, tracker_duration_seconds,
     * trading_enabled, trade_amount, open_threshold, close_threshold,
     * max_hold_time_minutes, max_loss
     */
    private void writeStatusLogToCsv(String symbol, PositionState position, OpportunityTracker tracker, 
                                     ArbitrageOpportunity opportunity, long now) {
        try {
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
            
            // 持仓状态相关字段
            String positionStatus = (position != null && position.isOpen()) ? "OPEN" : "CLOSED";
            String direction = (position != null && position.isOpen()) ? position.getDirection() : "N/A";
            long holdTimeSeconds = (position != null && position.isOpen()) ? (now - position.getOpenTime()) / 1000 : 0;
            
            // 金额相关字段（使用实际成交价格计算）
            BigDecimal amountCoin = (position != null && position.isOpen()) ? position.getAmount() : BigDecimal.ZERO;
            BigDecimal amountUsdt = BigDecimal.ZERO;
            if (position != null && position.isOpen()) {
                // 优先使用实际成交价格计算 USDT 金额
                if (position.getActualSpotPrice() != null) {
                    amountUsdt = position.getAmount().multiply(position.getActualSpotPrice());
                } else {
                    amountUsdt = position.getAmount().multiply(position.getEntrySpotPrice());
                }
            }
            
            // 开仓价格相关字段（优先使用实际成交价格）
            String entrySpotPrice = "N/A";
            String entrySwapPrice = "N/A";
            if (position != null && position.isOpen()) {
                // 优先使用实际成交价格
                if (position.getActualSpotPrice() != null) {
                    entrySpotPrice = formatPrice(position.getActualSpotPrice());
                } else {
                    entrySpotPrice = formatPrice(position.getEntrySpotPrice());
                }
                
                if (position.getActualSwapPrice() != null) {
                    entrySwapPrice = formatPrice(position.getActualSwapPrice());
                } else {
                    entrySwapPrice = formatPrice(position.getEntrySwapPrice());
                }
            }
            
            String entrySpreadRate = (position != null && position.isOpen() && position.getEntrySpreadRate() != null) ? 
                position.getEntrySpreadRate().setScale(3, RoundingMode.HALF_UP).toString() : "N/A";
            
            // 当前价格相关字段（从 opportunity 获取）
            String currentSpotPrice = (opportunity != null) ? formatPrice(opportunity.spotPrice) : "N/A";
            String currentSwapPrice = (opportunity != null) ? formatPrice(opportunity.futuresPrice) : "N/A";
            String currentSpreadRate = (opportunity != null) ? 
                opportunity.spreadRate.setScale(3, RoundingMode.HALF_UP).toString() : "N/A";
            
            // 成本相关字段（优先从 PositionState 获取）
            String spotCost = "N/A";
            String futuresCost = "N/A";
            String totalCost = "N/A";
            String totalFee = "N/A";
            String totalExpense = "N/A";
            
            if (position != null && position.isOpen()) {
                // 优先从 PositionState 读取
                if (position.getSpotCost() != null) {
                    spotCost = position.getSpotCost().setScale(8, RoundingMode.HALF_UP).toString();
                } else if (opportunity != null && opportunity.spotCost != null) {
                    spotCost = opportunity.spotCost.setScale(8, RoundingMode.HALF_UP).toString();
                }
                
                if (position.getFuturesCost() != null) {
                    futuresCost = position.getFuturesCost().setScale(8, RoundingMode.HALF_UP).toString();
                } else if (opportunity != null && opportunity.futuresCost != null) {
                    futuresCost = opportunity.futuresCost.setScale(8, RoundingMode.HALF_UP).toString();
                }
                
                if (position.getTotalCost() != null) {
                    totalCost = position.getTotalCost().setScale(8, RoundingMode.HALF_UP).toString();
                } else if (opportunity != null && opportunity.totalCost != null) {
                    totalCost = opportunity.totalCost.setScale(8, RoundingMode.HALF_UP).toString();
                }
                
                if (position.getTotalFee() != null) {
                    totalFee = position.getTotalFee().setScale(8, RoundingMode.HALF_UP).toString();
                } else if (opportunity != null && opportunity.totalFee != null) {
                    totalFee = opportunity.totalFee.setScale(8, RoundingMode.HALF_UP).toString();
                }
                
                if (position.getTotalExpense() != null) {
                    totalExpense = position.getTotalExpense().setScale(8, RoundingMode.HALF_UP).toString();
                } else if (opportunity != null && opportunity.totalExpense != null) {
                    totalExpense = opportunity.totalExpense.setScale(8, RoundingMode.HALF_UP).toString();
                }
            }
            
            // 利润相关字段
            String unrealizedProfit = "N/A";
            String profitRate = "N/A";
            if (position != null && position.isOpen() && position.getUnrealizedProfit() != null) {
                unrealizedProfit = position.getUnrealizedProfit().setScale(4, RoundingMode.HALF_UP).toString();
                // 计算利润率（基于 USDT 金额）
                BigDecimal profitRateValue = position.getUnrealizedProfit()
                    .divide(amountUsdt, 6, RoundingMode.HALF_UP)
                    .multiply(new BigDecimal("100"));
                profitRate = profitRateValue.setScale(2, RoundingMode.HALF_UP).toString();
            }
            
            // 订单ID
            String spotOrderId = (position != null && position.isOpen()) ? position.getSpotOrderId() : "N/A";
            String swapOrderId = (position != null && position.isOpen()) ? position.getSwapOrderId() : "N/A";
            
            // 套利机会跟踪相关字段
            String trackerActive = (tracker != null && tracker.isActive()) ? "YES" : "NO";
            String trackerSpreadRate = (tracker != null && tracker.isActive()) ? 
                tracker.getSpreadRate().setScale(3, RoundingMode.HALF_UP).toString() : "N/A";
            long trackerDurationSeconds = (tracker != null && tracker.isActive()) ? 
                (now - tracker.getFirstSeenTime()) / 1000 : 0;
            
            // 交易配置相关字段
            String tradingEnabledStr = tradingEnabled ? "YES" : "NO";
            String tradeAmountStr = tradeAmount.setScale(2, RoundingMode.HALF_UP).toString();
            String openThresholdStr = openThreshold.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP).toString();
            String closeThresholdStr = closeThreshold.multiply(new BigDecimal("100")).setScale(3, RoundingMode.HALF_UP).toString();
            long maxHoldTimeMinutes = maxHoldTimeMs / 60000;
            String maxLossStr = maxLossPerTrade.setScale(2, RoundingMode.HALF_UP).toString();
            
            // 构建 CSV 行
            String csvLine = String.format("%s,%s,%s,%s,%d,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%d,%s,%s,%s,%s,%d,%s",
                timestamp, symbol, positionStatus, direction, holdTimeSeconds,
                amountCoin.setScale(8, RoundingMode.HALF_UP), amountUsdt.setScale(2, RoundingMode.HALF_UP),
                entrySpotPrice, entrySwapPrice, entrySpreadRate,
                currentSpotPrice, currentSwapPrice, currentSpreadRate,
                spotCost, futuresCost, totalCost, totalFee, totalExpense,
                unrealizedProfit, profitRate, spotOrderId, swapOrderId,
                trackerActive, trackerSpreadRate, trackerDurationSeconds,
                tradingEnabledStr, tradeAmountStr, openThresholdStr, closeThresholdStr,
                maxHoldTimeMinutes, maxLossStr
            );
            
            // 写入 CSV
            if (statusLogWriter != null) {
                statusLogWriter.write(csvLine);
                logger.debug("✅ 状态日志已写入CSV: symbol={}", symbol);
            } else {
                logger.warn("⚠️ statusLogWriter 为空,无法写入状态日志");
            }
            
        } catch (Exception e) {
            logger.error("❌ 写入状态日志失败: symbol={}, error={}", symbol, e.getMessage(), e);
        }
    }
    
    /**
     * 更新 ArbitrageOpportunity 对象的现货交易明细
     * 
     * @param opp ArbitrageOpportunity 对象
     * @param spotDetail 现货订单详情(JSON)
     */
    private void updateArbitrageOpportunityWithSpotDetail(
            ArbitrageOpportunity opp, 
            com.fasterxml.jackson.databind.JsonNode spotDetail) {
        try {
            // 提取现货订单信息
            opp.spotOrderId = spotDetail.path("ordId").asText();
            opp.spotFillPrice = new BigDecimal(spotDetail.path("avgPx").asText());
            opp.spotFillQuantity = new BigDecimal(spotDetail.path("accFillSz").asText());
            opp.spotFee = new BigDecimal(spotDetail.path("fee").asText()).abs();  // 手续费取绝对值
            opp.spotFeeCurrency = spotDetail.path("feeCcy").asText();
            
            // 计算现货成本 = 成交价 × 成交量
            opp.spotCost = opp.spotFillPrice.multiply(opp.spotFillQuantity);
            
            logger.info("✅ 现货交易明细已更新: orderId={}, fillPrice={}, fillQty={}, fee={} {}, cost={}", 
                opp.spotOrderId, opp.spotFillPrice, opp.spotFillQuantity, 
                opp.spotFee, opp.spotFeeCurrency, opp.spotCost);
                
        } catch (Exception e) {
            logger.error("❌ 更新现货交易明细失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 更新 ArbitrageOpportunity 对象的合约交易明细
     * 
     * @param opp ArbitrageOpportunity 对象
     * @param futuresDetail 合约订单详情(JSON)
     */
    private void updateArbitrageOpportunityWithFuturesDetail(
            ArbitrageOpportunity opp, 
            com.fasterxml.jackson.databind.JsonNode futuresDetail) {
        try {
            // 提取合约订单信息
            opp.futuresOrderId = futuresDetail.path("ordId").asText();
            opp.futuresFillPrice = new BigDecimal(futuresDetail.path("avgPx").asText());
            opp.futuresFillQuantity = new BigDecimal(futuresDetail.path("accFillSz").asText());
            opp.futuresFee = new BigDecimal(futuresDetail.path("fee").asText()).abs();  // 手续费取绝对值
            opp.futuresFeeCurrency = futuresDetail.path("feeCcy").asText();
            
            // 计算合约成本 = 成交价 × 成交量(张) × 合约面值(ctVal)
            // 说明：OKX 合约 accFillSz 单位是“张”，不能直接当币数量使用
            OKXTradingService.InstrumentInfo info = tradingService.getInstrumentInfo(opp.symbol);
            BigDecimal ctVal = (info != null && info.ctVal != null) ? info.ctVal : BigDecimal.ONE;
            opp.futuresCost = opp.futuresFillPrice.multiply(opp.futuresFillQuantity).multiply(ctVal);
            
            logger.info("✅ 合约交易明细已更新: orderId={}, fillPrice={}, fillQty={}, fee={} {}, cost={}", 
                opp.futuresOrderId, opp.futuresFillPrice, opp.futuresFillQuantity, 
                opp.futuresFee, opp.futuresFeeCurrency, opp.futuresCost);
                
        } catch (Exception e) {
            logger.error("❌ 更新合约交易明细失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 打印详细的开仓状态报告
     * 包含所有关键信息,方便验证计算逻辑
     * 
     * @param opp ArbitrageOpportunity 对象（包含订单详情）
     * @param direction 交易方向
     */
    private void printOpenPositionReport(ArbitrageOpportunity opp, String direction) {
        try {
            StringBuilder report = new StringBuilder();
            report.append("\n");
            report.append("╔════════════════════════════════════════════════════════════════════════════════╗\n");
            report.append("║                          📊 开仓状态详细报告                                    ║\n");
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 基本信息
            report.append(String.format("║ 币对: %-20s 方向: %-40s ║\n", opp.symbol, direction));
            report.append(String.format("║ 时间: %-70s ║\n", 
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 1. 开仓决策信息
            report.append("║ 【1. 开仓决策信息】                                                             ║\n");
            report.append(String.format("║   发现价差率: %s%%                                                          ║\n", 
                opp.spreadRate.setScale(3, RoundingMode.HALF_UP)));
            report.append(String.format("║   现货价格: %s                                                              ║\n", 
                formatPriceForReport(opp.spotPrice)));
            report.append(String.format("║   合约价格: %s                                                              ║\n", 
                formatPriceForReport(opp.futuresPrice)));
            report.append(String.format("║   下单金额: %s USDT                                                         ║\n", 
                tradeAmount.setScale(2, RoundingMode.HALF_UP)));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 2. 现货订单成交信息
            report.append("║ 【2. 现货订单成交信息】                                                         ║\n");
            report.append(String.format("║   订单ID: %-68s ║\n", opp.spotOrderId));
            report.append(String.format("║   成交价格: %s                                                              ║\n", 
                formatPriceForReport(opp.spotFillPrice)));
            report.append(String.format("║   成交数量: %s %s                                                           ║\n", 
                opp.spotFillQuantity.setScale(8, RoundingMode.HALF_UP), opp.symbol));
            report.append(String.format("║   成交金额: %s USDT                                                         ║\n", 
                opp.spotCost.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   手续费: %s %s                                                             ║\n", 
                opp.spotFee.setScale(8, RoundingMode.HALF_UP), opp.spotFeeCurrency));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 3. 合约订单成交信息
            report.append("║ 【3. 合约订单成交信息】                                                         ║\n");
            report.append(String.format("║   订单ID: %-68s ║\n", opp.futuresOrderId));
            report.append(String.format("║   成交价格: %s                                                              ║\n", 
                formatPriceForReport(opp.futuresFillPrice)));
            report.append(String.format("║   成交数量: %s 张                                                           ║\n", 
                opp.futuresFillQuantity.setScale(0, RoundingMode.HALF_UP)));
            report.append(String.format("║   成交金额: %s USDT                                                         ║\n", 
                opp.futuresCost.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   手续费: %s %s                                                             ║\n", 
                opp.futuresFee.setScale(8, RoundingMode.HALF_UP), opp.futuresFeeCurrency));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 4. 成本汇总信息
            report.append("║ 【4. 成本汇总信息】                                                             ║\n");
            report.append(String.format("║   现货成本: %s USDT                                                         ║\n", 
                opp.spotCost.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   合约成本: %s USDT                                                         ║\n", 
                opp.futuresCost.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   总成本: %s USDT (双边投入)                                                ║\n", 
                opp.totalCost.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   总手续费: %s USDT                                                         ║\n", 
                opp.totalFee.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   总费用: %s USDT (成本+手续费)                                             ║\n", 
                opp.totalExpense.setScale(8, RoundingMode.HALF_UP)));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 5. 实际成交价差信息
            BigDecimal actualSpread = opp.futuresFillPrice.subtract(opp.spotFillPrice);
            BigDecimal actualSpreadRate = SpreadRateUtils.calculateSpreadRatePercent(
                opp.spotFillPrice,
                opp.futuresFillPrice,
                6
            );
            
            report.append("║ 【5. 实际成交价差信息】                                                         ║\n");
            report.append(String.format("║   实际价差: %s USDT                                                         ║\n", 
                actualSpread.setScale(8, RoundingMode.HALF_UP)));
            report.append(String.format("║   实际价差率: %s%%                                                          ║\n", 
                actualSpreadRate.setScale(3, RoundingMode.HALF_UP)));
            report.append(String.format("║   预期价差率: %s%%                                                          ║\n", 
                opp.spreadRate.setScale(3, RoundingMode.HALF_UP)));
            BigDecimal spreadDiff = actualSpreadRate.subtract(opp.spreadRate);
            report.append(String.format("║   价差偏差: %s%% %s                                                         ║\n", 
                spreadDiff.setScale(3, RoundingMode.HALF_UP),
                spreadDiff.compareTo(BigDecimal.ZERO) > 0 ? "(更优)" : "(更差)"));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 6. 预估利润信息（假设价差完全收敛）
            report.append("║ 【6. 预估利润信息】(假设价差完全收敛)                                           ║\n");
            
            // 计算预估利润 = 价差 × 数量 - 手续费
            BigDecimal estimatedProfit = actualSpread.multiply(opp.spotFillQuantity).subtract(opp.totalFee);
            BigDecimal estimatedProfitRate = estimatedProfit.divide(opp.totalCost, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            
            report.append(String.format("║   预估利润: %s USDT                                                         ║\n", 
                estimatedProfit.setScale(4, RoundingMode.HALF_UP)));
            report.append(String.format("║   预估利润率: %s%% (基于总成本)                                             ║\n", 
                estimatedProfitRate.setScale(2, RoundingMode.HALF_UP)));
            report.append("╠════════════════════════════════════════════════════════════════════════════════╣\n");
            
            // 7. 验证计算公式
            report.append("║ 【7. 验证计算公式】                                                             ║\n");
            report.append("║   现货成本 = 现货成交价 × 现货成交量                                            ║\n");
            BigDecimal verifySpotCost = opp.spotFillPrice.multiply(opp.spotFillQuantity);
            report.append(String.format("║   验证: %s × %s = %s ✓                                                      ║\n", 
                formatPriceForReport(opp.spotFillPrice),
                opp.spotFillQuantity.setScale(8, RoundingMode.HALF_UP),
                verifySpotCost.setScale(8, RoundingMode.HALF_UP)));
            
            report.append("║   合约成本 = 合约成交价 × 合约成交量                                            ║\n");
            BigDecimal verifyFuturesCost = opp.futuresFillPrice.multiply(opp.futuresFillQuantity);
            report.append(String.format("║   验证: %s × %s = %s ✓                                                      ║\n", 
                formatPriceForReport(opp.futuresFillPrice),
                opp.futuresFillQuantity.setScale(0, RoundingMode.HALF_UP),
                verifyFuturesCost.setScale(8, RoundingMode.HALF_UP)));
            
            report.append("║   总成本 = 现货成本 + 合约成本                                                  ║\n");
            BigDecimal verifyTotalCost = verifySpotCost.add(verifyFuturesCost);
            report.append(String.format("║   验证: %s + %s = %s ✓                                                      ║\n", 
                verifySpotCost.setScale(8, RoundingMode.HALF_UP),
                verifyFuturesCost.setScale(8, RoundingMode.HALF_UP),
                verifyTotalCost.setScale(8, RoundingMode.HALF_UP)));
            
            report.append("║   总手续费 = 现货手续费 + 合约手续费                                            ║\n");
            BigDecimal verifyTotalFee = opp.spotFee.add(opp.futuresFee);
            report.append(String.format("║   验证: %s + %s = %s ✓                                                      ║\n", 
                opp.spotFee.setScale(8, RoundingMode.HALF_UP),
                opp.futuresFee.setScale(8, RoundingMode.HALF_UP),
                verifyTotalFee.setScale(8, RoundingMode.HALF_UP)));
            
            report.append("║   预估利润 = 价差 × 数量 - 总手续费                                             ║\n");
            BigDecimal verifyProfit = actualSpread.multiply(opp.spotFillQuantity).subtract(opp.totalFee);
            report.append(String.format("║   验证: %s × %s - %s = %s ✓                                                 ║\n", 
                actualSpread.setScale(8, RoundingMode.HALF_UP),
                opp.spotFillQuantity.setScale(8, RoundingMode.HALF_UP),
                opp.totalFee.setScale(8, RoundingMode.HALF_UP),
                verifyProfit.setScale(4, RoundingMode.HALF_UP)));
            
            report.append("╚════════════════════════════════════════════════════════════════════════════════╝\n");
            
            // 输出报告
            logger.info(report.toString());
            
        } catch (Exception e) {
            logger.error("❌ 打印开仓状态报告失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 格式化价格用于报告显示（固定宽度）
     */
    private String formatPriceForReport(BigDecimal price) {
        if (price == null) {
            return "N/A";
        }
        String priceStr = price.stripTrailingZeros().toPlainString();
        // 限制最大长度为 20 个字符
        if (priceStr.length() > 20) {
            priceStr = priceStr.substring(0, 20);
        }
        return priceStr;
    }
}

