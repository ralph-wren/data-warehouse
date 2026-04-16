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
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;



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
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private final ConfigLoader config;
    private final boolean tradingEnabled;
    private final BigDecimal tradeAmount;
    private final int leverage;  // 杠杆倍数
    private final BigDecimal openThreshold;
    private final BigDecimal closeThreshold;
    private final boolean enableLongSpotShortSwap;
    private final boolean enableShortSpotLongSwap;
    private final BigDecimal feeRateEstimate;
    private final BigDecimal slippageRateEstimate;
    private final BigDecimal safetyBufferRate;
    private final BigDecimal minNetEdgeRate;
    private final long maxHoldTimeMs;
    private final BigDecimal maxLossPerTrade;
    private final int maxPositions;  // 最大持仓数量
    private final long statusLogIntervalMs;  // 状态日志输出间隔（毫秒）
    private final long orderDetailSyncIntervalMs;  // 订单详情回填最小间隔（毫秒）
    
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
    private transient java.util.concurrent.ConcurrentLinkedQueue<TradeRecord> pendingDorisRecords;  // 异步线程产出的 Doris 事件
    private transient java.util.concurrent.ConcurrentHashMap<String, OrderDetailsSyncResult> pendingOrderDetailSyncResults;  // 异步订单回填结果
    private transient java.util.concurrent.ConcurrentHashMap<String, Boolean> orderDetailSyncInFlight;  // 防重复提交
    private transient java.util.concurrent.ConcurrentHashMap<String, OrderCommandResult> pendingOrderCommandResults;  // 异步下单结果
    private transient java.util.concurrent.ConcurrentHashMap<String, String> orderCommandInFlight;  // OPEN/CLOSE 防重复提交
    private transient java.util.concurrent.ConcurrentHashMap<String, Boolean> positionSlotReserved;  // OPEN 预占持仓槽位

    // raw_payload_json 最大长度控制，避免 Doris 单行过大导致写入失败
    private static final int RAW_JSON_MAX_LEN = 6000;
    
    // Redis Key 常量
    private static final String REDIS_KEY_POSITION_COUNT = "okx:arbitrage:position:count";  // 全局持仓计数器
    
    private transient ValueState<PositionState> positionState;
    private transient ValueState<OpportunityTracker> opportunityTracker;
    private transient ValueState<Long> lastStatusLogTime;  // 最后一次状态日志输出时间
    private transient ValueState<String> currentSymbol;    // 当前币对名称
    private transient ValueState<ArbitrageOpportunity> currentOpportunity;  // 当前套利机会（用于状态日志）
    private transient ValueState<Long> lastOrderDetailsSyncTime;  // 最近一次订单详情回填时间
    
    // 失败黑名单状态
    private transient MapState<String, Integer> failureCount;  // 失败次数统计
    private transient MapState<String, Long> blacklistExpiry;  // 黑名单过期时间
    
    public TradingDecisionProcessor(ConfigLoader config) {
        this.config = config;
        this.tradingEnabled = config.getBoolean("arbitrage.trading.enabled", false);
        this.tradeAmount = new BigDecimal(config.getString("arbitrage.trading.trade-amount-usdt", "100"));
        this.leverage = config.getInt("arbitrage.trading.leverage", 1);  // 读取杠杆倍数配置,默认1倍
        this.openThreshold = new BigDecimal(config.getString("arbitrage.trading.open-threshold", "0.005"));
        this.closeThreshold = new BigDecimal(config.getString("arbitrage.trading.close-threshold", "0.002"));
        Boolean longSpotShortSwapEnabled = config.getBoolean("arbitrage.trading.direction-filter.enable-long-spot-short-swap");
        if (longSpotShortSwapEnabled == null) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.direction-filter.enable-long-spot-short-swap");
        }
        this.enableLongSpotShortSwap = longSpotShortSwapEnabled;
        Boolean shortSpotLongSwapEnabled = config.getBoolean("arbitrage.trading.direction-filter.enable-short-spot-long-swap");
        if (shortSpotLongSwapEnabled == null) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.direction-filter.enable-short-spot-long-swap");
        }
        this.enableShortSpotLongSwap = shortSpotLongSwapEnabled;
        String feeRateEstimateConfig = config.getString("arbitrage.trading.open-guard.fee-rate-estimate");
        if (feeRateEstimateConfig == null || feeRateEstimateConfig.trim().isEmpty()) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.open-guard.fee-rate-estimate");
        }
        this.feeRateEstimate = new BigDecimal(feeRateEstimateConfig);
        String slippageRateEstimateConfig = config.getString("arbitrage.trading.open-guard.slippage-rate-estimate");
        if (slippageRateEstimateConfig == null || slippageRateEstimateConfig.trim().isEmpty()) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.open-guard.slippage-rate-estimate");
        }
        this.slippageRateEstimate = new BigDecimal(slippageRateEstimateConfig);
        String safetyBufferRateConfig = config.getString("arbitrage.trading.open-guard.safety-buffer-rate");
        if (safetyBufferRateConfig == null || safetyBufferRateConfig.trim().isEmpty()) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.open-guard.safety-buffer-rate");
        }
        this.safetyBufferRate = new BigDecimal(safetyBufferRateConfig);
        this.minNetEdgeRate = feeRateEstimate.add(slippageRateEstimate).add(safetyBufferRate);
        this.maxPositions = config.getInt("arbitrage.trading.max-positions", 1);  // 读取最大持仓数量配置,默认1个
        
        int maxHoldMinutes = config.getInt("arbitrage.trading.max-hold-time-minutes", 60);
        this.maxHoldTimeMs = maxHoldMinutes * 60 * 1000L;
        
        this.maxLossPerTrade = new BigDecimal(config.getString("arbitrage.trading.max-loss-per-trade", "10"));

        Long configuredStatusLogIntervalMs = config.getLong("arbitrage.trading.status-log-interval-ms");
        if (configuredStatusLogIntervalMs == null) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.status-log-interval-ms");
        }
        if (configuredStatusLogIntervalMs <= 0) {
            throw new IllegalStateException("非法配置: arbitrage.trading.status-log-interval-ms 必须 > 0");
        }
        this.statusLogIntervalMs = configuredStatusLogIntervalMs;

        Long configuredOrderDetailSyncIntervalMs = config.getLong("arbitrage.trading.order-detail-sync-interval-ms");
        if (configuredOrderDetailSyncIntervalMs == null) {
            throw new IllegalStateException("缺少配置: arbitrage.trading.order-detail-sync-interval-ms");
        }
        if (configuredOrderDetailSyncIntervalMs <= 0) {
            throw new IllegalStateException("非法配置: arbitrage.trading.order-detail-sync-interval-ms 必须 > 0");
        }
        this.orderDetailSyncIntervalMs = configuredOrderDetailSyncIntervalMs;
        
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
        pendingDorisRecords = new java.util.concurrent.ConcurrentLinkedQueue<>();
        pendingOrderDetailSyncResults = new java.util.concurrent.ConcurrentHashMap<>();
        orderDetailSyncInFlight = new java.util.concurrent.ConcurrentHashMap<>();
        pendingOrderCommandResults = new java.util.concurrent.ConcurrentHashMap<>();
        orderCommandInFlight = new java.util.concurrent.ConcurrentHashMap<>();
        positionSlotReserved = new java.util.concurrent.ConcurrentHashMap<>();
        
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

        ValueStateDescriptor<Long> lastOrderDetailsSyncDescriptor = new ValueStateDescriptor<>(
            "last-order-details-sync-time",
            Long.class
        );
        lastOrderDetailsSyncTime = getRuntimeContext().getState(lastOrderDetailsSyncDescriptor);
        
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
        logger.info("  方向过滤: LONG_SPOT_SHORT_SWAP={}, SHORT_SPOT_LONG_SWAP={}",
            enableLongSpotShortSwap, enableShortSpotLongSwap);
        logger.info("  开仓净边际防护: feeRate={}, slippageRate={}, safetyBuffer={}, minNetEdgeRate={}",
            feeRateEstimate, slippageRateEstimate, safetyBufferRate, minNetEdgeRate);
        logger.info("  杠杆支持信息: {} 个币种已加载", marginCache.size());
        logger.info("  订单详情回填最小间隔: {} ms", orderDetailSyncIntervalMs);
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
        drainPendingDorisRecords(out);
        
        PositionState position = positionState.value();
        OpportunityTracker tracker = opportunityTracker.value();
        long now = System.currentTimeMillis();
        applyPendingOrderCommandResult(opportunity, position, out);
        position = positionState.value();
        
        // 保存当前币对名称
        currentSymbol.update(opportunity.symbol);
        
        // 保存当前套利机会（用于状态日志显示当前价差）
        currentOpportunity.update(opportunity);
        
        // 注册定时器(只在第一次处理时注册)
        Long lastLogTime = lastStatusLogTime.value();
        if (lastLogTime == null) {
            // 首次处理,注册第一个定时器
            long nextTimerTime = now + statusLogIntervalMs;
            ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
            lastStatusLogTime.update(now);
            logger.debug("⏰ 已启动状态日志定时器,每 {} 秒输出一次", statusLogIntervalMs / 1000);
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
                        submitAsyncOpenPosition(opportunity);
                        
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
            // 性能优化：按配置做节流，避免持仓期间每条行情都触发外部 API 查询导致主线程阻塞
            if (shouldSyncOrderDetails(now)) {
                submitAsyncOrderDetailsSync(position);
                lastOrderDetailsSyncTime.update(now);
            }
            applyPendingOrderDetailsSyncResult(opportunity.symbol, position);
            
            // ⭐ 兼容旧链路：如果 ArbitrageOpportunity 包含订单详情，也同步到 PositionState
            syncOrderDetailsToPosition(opportunity, position);
            
            // 更新预估利润
            updateUnrealizedProfit(opportunity, position, out);
            
            // 检查是否需要平仓
            if (shouldClose(opportunity, position)) {
                submitAsyncClosePosition(opportunity, position);
            }
        }
    }

    /**
     * 判断本次是否允许进行订单详情回填。
     * 目的：降低外部 REST 查询频率，避免阻塞 Flink 主处理线程造成 Kafka lag 堆积。
     */
    private boolean shouldSyncOrderDetails(long now) throws Exception {
        Long lastSyncTime = lastOrderDetailsSyncTime.value();
        return lastSyncTime == null || now - lastSyncTime >= orderDetailSyncIntervalMs;
    }

    /**
     * 提交异步订单详情回填任务，避免在 Flink 主线程执行 REST 查询。
     */
    private void submitAsyncOrderDetailsSync(PositionState position) {
        if (position == null || !position.isOpen()) {
            return;
        }
        String symbol = position.getSymbol();
        String spotOrderId = position.getSpotOrderId();
        String swapOrderId = position.getSwapOrderId();
        if (symbol == null || spotOrderId == null || swapOrderId == null) {
            return;
        }
        if (position.getActualSpotPrice() != null
            && position.getActualSwapPrice() != null
            && position.getActualSpotFilledQuantity() != null
            && position.getActualSwapFilledCoin() != null) {
            return;
        }

        // 关键点：同一 symbol 只允许一个 in-flight 查询任务，避免线程池堆积
        if (orderDetailSyncInFlight.putIfAbsent(symbol, Boolean.TRUE) != null) {
            return;
        }

        orderQueryExecutor.submit(() -> {
            try {
                OrderDetailsSyncResult result = queryOrderDetailsSyncResult(symbol, spotOrderId, swapOrderId);
                if (result != null) {
                    pendingOrderDetailSyncResults.put(symbol, result);
                }
            } catch (Exception e) {
                logger.warn("⚠️ 异步回填订单详情失败: symbol={}, error={}", symbol, e.getMessage());
            } finally {
                orderDetailSyncInFlight.remove(symbol);
            }
        });
    }

    /**
     * 主线程消费异步订单详情回填结果，并安全更新 Flink keyed state。
     */
    private void applyPendingOrderDetailsSyncResult(String symbol, PositionState position) throws Exception {
        if (symbol == null || position == null || !position.isOpen()) {
            return;
        }
        OrderDetailsSyncResult result = pendingOrderDetailSyncResults.remove(symbol);
        if (result == null) {
            return;
        }

        if (result.actualSpotPrice != null && result.actualSpotFilledQuantity != null) {
            position.setActualSpotPrice(result.actualSpotPrice);
            position.setActualSpotFilledQuantity(result.actualSpotFilledQuantity);
            position.setSpotCost(result.spotCost);
            position.setSpotFee(result.spotFee);
            position.setSpotFeeCurrency(result.spotFeeCurrency);
            position.setSpotOrderType(result.spotOrderType);
            position.setSpotOrderSide(result.spotOrderSide);
            position.setSpotPosSide(result.spotPosSide);
            position.setSpotOrderState(result.spotOrderState);
            position.setSpotTdMode(result.spotTdMode);
        }

        if (result.actualSwapPrice != null && result.actualSwapFilledContracts != null) {
            position.setActualSwapPrice(result.actualSwapPrice);
            position.setActualSwapFilledContracts(result.actualSwapFilledContracts);
            position.setActualSwapFilledCoin(result.actualSwapFilledCoin);
            position.setFuturesCost(result.futuresCost);
            position.setFuturesFee(result.futuresFee);
            position.setFuturesFeeCurrency(result.futuresFeeCurrency);
            position.setCtVal(result.ctVal);
            position.setLotSz(result.lotSz);
            position.setMinSz(result.minSz);
            position.setInstrumentMaxLeverage(result.instrumentMaxLeverage);
            position.setSwapOrderType(result.swapOrderType);
            position.setSwapOrderSide(result.swapOrderSide);
            position.setSwapPosSide(result.swapPosSide);
            position.setSwapOrderState(result.swapOrderState);
            position.setSwapTdMode(result.swapTdMode);
        }

        if (result.rawOrderDetailsJson != null) {
            position.setLastOrderDetailsRawJson(result.rawOrderDetailsJson);
        }
        if (result.totalCost != null) {
            position.setTotalCost(result.totalCost);
        }
        if (result.totalFee != null) {
            position.setTotalFee(result.totalFee);
        }
        if (result.totalExpense != null) {
            position.setTotalExpense(result.totalExpense);
        }

        positionState.update(position);
    }

    /**
     * 在线程池执行的订单详情回填查询逻辑（只做外部 I/O，不接触 Flink state）。
     */
    private OrderDetailsSyncResult queryOrderDetailsSyncResult(
            String symbol,
            String spotOrderId,
            String swapOrderId) {
        OrderDetailsSyncResult result = new OrderDetailsSyncResult();
        com.fasterxml.jackson.databind.JsonNode spotDetail = null;
        com.fasterxml.jackson.databind.JsonNode swapDetail = null;
        try {
            String spotInstId = symbol + "-USDT";
            spotDetail = tradingService.queryOrderDetail(spotOrderId, spotInstId, "SPOT");
            if (spotDetail != null) {
                BigDecimal spotAvgPx = new BigDecimal(spotDetail.path("avgPx").asText("0"));
                BigDecimal spotFillCoin = new BigDecimal(spotDetail.path("accFillSz").asText("0"));
                BigDecimal spotFee = new BigDecimal(spotDetail.path("fee").asText("0")).abs();
                if (spotAvgPx.compareTo(BigDecimal.ZERO) > 0 && spotFillCoin.compareTo(BigDecimal.ZERO) > 0) {
                    result.actualSpotPrice = spotAvgPx;
                    result.actualSpotFilledQuantity = spotFillCoin;
                    result.spotCost = spotAvgPx.multiply(spotFillCoin);
                    result.spotFee = spotFee;
                    result.spotFeeCurrency = spotDetail.path("feeCcy").asText();
                    result.spotOrderType = spotDetail.path("ordType").asText();
                    result.spotOrderSide = spotDetail.path("side").asText();
                    result.spotPosSide = spotDetail.path("posSide").asText();
                    result.spotOrderState = spotDetail.path("state").asText();
                    result.spotTdMode = spotDetail.path("tdMode").asText();
                }
            }

            String swapInstId = symbol + "-USDT-SWAP";
            swapDetail = tradingService.queryOrderDetail(swapOrderId, swapInstId, "SWAP");
            if (swapDetail != null) {
                BigDecimal swapAvgPx = new BigDecimal(swapDetail.path("avgPx").asText("0"));
                BigDecimal swapFillContracts = new BigDecimal(swapDetail.path("accFillSz").asText("0"));
                BigDecimal swapFee = new BigDecimal(swapDetail.path("fee").asText("0")).abs();
                OKXTradingService.InstrumentInfo info = tradingService.getInstrumentInfo(symbol);
                BigDecimal ctVal = (info != null && info.ctVal != null) ? info.ctVal : BigDecimal.ONE;
                BigDecimal swapFillCoin = swapFillContracts.multiply(ctVal);
                if (swapAvgPx.compareTo(BigDecimal.ZERO) > 0 && swapFillContracts.compareTo(BigDecimal.ZERO) > 0) {
                    result.actualSwapPrice = swapAvgPx;
                    result.actualSwapFilledContracts = swapFillContracts;
                    result.actualSwapFilledCoin = swapFillCoin;
                    result.futuresCost = swapAvgPx.multiply(swapFillCoin);
                    result.futuresFee = swapFee;
                    result.futuresFeeCurrency = swapDetail.path("feeCcy").asText();
                    result.ctVal = ctVal;
                    if (info != null) {
                        result.lotSz = info.lotSz;
                        result.minSz = info.minSz;
                        result.instrumentMaxLeverage = info.lever != null ? info.lever.intValue() : null;
                    }
                    result.swapOrderType = swapDetail.path("ordType").asText();
                    result.swapOrderSide = swapDetail.path("side").asText();
                    result.swapPosSide = swapDetail.path("posSide").asText();
                    result.swapOrderState = swapDetail.path("state").asText();
                    result.swapTdMode = swapDetail.path("tdMode").asText();
                }
            }

            if (spotDetail != null || swapDetail != null) {
                result.rawOrderDetailsJson = buildOrderDetailsRawJson(symbol, spotOrderId, swapOrderId, spotDetail, swapDetail);
            }
            if (result.spotCost != null && result.futuresCost != null) {
                result.totalCost = result.spotCost.add(result.futuresCost);
            }
            if (result.spotFee != null && result.futuresFee != null) {
                result.totalFee = result.spotFee.add(result.futuresFee);
            }
            if (result.totalCost != null && result.totalFee != null) {
                result.totalExpense = result.totalCost.add(result.totalFee);
            }
        } catch (Exception e) {
            logger.warn("⚠️ 查询订单详情结果失败: symbol={}, error={}", symbol, e.getMessage());
            return null;
        }
        return result;
    }

    /**
     * 异步查询结果承载对象（仅线程间传递，不包含 Flink state 引用）。
     */
    private static class OrderDetailsSyncResult {
        private BigDecimal actualSpotPrice;
        private BigDecimal actualSpotFilledQuantity;
        private BigDecimal spotCost;
        private BigDecimal spotFee;
        private String spotFeeCurrency;
        private String spotOrderType;
        private String spotOrderSide;
        private String spotPosSide;
        private String spotOrderState;
        private String spotTdMode;

        private BigDecimal actualSwapPrice;
        private BigDecimal actualSwapFilledContracts;
        private BigDecimal actualSwapFilledCoin;
        private BigDecimal futuresCost;
        private BigDecimal futuresFee;
        private String futuresFeeCurrency;
        private BigDecimal ctVal;
        private BigDecimal lotSz;
        private BigDecimal minSz;
        private Integer instrumentMaxLeverage;
        private String swapOrderType;
        private String swapOrderSide;
        private String swapPosSide;
        private String swapOrderState;
        private String swapTdMode;

        private BigDecimal totalCost;
        private BigDecimal totalFee;
        private BigDecimal totalExpense;
        private String rawOrderDetailsJson;
    }
    
    
    @Override
    public void onTimer(
            long timestamp,
            OnTimerContext ctx,
            Collector<TradeRecord> out) throws Exception {
        
        // 定时器触发,输出状态日志
        long now = System.currentTimeMillis();
        drainPendingDorisRecords(out);
        
        // 获取当前状态
        PositionState position = positionState.value();
        OpportunityTracker tracker = opportunityTracker.value();
        String symbol = currentSymbol.value();
        ArbitrageOpportunity opportunity = currentOpportunity.value();  // 获取当前套利机会
        applyPendingOrderCommandResult(opportunity, position, out);
        position = positionState.value();
        
        // 如果没有币对信息,跳过本次输出
        if (symbol == null || symbol.isEmpty()) {
            // 注册下一个定时器
            long nextTimerTime = now + statusLogIntervalMs;
            ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
            return;
        }
        
        // 检查是否在黑名单中
        if (isInBlacklist(symbol)) {
            // 在黑名单中,不输出状态日志
            // 注册下一个定时器
            long nextTimerTime = now + statusLogIntervalMs;
            ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
            return;
        }
        
        // 只有持仓状态的币对才输出状态日志
        if (position != null && position.isOpen()) {
            applyPendingOrderDetailsSyncResult(symbol, position);
            printStatusLog(symbol, position, tracker, opportunity, now);  // 传入当前套利机会
            out.collect(buildPositionStatusRecord(symbol, position, tracker, opportunity, now));
        }
        
        // 注册下一个定时器,实现持续定时输出
        long nextTimerTime = now + statusLogIntervalMs;
        ctx.timerService().registerProcessingTimeTimer(nextTimerTime);
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
                position.setSpotFeeCurrency(opp.spotFeeCurrency);
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
                position.setFuturesFeeCurrency(opp.futuresFeeCurrency);
                try {
                    OKXTradingService.InstrumentInfo info = tradingService.getInstrumentInfo(opp.symbol);
                    if (info != null) {
                        position.setCtVal(info.ctVal);
                        position.setLotSz(info.lotSz);
                        position.setMinSz(info.minSz);
                        position.setInstrumentMaxLeverage(info.lever != null ? info.lever.intValue() : null);
                    }
                } catch (Exception e) {
                    logger.warn("⚠️ 同步合约规格信息失败: symbol={}, error={}", opp.symbol, e.getMessage());
                }
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
        String openDirection = resolveOpenDirection(opp);
        if (openDirection == null) {
            return false;
        }
        if ("LONG_SPOT_SHORT_SWAP".equals(openDirection) && !enableLongSpotShortSwap) {
            return false;
        }
        if ("SHORT_SPOT_LONG_SWAP".equals(openDirection) && !enableShortSpotLongSwap) {
            return false;
        }
        BigDecimal spreadRate = opp.spreadRate.divide(new BigDecimal("100"), 6, RoundingMode.HALF_UP);
        BigDecimal spreadAbs = spreadRate.abs();
        if (spreadAbs.compareTo(openThreshold) <= 0) {
            return false;
        }
        BigDecimal netEdge = spreadAbs.subtract(minNetEdgeRate);
        return netEdge.compareTo(BigDecimal.ZERO) > 0;
    }

    private String resolveOpenDirection(ArbitrageOpportunity opp) {
        if (opp == null || opp.arbitrageDirection == null) {
            return null;
        }
        if (opp.arbitrageDirection.contains("做多现货")) {
            return "LONG_SPOT_SHORT_SWAP";
        }
        if (opp.arbitrageDirection.contains("做空现货")) {
            return "SHORT_SPOT_LONG_SWAP";
        }
        return null;
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
    
    /**
     * 异步提交开仓命令，避免主线程阻塞在 REST 调用上。
     */
    private void submitAsyncOpenPosition(ArbitrageOpportunity opp) {
        if (opp == null || opp.symbol == null) {
            return;
        }
        if (!isStrategySupportedForOpportunity(opp)) {
            return;
        }
        if (!tryReservePositionSlot(opp.symbol)) {
            return;
        }
        if (orderCommandInFlight.putIfAbsent(opp.symbol, "OPEN") != null) {
            releasePositionSlotIfReserved(opp.symbol);
            return;
        }
        positionSlotReserved.put(opp.symbol, Boolean.TRUE);
        orderQueryExecutor.submit(() -> {
            OrderCommandResult result = executeOpenOrderCommand(opp);
            pendingOrderCommandResults.put(opp.symbol, result);
        });
    }

    /**
     * 异步提交平仓命令，避免主线程阻塞在 REST 调用上。
     */
    private void submitAsyncClosePosition(ArbitrageOpportunity opp, PositionState pos) {
        if (opp == null || pos == null || pos.getSymbol() == null) {
            return;
        }
        if (orderCommandInFlight.putIfAbsent(pos.getSymbol(), "CLOSE") != null) {
            return;
        }
        orderQueryExecutor.submit(() -> {
            OrderCommandResult result = executeCloseOrderCommand(opp, pos);
            pendingOrderCommandResults.put(pos.getSymbol(), result);
        });
    }

    private OrderCommandResult executeOpenOrderCommand(ArbitrageOpportunity opp) {
        OrderCommandResult result = new OrderCommandResult();
        result.commandType = "OPEN";
        result.symbol = opp.symbol;
        result.spotPrice = opp.spotPrice;
        result.swapPrice = opp.futuresPrice;
        result.spreadRate = opp.spreadRate;
        try {
            BigDecimal coinAmount = tradingService.calculateValidCoinAmount(opp.symbol, tradeAmount, opp.spotPrice);
            result.coinAmount = coinAmount;
            if (coinAmount == null) {
                result.success = false;
                result.errorMessage = "无法计算有效的币数量";
                return result;
            }
            result.direction = resolveOpenDirection(opp);
            if (result.direction == null) {
                result.success = false;
                result.errorMessage = "无法识别套利方向";
                return result;
            }
            String precheckError = precheckOpenOrderCapacity(opp.symbol, result.direction, coinAmount);
            if (precheckError != null) {
                result.success = false;
                result.errorMessage = precheckError;
                return result;
            }
            if ("LONG_SPOT_SHORT_SWAP".equals(result.direction)) {
                result.spotOrderId = tradingService.buySpot(opp.symbol, coinAmount, opp.spotPrice);
                if (result.spotOrderId != null) {
                    result.swapOrderId = tradingService.shortSwap(opp.symbol, coinAmount, opp.futuresPrice, this.leverage);
                }
            } else {
                result.spotOrderId = tradingService.sellSpot(opp.symbol, coinAmount, opp.spotPrice, this.leverage);
                if (result.spotOrderId != null) {
                    result.swapOrderId = tradingService.longSwap(opp.symbol, coinAmount, opp.futuresPrice, this.leverage);
                }
            }
            result.success = result.spotOrderId != null && result.swapOrderId != null;
            if (!result.success) {
                String detail = tradingService.getLastErrorMessage();
                result.errorMessage = detail != null ? detail : "下单失败";
            }
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = e.getMessage() != null ? e.getMessage() : "开仓异常";
        }
        return result;
    }

    /**
     * 开仓前容量预检：下单数量超过账户 max-size 时直接拦截，避免盲下后报错。
     *
     * @return null=通过；非null=失败原因
     */
    private String precheckOpenOrderCapacity(String symbol, String direction, BigDecimal coinAmount) {
        try {
            String spotInstId = symbol + "-USDT";
            String swapInstId = symbol + "-USDT-SWAP";

            OKXTradingService.InstrumentInfo info = tradingService.getInstrumentInfo(symbol);
            if (info == null || info.ctVal == null || info.lotSz == null || info.lotSz.compareTo(BigDecimal.ZERO) <= 0) {
                return "预检失败: 无法获取合约规格信息";
            }

            // 合约张数与下单逻辑保持一致：向下按 lotSz 取整
            BigDecimal rawContractSize = coinAmount.divide(info.ctVal, 8, RoundingMode.DOWN);
            BigDecimal contractLots = rawContractSize.divide(info.lotSz, 0, RoundingMode.DOWN);
            BigDecimal contractSize = contractLots.multiply(info.lotSz);
            if (contractSize.compareTo(BigDecimal.ZERO) <= 0) {
                return "预检失败: 合约下单张数为0";
            }

            // LONG 方向买现货使用 cash 口径；SHORT 方向卖现货使用 cross 口径（杠杆卖出）
            String spotTdMode = "LONG_SPOT_SHORT_SWAP".equals(direction) ? "cash" : "cross";
            OKXTradingService.MaxSizeInfo spotMax = tradingService.queryMaxSize(spotInstId, spotTdMode);
            OKXTradingService.MaxSizeInfo swapMax = tradingService.queryMaxSize(swapInstId, "cross");
            if (spotMax == null || swapMax == null) {
                return "预检失败: 无法获取账户 max-size";
            }

            if ("LONG_SPOT_SHORT_SWAP".equals(direction)) {
                if (coinAmount.compareTo(spotMax.maxBuy) > 0) {
                    return String.format("预检失败: 现货可买上限不足 required=%s, maxBuy=%s",
                        coinAmount.toPlainString(), spotMax.maxBuy.toPlainString());
                }
                if (contractSize.compareTo(swapMax.maxSell) > 0) {
                    return String.format("预检失败: 合约可卖上限不足 required=%s, maxSell=%s",
                        contractSize.toPlainString(), swapMax.maxSell.toPlainString());
                }
                return null;
            }

            if (coinAmount.compareTo(spotMax.maxSell) > 0) {
                return String.format("预检失败: 现货可卖上限不足 required=%s, maxSell=%s",
                    coinAmount.toPlainString(), spotMax.maxSell.toPlainString());
            }
            if (contractSize.compareTo(swapMax.maxBuy) > 0) {
                return String.format("预检失败: 合约可买上限不足 required=%s, maxBuy=%s",
                    contractSize.toPlainString(), swapMax.maxBuy.toPlainString());
            }
            return null;
        } catch (Exception e) {
            return "预检异常: " + e.getMessage();
        }
    }

    /**
     * 开仓前策略支持校验：避免不支持杠杆卖出现货的币对进入下单链路。
     */
    private boolean isStrategySupportedForOpportunity(ArbitrageOpportunity opp) {
        String standardSymbol = opp.symbol + "-USDT";
        String openDirection = resolveOpenDirection(opp);
        if ("SHORT_SPOT_LONG_SWAP".equals(openDirection) && !marginCache.supportsStrategyA(standardSymbol)) {
            logger.warn("⚠️ 币种 {} 不支持 SHORT_SPOT_LONG_SWAP（现货杠杆卖出），拒绝开仓", opp.symbol);
            return false;
        }
        if ("LONG_SPOT_SHORT_SWAP".equals(openDirection) && !marginCache.supportsStrategyB(standardSymbol)) {
            logger.warn("⚠️ 币种 {} 不支持 LONG_SPOT_SHORT_SWAP，拒绝开仓", opp.symbol);
            return false;
        }
        return true;
    }

    /**
     * 预占持仓槽位，防止并发开仓把可用保证金打满。
     */
    private boolean tryReservePositionSlot(String symbol) {
        try {
            long currentPositionCount = redisManager.getCounter(REDIS_KEY_POSITION_COUNT);
            if (currentPositionCount >= maxPositions) {
                logger.info("⚠️ 全局持仓数量已达上限 ({}/{}), 拒绝开仓: {}", currentPositionCount, maxPositions, symbol);
                return false;
            }
            long newCount = redisManager.incr(REDIS_KEY_POSITION_COUNT);
            logger.info("✅ 预占持仓槽位成功: {} -> {} ({})", currentPositionCount, newCount, symbol);
            return true;
        } catch (Exception e) {
            logger.error("❌ 预占持仓槽位失败: symbol={}, error={}", symbol, e.getMessage(), e);
            return false;
        }
    }

    private void releasePositionSlotIfReserved(String symbol) {
        if (symbol == null || positionSlotReserved == null) {
            return;
        }
        if (positionSlotReserved.remove(symbol) == null) {
            return;
        }
        try {
            long newCount = redisManager.decr(REDIS_KEY_POSITION_COUNT);
            logger.info("🔄 回滚持仓槽位成功: {} ({})", newCount, symbol);
        } catch (Exception e) {
            logger.error("❌ 回滚持仓槽位失败: symbol={}, error={}", symbol, e.getMessage(), e);
        }
    }

    private OrderCommandResult executeCloseOrderCommand(ArbitrageOpportunity opp, PositionState pos) {
        OrderCommandResult result = new OrderCommandResult();
        result.commandType = "CLOSE";
        result.symbol = pos.getSymbol();
        result.direction = pos.getDirection();
        result.coinAmount = pos.getAmount();
        result.spotPrice = opp.spotPrice;
        result.swapPrice = opp.futuresPrice;
        try {
            if ("LONG_SPOT_SHORT_SWAP".equals(pos.getDirection())) {
                result.spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice, this.leverage);
                result.swapOrderId = tradingService.closeShortSwap(pos.getSymbol(), pos.getAmount());
            } else {
                result.spotOrderId = tradingService.buySpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice);
                result.swapOrderId = tradingService.closeLongSwap(pos.getSymbol(), pos.getAmount());
            }
            result.success = result.spotOrderId != null && result.swapOrderId != null;
            if (!result.success) {
                String detail = tradingService.getLastErrorMessage();
                result.errorMessage = detail != null ? detail : "平仓下单失败";
            }
        } catch (Exception e) {
            result.success = false;
            result.errorMessage = e.getMessage() != null ? e.getMessage() : "平仓异常";
        }
        return result;
    }

    /**
     * 主线程消费异步下单结果，统一更新 Flink state 与输出日志/事件。
     */
    private void applyPendingOrderCommandResult(
            ArbitrageOpportunity opp,
            PositionState currentPos,
            Collector<TradeRecord> out) throws Exception {
        if (opp == null || opp.symbol == null) {
            return;
        }
        OrderCommandResult result = pendingOrderCommandResults.remove(opp.symbol);
        if (result == null) {
            return;
        }
        orderCommandInFlight.remove(opp.symbol);
        String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

        if ("OPEN".equals(result.commandType)) {
            if (result.success) {
                PositionState tempPosition = new PositionState();
                tempPosition.setSymbol(result.symbol);
                tempPosition.setOpen(true);
                tempPosition.setDirection(result.direction);
                tempPosition.setAmount(result.coinAmount);
                tempPosition.setEntrySpotPrice(result.spotPrice);
                tempPosition.setEntrySwapPrice(result.swapPrice);
                tempPosition.setEntrySpreadRate(result.spreadRate);
                tempPosition.setOpenTime(System.currentTimeMillis());
                tempPosition.setSpotOrderId(result.spotOrderId);
                tempPosition.setSwapOrderId(result.swapOrderId);
                tempPosition.setLastLogTime(System.currentTimeMillis());
                positionState.update(tempPosition);

                orderDetailWriter.write(String.format("%s,%s,OPEN,%s,SPOT,%s,%s,%s,PENDING,订单已提交",
                    timestamp, result.symbol, result.direction, result.spotOrderId, result.spotPrice, result.coinAmount));
                orderDetailWriter.write(String.format("%s,%s,OPEN,%s,SWAP,%s,%s,%s,PENDING,订单已提交",
                    timestamp, result.symbol, result.direction, result.swapOrderId, result.swapPrice, result.coinAmount));
                out.collect(buildOpenPendingRecord(opp, result.direction, result.spotOrderId, result.swapOrderId, result.coinAmount));
            } else {
                releasePositionSlotIfReserved(result.symbol);
                recordFailure(result.symbol, result.errorMessage != null ? result.errorMessage : "开仓失败");
                orderDetailWriter.write(String.format("%s,%s,OPEN,%s,ERROR,N/A,N/A,N/A,FAILED,%s",
                    timestamp, result.symbol, result.direction != null ? result.direction : "UNKNOWN",
                    (result.errorMessage != null ? result.errorMessage : "开仓失败").replace(",", ";")));
                out.collect(buildFailureRecord(
                    opp,
                    result.direction != null ? result.direction : "UNKNOWN",
                    "ERROR",
                    result.errorMessage != null ? result.errorMessage : "开仓失败",
                    result.spotOrderId,
                    result.swapOrderId,
                    result.coinAmount
                ));
            }
            return;
        }

        if ("CLOSE".equals(result.commandType)) {
            if (result.success && currentPos != null) {
                orderDetailWriter.write(String.format("%s,%s,CLOSE,%s,SPOT,%s,%s,%s,PENDING,平仓订单已提交",
                    timestamp, result.symbol, result.direction, result.spotOrderId, result.spotPrice, result.coinAmount));
                orderDetailWriter.write(String.format("%s,%s,CLOSE,%s,SWAP,%s,%s,%s,PENDING,平仓订单已提交",
                    timestamp, result.symbol, result.direction, result.swapOrderId, result.swapPrice, result.coinAmount));
                out.collect(buildClosePendingRecord(opp, currentPos, result.spotOrderId, result.swapOrderId));

                currentPos.setOpen(false);
                currentPos.setLastUpdateTime(System.currentTimeMillis());
                positionState.update(currentPos);
                releasePositionSlotIfReserved(result.symbol);
            } else {
                String reason = result.errorMessage != null ? result.errorMessage : "平仓失败";
                orderDetailWriter.write(String.format("%s,%s,CLOSE,%s,ERROR,N/A,N/A,N/A,FAILED,%s",
                    timestamp, result.symbol, result.direction != null ? result.direction : "UNKNOWN",
                    reason.replace(",", ";")));
                if (currentPos != null) {
                    out.collect(buildCloseFailureRecord(opp, currentPos, reason));
                }
            }
        }
    }

    private static class OrderCommandResult {
        private String commandType;
        private String symbol;
        private String direction;
        private BigDecimal coinAmount;
        private BigDecimal spotPrice;
        private BigDecimal swapPrice;
        private BigDecimal spreadRate;
        private String spotOrderId;
        private String swapOrderId;
        private boolean success;
        private String errorMessage;
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

        // ext_json: 关键指标快照（小体量，便于排障）
        record.extJson = buildExtSnapshotJson(record);
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
        // 方向口径统一：持仓事件优先使用 PositionState.direction（枚举），避免混入中文描述
        record.direction = position != null ? position.getDirection() : (opp != null ? opp.arbitrageDirection : null);
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
            // 下单参考价：以开仓委托价格为准（与 order_spot_price/order_swap_price 字段语义一致）
            record.orderSpotPrice = position.getEntrySpotPrice();
            record.orderSwapPrice = position.getEntrySwapPrice();
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
            record.spotFeeCcy = position.getSpotFeeCurrency();
            record.swapFee = position.getFuturesFee();
            record.swapFeeCcy = position.getFuturesFeeCurrency();
            record.totalFee = position.getTotalFee();
            record.totalExpense = position.getTotalExpense();
            record.unrealizedProfit = position.getUnrealizedProfit();
            record.ctVal = position.getCtVal();
            record.lotSz = position.getLotSz();
            record.minSz = position.getMinSz();
            record.instrumentMaxLeverage = position.getInstrumentMaxLeverage();
            record.spotOrderType = position.getSpotOrderType();
            record.spotOrderSide = position.getSpotOrderSide();
            record.spotPosSide = position.getSpotPosSide();
            record.spotOrderState = position.getSpotOrderState();
            record.spotTdMode = position.getSpotTdMode();
            record.swapOrderType = position.getSwapOrderType();
            record.swapOrderSide = position.getSwapOrderSide();
            record.swapPosSide = position.getSwapPosSide();
            record.swapOrderState = position.getSwapOrderState();
            record.swapTdMode = position.getSwapTdMode();
            if (position.getLastOrderDetailsRawJson() != null && !position.getLastOrderDetailsRawJson().trim().isEmpty()) {
                record.rawPayloadJson = position.getLastOrderDetailsRawJson();
            }
        }

        // raw_payload_json: 机会对象快照（裁剪后）
        if (opp != null) {
            if (record.rawPayloadJson == null || record.rawPayloadJson.trim().isEmpty()) {
                record.rawPayloadJson = truncateJsonQuietly(opp, RAW_JSON_MAX_LEN);
            }
            // 对于无持仓的事件，也补一下下单参考价（发现阶段）
            if (record.orderSpotPrice == null) {
                record.orderSpotPrice = opp.spotPrice;
            }
            if (record.orderSwapPrice == null) {
                record.orderSwapPrice = opp.futuresPrice;
            }
            if (record.entrySpreadRate == null) {
                record.entrySpreadRate = opp.spreadRate;
            }
        }
        return record;
    }

    private BigDecimal multiplyOrNull(BigDecimal left, BigDecimal right) {
        if (left == null || right == null) {
            return null;
        }
        return left.multiply(right);
    }

    private void drainPendingDorisRecords(Collector<TradeRecord> out) {
        if (pendingDorisRecords == null) {
            return;
        }
        TradeRecord record;
        while ((record = pendingDorisRecords.poll()) != null) {
            out.collect(record);
        }
    }

    private String buildExtSnapshotJson(TradeRecord record) {
        try {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("eventType", record.eventType);
            root.put("eventStage", record.eventStage);
            root.put("symbol", record.symbol);
            if (record.spotOrderId != null) {
                root.put("spotOrderId", record.spotOrderId);
            }
            if (record.swapOrderId != null) {
                root.put("swapOrderId", record.swapOrderId);
            }
            if (record.amountCoin != null) {
                root.put("amountCoin", record.amountCoin.toPlainString());
            }
            if (record.amountUsdt != null) {
                root.put("amountUsdt", record.amountUsdt.toPlainString());
            }
            if (record.currentSpreadRate != null) {
                root.put("currentSpreadRate", record.currentSpreadRate.toPlainString());
            }
            if (record.unrealizedProfit != null) {
                root.put("unrealizedProfit", record.unrealizedProfit.toPlainString());
            }
            if (record.realizedProfit != null) {
                root.put("realizedProfit", record.realizedProfit.toPlainString());
            }
            if (record.ctVal != null) {
                root.put("ctVal", record.ctVal.toPlainString());
            }
            if (record.instrumentMaxLeverage != null) {
                root.put("instrumentMaxLeverage", record.instrumentMaxLeverage);
            }
            return root.toString();
        } catch (Exception e) {
            return null;
        }
    }

    private String truncateJsonQuietly(Object obj, int maxLen) {
        try {
            String json = OBJECT_MAPPER.writeValueAsString(obj);
            if (json == null) {
                return null;
            }
            if (json.length() <= maxLen) {
                return json;
            }
            return json.substring(0, maxLen);
        } catch (Exception e) {
            return null;
        }
    }

    private String buildOrderDetailsRawJson(
            String symbol,
            String spotOrderId,
            String swapOrderId,
            com.fasterxml.jackson.databind.JsonNode spotDetail,
            com.fasterxml.jackson.databind.JsonNode swapDetail) {
        try {
            ObjectNode root = OBJECT_MAPPER.createObjectNode();
            root.put("symbol", symbol);
            if (spotOrderId != null) {
                root.put("spotOrderId", spotOrderId);
            }
            if (swapOrderId != null) {
                root.put("swapOrderId", swapOrderId);
            }
            if (spotDetail != null) {
                root.set("spotDetail", spotDetail);
            }
            if (swapDetail != null) {
                root.set("swapDetail", swapDetail);
            }
            String json = root.toString();
            if (json.length() <= RAW_JSON_MAX_LEN) {
                return json;
            }
            return json.substring(0, RAW_JSON_MAX_LEN);
        } catch (Exception e) {
            return null;
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
    
}

