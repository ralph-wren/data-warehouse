package com.crypto.dw.flink.source;

import com.crypto.dw.config.ConfigLoader;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OKX WebSocket Source Function
 * 
 * 从 OKX WebSocket 接收实时行情数据,作为 Flink Source
 * 
 * 功能:
 * - 连接 OKX WebSocket 公共频道
 * - 同时订阅现货（SPOT）和合约（SWAP）的 Ticker 数据
 * - 将数据发送到 Flink 数据流（带类型标记）
 * - 支持 Flink 的 Checkpoint 机制
 * - 自动重连和错误处理
 * 
 * 数据格式:
 * - 现货：{"type":"SPOT","data":{...}}
 * - 合约：{"type":"SWAP","data":{...}}
 * 
 * 使用示例:
 * <pre>
 * ConfigLoader config = ConfigLoader.getInstance();
 * List<String> symbols = Arrays.asList("BTC-USDT", "ETH-USDT");
 * 
 * DataStream<String> stream = env.addSource(
 *     new OKXWebSocketSourceFunction(config, symbols)
 * ).name("OKX WebSocket Source");
 * </pre>
 */
public class OKXWebSocketSourceFunction extends RichSourceFunction<String> {
    
    private static final Logger logger = LoggerFactory.getLogger(OKXWebSocketSourceFunction.class);
    
    private final ConfigLoader config;
    private final List<String> symbols;
    private final int maxReconnectAttempts;
    private final long initialReconnectDelayMs;
    private final long maxReconnectDelayMs;
    
    // WebSocket 客户端
    private transient OKXWebSocketClientInternal wsClient;
    
    // 运行标志
    private final AtomicBoolean running = new AtomicBoolean(true);
    
    // 统计信息
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    
    /**
     * 构造函数
     * 
     * @param config 配置加载器
     * @param symbols 订阅的交易对列表
     */
    public OKXWebSocketSourceFunction(ConfigLoader config, List<String> symbols) {
        this.config = config;
        this.symbols = symbols;
        // 复用配置文件中的重连参数，避免这里和普通 WebSocket 客户端的重试策略脱节
        this.maxReconnectAttempts = config.getInt("okx.websocket.reconnect.max-retries", 10);
        this.initialReconnectDelayMs = config.getLong("okx.websocket.reconnect.initial-delay", 1000L);
        this.maxReconnectDelayMs = config.getLong("okx.websocket.reconnect.max-delay", 60000L);
    }
    
    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);
        
        logger.info("========================================");
        logger.info("OKX WebSocket Source Function Opening");
        logger.info("Symbols: {}", symbols);
        logger.info("========================================");
    }
    
    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        logger.info("Starting OKX WebSocket connection...");
        
        try {
            // 启动统计线程
            startStatisticsThread();

            // 修复说明：
            // 1. 外层循环只由 running 控制，避免连接一断开就直接退出 run()
            // 2. 断开连接后统一在循环内做重连，而不是依赖 while 条件外的偶然时机
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
                        logger.info("========================================");
                        logger.info("Data collection started");
                        logger.info("========================================");
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
        logger.info("========================================");
        logger.info("Cancelling OKX WebSocket Source Function");
        logger.info("========================================");
        
        running.set(false);
        
        try {
            // 关闭 WebSocket
            closeWebSocketQuietly();
            
            // 打印最终统计
            logger.info("========================================");
            logger.info("Final Statistics:");
            logger.info("  WebSocket Messages: {}", messageCount.get());
            logger.info("  WebSocket Errors: {}", errorCount.get());
            logger.info("========================================");
            
        } catch (Exception e) {
            logger.error("Error during cancellation", e);
        }
    }

    /**
     * 创建并连接 WebSocket 客户端
     *
     * 每次重连都创建新客户端，避免复用已关闭实例带来的状态问题
     */
    private void connectWebSocket(SourceContext<String> ctx) throws Exception {
        closeWebSocketQuietly();

        // Ticker 数据使用 public 频道
        String wsUrl = config.getString("okx.websocket.ticker-url");
        wsClient = new OKXWebSocketClientInternal(wsUrl, ctx, symbols);
        wsClient.connectBlocking();
    }

    /**
     * 关闭 WebSocket，供 cancel() 和异常恢复共用
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
            // 关闭后立即清空引用，避免后续误用旧连接实例
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
     * 启动统计信息线程
     */
    private void startStatisticsThread() {
        Thread statsThread = new Thread(() -> {
            while (running.get() && !Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000); // 每分钟打印一次
                    
                    logger.info("========================================");
                    logger.info("Statistics (1 minute interval):");
                    logger.info("  WebSocket Status: {}", wsClient != null && wsClient.isOpen() ? "Connected" : "Disconnected");
                    logger.info("  Messages Received: {}", messageCount.get());
                    logger.info("  WebSocket Errors: {}", errorCount.get());
                    logger.info("========================================");
                    
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
     * 
     * 负责接收 OKX WebSocket 数据并发送到 Flink SourceContext
     */
    private class OKXWebSocketClientInternal extends WebSocketClient {
        
        private final SourceContext<String> sourceContext;
        private final List<String> symbols;
        
        public OKXWebSocketClientInternal(String serverUri, SourceContext<String> sourceContext, List<String> symbols) throws Exception {
            super(new URI(serverUri));
            this.sourceContext = sourceContext;
            this.symbols = symbols;
        }
        
        @Override
        public void onOpen(ServerHandshake handshakedata) {
            logger.info("WebSocket connection opened");
            
            // 同时订阅现货和合约 Ticker 频道
            // 现货：BTC-USDT
            // 合约：BTC-USDT-SWAP
            for (String symbol : symbols) {
                // 订阅现货
                String spotSubscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"%s\"}]}",
                    symbol
                );
                send(spotSubscribeMsg);
                logger.info("Subscribed to SPOT ticker: {}", symbol);
                
                // 订阅合约
                String swapSubscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"%s-SWAP\"}]}",
                    symbol
                );
                send(swapSubscribeMsg);
                logger.info("Subscribed to SWAP ticker: {}-SWAP", symbol);
            }
            
            logger.info("Total subscriptions: {} (SPOT: {}, SWAP: {})", 
                symbols.size() * 2, symbols.size(), symbols.size());
        }
        
        @Override
        public void onMessage(String message) {
            try {
                // 增加消息计数
                messageCount.incrementAndGet();
                
                // 发送数据到 Flink 数据流
                // 注意: 需要在 checkpoint lock 中进行,保证 Exactly-Once 语义
                synchronized (sourceContext.getCheckpointLock()) {
                    sourceContext.collect(message);
                }
                
            } catch (Exception e) {
                logger.error("Error processing message: {}", e.getMessage());
                errorCount.incrementAndGet();
            }
        }
        
        @Override
        public void onClose(int code, String reason, boolean remote) {
            logger.warn("WebSocket connection closed. Code: {}, Reason: {}, Remote: {}", code, reason, remote);
        }
        
        @Override
        public void onError(Exception ex) {
            logger.error("WebSocket error: {}", ex.getMessage());
            errorCount.incrementAndGet();
        }
    }
}
