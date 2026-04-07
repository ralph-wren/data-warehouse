package com.crypto.dw.flink.source;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.redis.RedisConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OKX K线 WebSocket Source Function
 * <p>
 * 从 Redis 动态获取订阅配置，订阅 OKX K线数据
 * <p>
 * 功能:
 * - 从 Redis kline:subscriptions 获取订阅（格式: BTC-USDT:4H）
 * - 定时刷新订阅（默认 5 分钟）
 * - 动态添加/取消订阅
 * - 订阅 K线数据（candle channel）
 * - 将数据发送到 Flink 数据流
 * - 支持 Flink 的 Checkpoint 机制
 * - 自动重连和错误处理
 * <p>
 * Redis 数据格式:
 * <pre>
 * # Set 类型，存储订阅
 * SADD kline:subscriptions "BTC-USDT:4H"
 * SADD kline:subscriptions "ETH-USDT:1H"
 * SADD kline:subscriptions "SOL-USDT:15m"
 * </pre>
 * <p>
 * OKX K线周期映射:
 * <pre>
 * 1m, 3m, 5m, 15m, 30m (分钟)
 * 1H, 2H, 4H, 6H, 12H (小时)
 * 1D, 1W, 1M, 3M (天/周/月)
 * </pre>
 */
public class OKXKlineWebSocketSourceFunction extends RichSourceFunction<String> {

    private static final Logger logger = LoggerFactory.getLogger(OKXKlineWebSocketSourceFunction.class);

    private final ConfigLoader config;
    private final int refreshIntervalSeconds;
    private final int maxReconnectAttempts;
    private final long initialReconnectDelayMs;
    private final long maxReconnectDelayMs;

    // Redis 连接管理器
    private transient RedisConnectionManager redisManager;

    // WebSocket 客户端
    private transient OKXKlineWebSocketClientInternal wsClient;

    // 运行标志
    private final AtomicBoolean running = new AtomicBoolean(true);

    // 当前订阅的币对和周期（格式: BTC-USDT:4H）
    private final Set<String> currentSubscriptions = ConcurrentHashMap.newKeySet();

    // 统计信息
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    private final AtomicLong subscriptionRefreshCount = new AtomicLong(0);

    /**
     * 构造函数
     *
     * @param config 配置加载器
     */
    public OKXKlineWebSocketSourceFunction(ConfigLoader config) {
        this.config = config;
        this.refreshIntervalSeconds = config.getInt("kline.subscription.refresh-interval-seconds", 300);
        this.maxReconnectAttempts = config.getInt("okx.websocket.reconnect.max-retries", 10);
        this.initialReconnectDelayMs = config.getLong("okx.websocket.reconnect.initial-delay", 1000L);
        this.maxReconnectDelayMs = config.getLong("okx.websocket.reconnect.max-delay", 60000L);
    }

    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);

        logger.info("OKX K线 WebSocket Source Function 初始化");
        logger.info("刷新间隔: {} 秒", refreshIntervalSeconds);

        // 初始化 Redis 连接管理器
        redisManager = new RedisConnectionManager(config);
        
        // 测试 Redis 连接
        if (!redisManager.testConnection()) {
            throw new RuntimeException("Failed to connect to Redis");
        }
        
        logger.info("Redis 连接管理器初始化成功");
    }

    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        logger.info("Starting OKX K-line WebSocket connection...");

        try {
            // 启动统计线程
            startStatisticsThread();

            // 启动订阅刷新线程
            startSubscriptionRefreshThread(ctx);

            int reconnectAttempts = 0;

            // 保持运行,直到被取消
            while (running.get()) {
                try {
                    if (wsClient == null || !wsClient.isOpen()) {
                        if (reconnectAttempts >= maxReconnectAttempts) {
                            throw new RuntimeException("WebSocket reconnect attempts exceeded limit: " + maxReconnectAttempts);
                        }

                        if (reconnectAttempts > 0) {
                            long reconnectDelayMs = calculateReconnectDelayMs(reconnectAttempts);
                            logger.warn(
                                    "WebSocket disconnected, attempt {} to reconnect after {} ms",
                                    reconnectAttempts,
                                    reconnectDelayMs
                            );
                            Thread.sleep(reconnectDelayMs);
                        }

                        reconnectAttempts++;
                        connectWebSocket(ctx);

                        if (!wsClient.isOpen()) {
                            throw new RuntimeException("WebSocket connection failed");
                        }

                        reconnectAttempts = 0;
                        logger.info("WebSocket connected successfully");

                        // 连接成功后，立即加载并订阅
                        refreshSubscriptions();
                    }

                    // 每秒检查一次连接状态
                    Thread.sleep(1000);
                } catch (InterruptedException e) {
                    logger.info("Source function interrupted");
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                    logger.error("WebSocket loop error, will retry if allowed: {}", e.getMessage());
                    closeWebSocketQuietly();
                }
            }

        } catch (Exception e) {
            logger.error("Error in WebSocket source function", e);
            throw e;
        }
    }

    @Override
    public void cancel() {
        logger.info("取消 OKX K线 WebSocket Source Function");

        running.set(false);

        try {
            // 关闭 WebSocket
            closeWebSocketQuietly();

            // 关闭 Redis 连接管理器
            if (redisManager != null) {
                redisManager.close();
                logger.info("Redis 连接管理器已关闭");
            }

            // 打印最终统计
            logger.info("最终统计: WebSocket消息={}, 错误={}, 订阅刷新={}, 当前订阅={}",
                    messageCount.get(), errorCount.get(), subscriptionRefreshCount.get(), currentSubscriptions.size());

        } catch (Exception e) {
            logger.error("取消过程中发生错误", e);
        }
    }

    /**
     * 创建并连接 WebSocket 客户端
     */
    private void connectWebSocket(SourceContext<String> ctx) throws Exception {
        closeWebSocketQuietly();

        // K线数据使用 business 频道
        String wsUrl = config.getString("okx.websocket.kline-url");
        wsClient = new OKXKlineWebSocketClientInternal(wsUrl, ctx);
        wsClient.connectBlocking();
    }

    /**
     * 关闭 WebSocket
     */
    private void closeWebSocketQuietly() {
        try {
            if (wsClient != null) {
                logger.info("Closing WebSocket connection...");
                wsClient.close();
            }
        } catch (Exception e) {
            logger.warn("Failed to close WebSocket cleanly: {}", e.getMessage());
        } finally {
            wsClient = null;
        }
    }

    /**
     * 计算指数退避重连间隔
     */
    private long calculateReconnectDelayMs(int reconnectAttempts) {
        return Math.min(
                initialReconnectDelayMs * (long) Math.pow(2, Math.max(0, reconnectAttempts - 1)),
                maxReconnectDelayMs
        );
    }

    /**
     * 从 Redis 获取订阅配置
     *
     * @return 订阅集合（格式: BTC-USDT:4H）
     */
    private Set<String> getSubscriptionsFromRedis() {
        try {
            Set<String> subscriptions = redisManager.getSet("kline:subscriptions");
            logger.debug("Fetched {} subscriptions from Redis", subscriptions.size());
            return subscriptions;
        } catch (Exception e) {
            logger.error("Failed to fetch subscriptions from Redis: {}", e.getMessage(), e);
            return Collections.emptySet();
        }
    }

    /**
     * 刷新订阅
     * <p>
     * 比较 Redis 中的订阅和当前订阅，动态添加/取消订阅
     */
    private void refreshSubscriptions() {
        if (wsClient == null || !wsClient.isOpen()) {
            logger.warn("WebSocket not connected, skip subscription refresh");
            return;
        }

        try {
            subscriptionRefreshCount.incrementAndGet();

            // 从 Redis 获取最新订阅
            Set<String> redisSubscriptions = getSubscriptionsFromRedis();

            if (redisSubscriptions.isEmpty()) {
                logger.warn("No subscriptions found in Redis (kline:subscriptions)");
                return;
            }

            logger.info("Redis subscriptions: {}", redisSubscriptions);

            // 找出新增的订阅
            Set<String> toAdd = new HashSet<>(redisSubscriptions);
            toAdd.removeAll(currentSubscriptions);

            // 找出需要取消的订阅
            Set<String> toRemove = new HashSet<>(currentSubscriptions);
            toRemove.removeAll(redisSubscriptions);

            // 添加新订阅
            for (String subscription : toAdd) {
                try {
                    // 去除空格和特殊字符
                    subscription = subscription.trim();
                    
                    String[] parts = subscription.split(":");
                    if (parts.length != 2) {
                        logger.warn("无效的订阅格式: {}, 期望格式: BTC-USDT:4H", subscription);
                        continue;
                    }

                    String symbol = parts[0].trim();
                    String interval = parts[1].trim();
                    
                    // 验证周期格式
                    if (!isValidInterval(interval)) {
                        logger.warn("无效的K线周期: {}, 支持的周期: 1m,3m,5m,15m,30m,1H,2H,4H,6H,12H,1D,1W,1M,3M", interval);
                        continue;
                    }

                    // 订阅 K线
                    wsClient.subscribeKline(symbol, interval);
                    currentSubscriptions.add(subscription);

                    logger.info("已添加订阅: symbol={}, interval={}, 总数={}", symbol, interval, currentSubscriptions.size());
                } catch (Exception e) {
                    logger.error("添加订阅失败: {}, 错误: {}", subscription, e.getMessage());
                }
            }

            // 取消订阅
            for (String subscription : toRemove) {
                try {
                    String[] parts = subscription.split(":");
                    if (parts.length != 2) {
                        continue;
                    }

                    String symbol = parts[0].trim();
                    String interval = parts[1].trim();

                    // 取消订阅 K线
                    wsClient.unsubscribeKline(symbol, interval);
                    currentSubscriptions.remove(subscription);

                    logger.info("已移除订阅: symbol={}, interval={}, 总数={}", symbol, interval, currentSubscriptions.size());
                } catch (Exception e) {
                    logger.error("移除订阅失败: {}, 错误: {}", subscription, e.getMessage());
                }
            }

            if (toAdd.isEmpty() && toRemove.isEmpty()) {
                logger.debug("订阅无变化");
            } else {
                logger.info("订阅刷新完成: 新增={}, 移除={}, 总数={}", toAdd.size(), toRemove.size(), currentSubscriptions.size());
            }

        } catch (Exception e) {
            logger.error("Failed to refresh subscriptions: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 验证 K线周期格式是否有效
     * 
     * @param interval K线周期
     * @return true 表示有效，false 表示无效
     */
    private boolean isValidInterval(String interval) {
        // 支持的周期列表
        Set<String> validIntervals = new HashSet<>();
        // 分钟级别
        validIntervals.add("1m");
        validIntervals.add("3m");
        validIntervals.add("5m");
        validIntervals.add("15m");
        validIntervals.add("30m");
        // 小时级别
        validIntervals.add("1H");
        validIntervals.add("2H");
        validIntervals.add("4H");
        validIntervals.add("6H");
        validIntervals.add("12H");
        // 日/周/月级别
        validIntervals.add("1D");
        validIntervals.add("1Dutc");
        validIntervals.add("1W");
        validIntervals.add("1Wutc");
        validIntervals.add("1M");
        validIntervals.add("1Mutc");
        validIntervals.add("3M");
        validIntervals.add("3Mutc");
        
        return validIntervals.contains(interval);
    }

    /**
     * 启动订阅刷新线程
     */
    private void startSubscriptionRefreshThread(SourceContext<String> ctx) {
        Thread refreshThread = new Thread(() -> {
            logger.info("Subscription refresh thread started (interval: {} seconds)", refreshIntervalSeconds);

            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    // 刷新订阅
                    refreshSubscriptions();

                    // 等待下一次刷新
                    Thread.sleep(refreshIntervalSeconds * 1000L);

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    logger.error("Error in subscription refresh thread: {}", e.getMessage(), e);
                }
            }

            logger.info("Subscription refresh thread stopped");
        });

        refreshThread.setDaemon(true);
        refreshThread.setName("okx-subscription-refresh-thread");
        refreshThread.start();
    }

    /**
     * 启动统计信息线程
     */
    private void startStatisticsThread() {
        Thread statsThread = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000); // 每分钟打印一次

                    logger.info("统计信息: WebSocket状态={}, 消息数={}, 错误数={}, 订阅刷新={}, 当前订阅数={}", 
                            wsClient != null && wsClient.isOpen() ? "已连接" : "未连接",
                            messageCount.get(),
                            errorCount.get(),
                            subscriptionRefreshCount.get(),
                            currentSubscriptions.size());
                    
                    if (!currentSubscriptions.isEmpty()) {
                        logger.debug("当前订阅列表: {}", currentSubscriptions);
                    }

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });

        statsThread.setDaemon(true);
        statsThread.setName("okx-statistics-thread");
        statsThread.start();
    }

    /**
     * 内部 WebSocket 客户端
     * <p>
     * 负责接收 OKX K线数据并发送到 Flink SourceContext
     */
    private class OKXKlineWebSocketClientInternal extends WebSocketClient {

        private final SourceContext<String> sourceContext;
        private final ObjectMapper objectMapper = new ObjectMapper();

        public OKXKlineWebSocketClientInternal(String serverUri, SourceContext<String> sourceContext) throws Exception {
            super(new URI(serverUri));
            this.sourceContext = sourceContext;
        }

        @Override
        public void onOpen(ServerHandshake handshakedata) {
            logger.info("WebSocket 连接已建立");
        }

        @Override
        public void onMessage(String message) {
            try {
                // 增加消息计数
                messageCount.incrementAndGet();

                // 解析消息，过滤订阅确认和错误消息
                JsonNode rootNode = objectMapper.readTree(message);

                // 检查是否是订阅确认消息或错误消息
                if (rootNode.has("event")) {
                    String event = rootNode.get("event").asText();
                    if ("subscribe".equals(event)) {
                        logger.info("订阅确认: {}", message);
                        return;
                    } else if ("unsubscribe".equals(event)) {
                        logger.info("取消订阅确认: {}", message);
                        return;
                    } else if ("error".equals(event)) {
                        String errorMsg = rootNode.has("msg") ? rootNode.get("msg").asText() : "Unknown error";
                        String errorCode = rootNode.has("code") ? rootNode.get("code").asText() : "N/A";
                        logger.error("订阅错误 [code={}]: {}", errorCode, errorMsg);
                        logger.error("完整错误消息: {}", message);
                        errorCount.incrementAndGet();
                        return;
                    }
                }

                // 发送 K线数据到 Flink 数据流
                // 注意: 需要在 checkpoint lock 中进行,保证 Exactly-Once 语义
                synchronized (sourceContext.getCheckpointLock()) {
                    sourceContext.collect(message);
                }

                if (logger.isTraceEnabled()) {
                    logger.trace("Collected K-line message: {}", message);
                }

            } catch (Exception e) {
                logger.error("Error processing message: {}", e.getMessage());
                errorCount.incrementAndGet();
            }
        }

        @Override
        public void onClose(int code, String reason, boolean remote) {
            logger.warn("WebSocket 连接已关闭, 代码: {}, 原因: {}, 远程: {}", code, reason, remote);
        }

        @Override
        public void onError(Exception ex) {
            logger.error("WebSocket 错误: {}", ex.getMessage());
            errorCount.incrementAndGet();
        }

        /**
         * 订阅 K线
         *
         * @param symbol   交易对，如 BTC-USDT
         * @param interval K线周期，如 4H, 1H, 15m
         */
        public void subscribeKline(String symbol, String interval) {
            String channel = "candle" + interval;
            String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"%s\",\"instId\":\"%s\"}]}",
                    channel, symbol
            );
            send(subscribeMsg);
            logger.info("订阅K线数据，交易对: {}, 间隔: {}", symbol, interval);
        }

        /**
         * 取消订阅 K线
         *
         * @param symbol   交易对，如 BTC-USDT
         * @param interval K线周期，如 4H, 1H, 15m
         */
        public void unsubscribeKline(String symbol, String interval) {
            String channel = "candle" + interval;
            String unsubscribeMsg = String.format(
                    "{\"op\":\"unsubscribe\",\"args\":[{\"channel\":\"%s\",\"instId\":\"%s\"}]}",
                    channel, symbol
            );
            send(unsubscribeMsg);
            logger.info("取消订阅K线数据，交易对: {}, 间隔: {}", symbol, interval);
        }
    }
}
