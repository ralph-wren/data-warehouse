package com.crypto.dw.collector;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.kafka.KafkaProducerManager;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * OKX WebSocket 客户端
 * 订阅实时行情数据并发送到 Kafka
 */
public class OKXWebSocketClient extends WebSocketClient {
    
    private static final Logger logger = LoggerFactory.getLogger(OKXWebSocketClient.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaProducerManager kafkaProducer;
    private final ConfigLoader config;
    private final List<String> symbols;
    
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    
    private int reconnectAttempts = 0;
    private final int maxReconnectAttempts;
    private final long initialReconnectDelay;
    private final long maxReconnectDelay;
    
    public OKXWebSocketClient(ConfigLoader config, KafkaProducerManager kafkaProducer, List<String> symbols) {
        super(URI.create(config.getString("okx.websocket.url")));
        this.config = config;
        this.kafkaProducer = kafkaProducer;
        this.symbols = symbols;
        
        this.maxReconnectAttempts = config.getInt("okx.websocket.reconnect.max-retries", 10);
        this.initialReconnectDelay = config.getLong("okx.websocket.reconnect.initial-delay", 1000);
        this.maxReconnectDelay = config.getLong("okx.websocket.reconnect.max-delay", 60000);
        
        logger.info("OKX WebSocket Client created. URL: {}", getURI());
    }
    
    @Override
    public void onOpen(ServerHandshake handshakedata) {
        isConnected.set(true);
        reconnectAttempts = 0;
        logger.info("WebSocket connection opened. Status: {}", handshakedata.getHttpStatus());
        
        // 订阅 Ticker 频道
        subscribe();
    }
    
    @Override
    public void onMessage(String message) {
        try {
            messageCount.incrementAndGet();
            
            if (logger.isTraceEnabled()) {
                logger.trace("Received message: {}", message);
            }
            
            // 解析消息
            JsonNode rootNode = objectMapper.readTree(message);
            
            // 检查是否是订阅确认消息
            if (rootNode.has("event")) {
                String event = rootNode.get("event").asText();
                if ("subscribe".equals(event)) {
                    logger.info("Subscription confirmed: {}", message);
                    return;
                } else if ("error".equals(event)) {
                    logger.error("Subscription error: {}", message);
                    return;
                }
            }
            
            // 处理 Ticker 数据
            if (rootNode.has("data")) {
                JsonNode dataArray = rootNode.get("data");
                if (dataArray.isArray() && dataArray.size() > 0) {
                    for (JsonNode dataNode : dataArray) {
                        processTickerData(dataNode);
                    }
                }
            }
            
        } catch (Exception e) {
            errorCount.incrementAndGet();
            logger.error("Error processing message: {}", e.getMessage(), e);
        }
    }
    
    @Override
    public void onClose(int code, String reason, boolean remote) {
        isConnected.set(false);
        logger.warn("WebSocket connection closed. Code: {}, Reason: {}, Remote: {}", 
            code, reason, remote);
        
        // 尝试重连
        if (reconnectAttempts < maxReconnectAttempts) {
            scheduleReconnect();
        } else {
            logger.error("Max reconnect attempts reached. Giving up.");
        }
    }
    
    @Override
    public void onError(Exception ex) {
        errorCount.incrementAndGet();
        logger.error("WebSocket error: {}", ex.getMessage(), ex);
    }
    
    /**
     * 订阅 Ticker 频道
     */
    private void subscribe() {
        try {
            // 构建订阅消息
            // OKX WebSocket 订阅格式：
            // {"op": "subscribe", "args": [{"channel": "tickers", "instId": "BTC-USDT"}]}
            
            StringBuilder sb = new StringBuilder();
            sb.append("{\"op\":\"subscribe\",\"args\":[");
            
            for (int i = 0; i < symbols.size(); i++) {
                if (i > 0) {
                    sb.append(",");
                }
                sb.append("{\"channel\":\"tickers\",\"instId\":\"").append(symbols.get(i)).append("\"}");
            }
            
            sb.append("]}");
            
            String subscribeMessage = sb.toString();
            logger.info("Subscribing to tickers: {}", subscribeMessage);
            
            send(subscribeMessage);
            
        } catch (Exception e) {
            logger.error("Failed to subscribe: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 处理 Ticker 数据
     */
    private void processTickerData(JsonNode dataNode) {
        try {
            // 解析 Ticker 数据
            TickerData tickerData = objectMapper.treeToValue(dataNode, TickerData.class);
            
            String instId = tickerData.getInstId();
            String jsonData = objectMapper.writeValueAsString(tickerData);
            
            System.out.println("Processing ticker: " + instId + ", Price: " + tickerData.getLast());
            
            // 发送到 Kafka
            kafkaProducer.send(instId, jsonData)
                .thenAccept(metadata -> {
                    System.out.println("✓ Sent to Kafka: " + instId + ", Partition: " + metadata.partition() + ", Offset: " + metadata.offset());
                    if (logger.isDebugEnabled()) {
                        logger.debug("Ticker data sent to Kafka. InstId: {}, Price: {}, Partition: {}, Offset: {}", 
                            instId, tickerData.getLast(), metadata.partition(), metadata.offset());
                    }
                })
                .exceptionally(ex -> {
                    System.err.println("❌ Failed to send to Kafka: " + instId + ", Error: " + ex.getMessage());
                    logger.error("Failed to send ticker data to Kafka. InstId: {}, Error: {}", 
                        instId, ex.getMessage());
                    return null;
                });
            
            // 定期打印统计信息
            long count = messageCount.get();
            if (count % 100 == 0) {
                logger.info("Statistics - Messages: {}, Errors: {}, Kafka Success: {}, Kafka Failure: {}", 
                    count, errorCount.get(), kafkaProducer.getSuccessCount(), kafkaProducer.getFailureCount());
            }
            
        } catch (Exception e) {
            System.err.println("Error processing ticker data: " + e.getMessage());
            logger.error("Error processing ticker data: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 安排重连
     */
    private void scheduleReconnect() {
        reconnectAttempts++;
        
        // 计算重连延迟（指数退避）
        long delay = Math.min(
            initialReconnectDelay * (long) Math.pow(2, reconnectAttempts - 1),
            maxReconnectDelay
        );
        
        logger.info("Scheduling reconnect attempt {} in {} ms", reconnectAttempts, delay);
        
        new Thread(() -> {
            try {
                Thread.sleep(delay);
                logger.info("Attempting to reconnect...");
                reconnect();
            } catch (InterruptedException e) {
                logger.error("Reconnect interrupted", e);
                Thread.currentThread().interrupt();
            }
        }).start();
    }
    
    /**
     * 检查连接状态
     */
    public boolean isConnected() {
        return isConnected.get() && !isClosed();
    }
    
    /**
     * 获取消息计数
     */
    public long getMessageCount() {
        return messageCount.get();
    }
    
    /**
     * 获取错误计数
     */
    public long getErrorCount() {
        return errorCount.get();
    }
    
    /**
     * 优雅关闭
     */
    public void shutdown() {
        logger.info("Shutting down WebSocket client...");
        close();
    }
}
