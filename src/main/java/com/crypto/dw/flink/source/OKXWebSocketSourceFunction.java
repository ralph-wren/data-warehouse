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
 * - 订阅指定交易对的 Ticker 数据
 * - 将数据发送到 Flink 数据流
 * - 支持 Flink 的 Checkpoint 机制
 * - 自动重连和错误处理
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
            // 创建 WebSocket 客户端
            String wsUrl = config.getString("okx.websocket.url", "wss://ws.okx.com:8443/ws/v5/public");
            wsClient = new OKXWebSocketClientInternal(wsUrl, ctx, symbols);
            
            // 连接 WebSocket
            wsClient.connectBlocking();
            
            if (wsClient.isOpen()) {
                logger.info("WebSocket connected successfully");
                logger.info("========================================");
                logger.info("Data collection started");
                logger.info("========================================");
            } else {
                logger.error("Failed to connect to WebSocket");
                throw new RuntimeException("WebSocket connection failed");
            }
            
            // 启动统计线程
            startStatisticsThread();
            
            // 保持运行,直到被取消
            while (running.get() && wsClient.isOpen()) {
                try {
                    // 每秒检查一次连接状态
                    Thread.sleep(1000);
                    
                    // 如果连接断开,尝试重连
                    if (!wsClient.isOpen() && running.get()) {
                        logger.warn("WebSocket disconnected, attempting to reconnect...");
                        wsClient.reconnectBlocking();
                        
                        if (wsClient.isOpen()) {
                            logger.info("WebSocket reconnected successfully");
                        } else {
                            logger.error("Failed to reconnect WebSocket");
                            throw new RuntimeException("WebSocket reconnection failed");
                        }
                    }
                    
                } catch (InterruptedException e) {
                    logger.info("Source function interrupted");
                    Thread.currentThread().interrupt();
                    break;
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
            if (wsClient != null) {
                logger.info("Closing WebSocket connection...");
                wsClient.close();
            }
            
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
            
            // 订阅 Ticker 频道
            for (String symbol : symbols) {
                String subscribeMsg = String.format(
                    "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"%s\"}]}",
                    symbol
                );
                send(subscribeMsg);
                logger.info("Subscribed to ticker: {}", symbol);
            }
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
