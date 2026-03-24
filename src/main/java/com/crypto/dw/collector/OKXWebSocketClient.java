package com.crypto.dw.collector;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.kafka.KafkaProducerManager;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
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
 * 
 * 支持同时订阅现货（SPOT）和合约（SWAP）：
 * - 现货：BTC-USDT（SPOT）
 * - 合约：BTC-USDT-SWAP（永续合约）
 * 
 * 数据发送到不同的 Kafka Topic：
 * - 现货：crypto-ticker-spot
 * - 合约：crypto-ticker-swap
 */
@Slf4j
public class OKXWebSocketClient extends WebSocketClient {
    
    private static final Logger logger = LoggerFactory.getLogger(OKXWebSocketClient.class);
    
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final KafkaProducerManager kafkaProducer;
    private final ConfigLoader config;
    private final List<String> symbols;
    
    private final AtomicBoolean isConnected = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private final AtomicLong errorCount = new AtomicLong(0);
    
    // 统计信息：现货和合约分别计数
    private final AtomicLong spotMessageCount = new AtomicLong(0);
    private final AtomicLong swapMessageCount = new AtomicLong(0);
    
    private int reconnectAttempts = 0;
    private final int maxReconnectAttempts;
    private final long initialReconnectDelay;
    private final long maxReconnectDelay;
    
    // Kafka Topic 配置
    private final String spotTopic;
    private final String swapTopic;
    
    public OKXWebSocketClient(ConfigLoader config, KafkaProducerManager kafkaProducer, List<String> symbols) {
        super(URI.create(config.getString("okx.websocket.url")));
        this.config = config;
        this.kafkaProducer = kafkaProducer;
        this.symbols = symbols;
        
        this.maxReconnectAttempts = config.getInt("okx.websocket.reconnect.max-retries", 10);
        this.initialReconnectDelay = config.getLong("okx.websocket.reconnect.initial-delay", 1000);
        this.maxReconnectDelay = config.getLong("okx.websocket.reconnect.max-delay", 60000);
        
        // 读取 Kafka Topic 配置
        this.spotTopic = config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot");
        this.swapTopic = config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap");
        
        logger.info("OKX WebSocket Client created. URL: {}", getURI());
        logger.info("Spot Topic: {}, Swap Topic: {}", spotTopic, swapTopic);
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
     * 
     * 同时订阅现货和合约：
     * - 现货：BTC-USDT（SPOT）
     * - 合约：BTC-USDT-SWAP（永续合约）
     * 
     * OKX WebSocket 订阅格式：
     * {"op": "subscribe", "args": [
     *   {"channel": "tickers", "instId": "BTC-USDT"},
     *   {"channel": "tickers", "instId": "BTC-USDT-SWAP"}
     * ]}
     */
    private void subscribe() {
        try {
            StringBuilder sb = new StringBuilder();
            sb.append("{\"op\":\"subscribe\",\"args\":[");
            
            int argCount = 0;
            
            // 订阅现货和合约
            for (String symbol : symbols) {
                // 现货：BTC-USDT
                if (argCount > 0) {
                    sb.append(",");
                }
                sb.append("{\"channel\":\"tickers\",\"instId\":\"").append(symbol).append("\"}");
                argCount++;
                
                // 合约：BTC-USDT-SWAP
                sb.append(",");
                sb.append("{\"channel\":\"tickers\",\"instId\":\"").append(symbol).append("-SWAP\"}");
                argCount++;
            }
            
            sb.append("]}");
            
            String subscribeMessage = sb.toString();
            logger.info("Subscribing to tickers (SPOT + SWAP): {}", subscribeMessage);
            logger.info("Total subscriptions: {} (SPOT: {}, SWAP: {})", 
                argCount, symbols.size(), symbols.size());
            
            send(subscribeMessage);
            
        } catch (Exception e) {
            logger.error("Failed to subscribe: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 处理 Ticker 数据
     * 
     * 根据 instId 判断是现货还是合约：
     * - 现货：BTC-USDT → 发送到 crypto-ticker-spot
     * - 合约：BTC-USDT-SWAP → 发送到 crypto-ticker-swap
     */
    private void processTickerData(JsonNode dataNode) {
        try {
            // 解析 Ticker 数据
            TickerData tickerData = objectMapper.treeToValue(dataNode, TickerData.class);
            
            String instId = tickerData.getInstId();
            String jsonData = objectMapper.writeValueAsString(tickerData);
            
            // 判断是现货还是合约
            boolean isSwap = instId.endsWith("-SWAP");
            String topic = isSwap ? swapTopic : spotTopic;
            String type = isSwap ? "SWAP" : "SPOT";
            
            // 更新统计信息
            if (isSwap) {
                swapMessageCount.incrementAndGet();
            } else {
                spotMessageCount.incrementAndGet();
            }
            
            log.info("Processing ticker [{}]: {}, Price: {}", type, instId, tickerData.getLast());
            
            // 发送到 Kafka（指定 Topic）
            kafkaProducer.send(topic, instId, jsonData)
                .thenAccept(metadata -> {
                    log.info("✓ Sent to Kafka [{}]: {} → Topic: {}, Partition: {}, Offset: {}", 
                        type, instId, topic, metadata.partition(), metadata.offset());
                    if (logger.isDebugEnabled()) {
                        logger.debug("Ticker data sent to Kafka. Type: {}, InstId: {}, Price: {}, Topic: {}, Partition: {}, Offset: {}", 
                            type, instId, tickerData.getLast(), topic, metadata.partition(), metadata.offset());
                    }
                })
                .exceptionally(ex -> {
                    log.error("❌ Failed to send to Kafka [{}]: {}, Error: {}", type, instId, ex.getMessage());
                    logger.error("Failed to send ticker data to Kafka. Type: {}, InstId: {}, Error: {}", 
                        type, instId, ex.getMessage());
                    return null;
                });
            
            // 定期打印统计信息
            long count = messageCount.get();
            if (count % 100 == 0) {
                logger.info("Statistics - Total: {}, SPOT: {}, SWAP: {}, Errors: {}, Kafka Success: {}, Kafka Failure: {}", 
                    count, spotMessageCount.get(), swapMessageCount.get(), 
                    errorCount.get(), kafkaProducer.getSuccessCount(), kafkaProducer.getFailureCount());
            }
            
        } catch (Exception e) {
            log.error("Error processing ticker data: " + e.getMessage());
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
