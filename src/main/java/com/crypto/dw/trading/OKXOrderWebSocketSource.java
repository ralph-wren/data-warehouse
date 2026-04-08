package com.crypto.dw.trading;

import com.crypto.dw.config.ConfigLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * OKX 订单 WebSocket 数据源
 * 
 * 功能：
 * 1. 订阅 OKX 私有频道的订单更新
 * 2. 实时接收订单状态变化
 * 3. 发送到 Flink 数据流
 * 
 * 订阅频道：
 * - orders: 订单更新
 * - orders-algo: 策略订单更新
 * 
 * 使用场景：
 * - 监控订单成交状态
 * - 确认开仓/平仓是否成功
 * - 实时风险控制
 */
public class OKXOrderWebSocketSource extends RichSourceFunction<String> {
    
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(OKXOrderWebSocketSource.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    private final ConfigLoader config;
    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final boolean isSimulated;
    private final boolean proxyEnabled;
    private final String proxyHost;
    private final int proxyPort;
    
    private transient OKXOrderWebSocketClient wsClient;
    private final AtomicBoolean running = new AtomicBoolean(true);
    private transient ScheduledExecutorService pingScheduler;
    
    public OKXOrderWebSocketSource(ConfigLoader config) {
        this.config = config;
        this.apiKey = config.getString("okx.api.key", "");
        this.secretKey = config.getString("okx.api.secret", "");
        this.passphrase = config.getString("okx.api.passphrase", "");
        this.isSimulated = config.getBoolean("okx.api.simulated", true);
        this.proxyEnabled = config.getBoolean("okx.proxy.enabled", false);
        this.proxyHost = config.getString("okx.proxy.host", "localhost");
        this.proxyPort = config.getInt("okx.proxy.port", 10809);
    }
    
    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);
        logger.info("========================================");
        logger.info("OKX Order WebSocket Source 初始化");
        logger.info("模式: {}", isSimulated ? "模拟交易" : "实盘交易");
        logger.info("代理: {}", proxyEnabled ? (proxyHost + ":" + proxyPort) : "未启用");
        logger.info("========================================");
        
        // 创建心跳调度器
        pingScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "OKX-Order-Ping");
            t.setDaemon(true);
            return t;
        });
    }
    
    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        logger.info("启动 OKX Order WebSocket 连接...");
        
        int reconnectAttempts = 0;
        int maxReconnectAttempts = 10;
        long initialReconnectDelayMs = 1000L;
        long maxReconnectDelayMs = 60000L;
        
        while (running.get()) {
            try {
                if (wsClient == null || !wsClient.isOpen()) {
                    if (reconnectAttempts >= maxReconnectAttempts) {
                        throw new RuntimeException("WebSocket 重连次数超过限制: " + maxReconnectAttempts);
                    }
                    
                    if (reconnectAttempts > 0) {
                        long reconnectDelayMs = Math.min(
                            initialReconnectDelayMs * (long) Math.pow(2, reconnectAttempts - 1),
                            maxReconnectDelayMs
                        );
                        logger.warn("WebSocket 断开，尝试重连 ({}/{}) 延迟 {} ms",
                            reconnectAttempts, maxReconnectAttempts, reconnectDelayMs);
                        Thread.sleep(reconnectDelayMs);
                    }
                    
                    reconnectAttempts++;
                    connectWebSocket(ctx);
                    
                    if (!wsClient.isOpen()) {
                        throw new RuntimeException("WebSocket 连接失败");
                    }
                    
                    reconnectAttempts = 0;
                    logger.info("✓ WebSocket 连接成功");
                }
                
                Thread.sleep(1000);
                
            } catch (InterruptedException e) {
                logger.info("Source function 被中断");
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                logger.error("WebSocket 循环错误: {}", e.getMessage(), e);
                closeWebSocketQuietly();
            }
        }
    }
    
    @Override
    public void cancel() {
        logger.info("取消 OKX Order WebSocket Source");
        running.set(false);
        
        // 停止心跳调度器
        if (pingScheduler != null && !pingScheduler.isShutdown()) {
            pingScheduler.shutdown();
            try {
                if (!pingScheduler.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)) {
                    pingScheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                pingScheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        
        closeWebSocketQuietly();
    }
    
    /**
     * 连接 WebSocket
     */
    private void connectWebSocket(SourceContext<String> ctx) throws Exception {
        closeWebSocketQuietly();
        
        // 私有频道 URL
        String wsUrl = config.getString("okx.websocket.private-url", "wss://ws.okx.com:8443/ws/v5/private");
        wsClient = new OKXOrderWebSocketClient(wsUrl, ctx);
        
        // 如果启用了代理，设置代理
        if (proxyEnabled) {
            logger.info("使用代理连接: {}:{}", proxyHost, proxyPort);
            
            // 验证代理配置
            if (proxyHost == null || proxyHost.isEmpty()) {
                throw new IllegalArgumentException("代理主机地址为空");
            }
            if (proxyPort <= 0 || proxyPort > 65535) {
                throw new IllegalArgumentException("代理端口无效: " + proxyPort);
            }
            
            try {
                Proxy proxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(proxyHost, proxyPort));
                wsClient.setProxy(proxy);
                logger.debug("代理设置成功: SOCKS {}:{}", proxyHost, proxyPort);
            } catch (Exception e) {
                logger.error("设置代理失败: {}", e.getMessage());
                throw e;
            }
        } else {
            logger.info("直连模式(未使用代理)");
        }
        
        logger.info("正在连接到: {}", wsUrl);
        wsClient.connectBlocking();
        
        // 启动心跳任务（每15秒发送一次ping）
        if (pingScheduler != null && !pingScheduler.isShutdown()) {
            pingScheduler.scheduleAtFixedRate(() -> {
                try {
                    if (wsClient != null && wsClient.isOpen()) {
                        wsClient.send("ping");
                        logger.debug("发送心跳 ping");
                    }
                } catch (Exception e) {
                    logger.warn("发送心跳失败: {}", e.getMessage());
                }
            }, 15, 15, java.util.concurrent.TimeUnit.SECONDS);
        }
    }
    
    /**
     * 关闭 WebSocket
     */
    private void closeWebSocketQuietly() {
        try {
            if (wsClient != null) {
                logger.info("关闭 WebSocket 连接...");
                wsClient.close();
            }
        } catch (Exception e) {
            logger.warn("关闭 WebSocket 失败: {}", e.getMessage());
        } finally {
            wsClient = null;
        }
    }
    
    /**
     * 生成签名
     */
    private String generateSignature(String timestamp, String method, String requestPath, String body) throws Exception {
        String preHash = timestamp + method + requestPath + body;
        
        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec secretKeySpec = new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
        mac.init(secretKeySpec);
        
        byte[] hash = mac.doFinal(preHash.getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(hash);
    }
    
    /**
     * 内部 WebSocket 客户端
     */
    private class OKXOrderWebSocketClient extends WebSocketClient {
        
        private final SourceContext<String> sourceContext;
        
        public OKXOrderWebSocketClient(String serverUri, SourceContext<String> sourceContext) throws Exception {
            super(new URI(serverUri));
            this.sourceContext = sourceContext;
        }
        
        @Override
        public void onOpen(ServerHandshake handshakedata) {
            logger.info("WebSocket 连接已建立");
            
            try {
                // 登录认证
                login();
                
                // 等待登录成功
                Thread.sleep(1000);
                
                // 订阅订单频道
                subscribeOrders();
                
            } catch (Exception e) {
                logger.error("订阅失败: {}", e.getMessage(), e);
            }
        }
        
        @Override
        public void onMessage(String message) {
            try {
                // 处理 ping/pong
                if ("pong".equals(message)) {
                    logger.debug("收到心跳 pong");
                    return;
                }
                
                // 解析消息
                JsonNode rootNode = OBJECT_MAPPER.readTree(message);
                
                // 检查是否是事件消息
                if (rootNode.has("event")) {
                    String event = rootNode.get("event").asText();
                    
                    if ("login".equals(event)) {
                        String code = rootNode.get("code").asText();
                        if ("0".equals(code)) {
                            logger.info("✓ 登录成功");
                        } else {
                            logger.error("✗ 登录失败: {}", message);
                        }
                        return;
                    } else if ("subscribe".equals(event)) {
                        logger.info("✓ 订阅确认: {}", message);
                        return;
                    } else if ("error".equals(event)) {
                        logger.error("✗ 错误消息: {}", message);
                        return;
                    }
                }
                
                // 处理订单数据
                if (rootNode.has("data")) {
                    // 发送到 Flink 数据流
                    synchronized (sourceContext.getCheckpointLock()) {
                        sourceContext.collect(message);
                    }
                    
                    // 记录日志
                    if (logger.isDebugEnabled()) {
                        JsonNode dataArray = rootNode.get("data");
                        if (dataArray.isArray() && dataArray.size() > 0) {
                            JsonNode order = dataArray.get(0);
                            String orderId = order.get("ordId").asText();
                            String instId = order.get("instId").asText();
                            String state = order.get("state").asText();
                            logger.debug("订单更新: orderId={}, instId={}, state={}", orderId, instId, state);
                        }
                    }
                }
                
            } catch (Exception e) {
                logger.error("处理消息失败: {}", e.getMessage(), e);
            }
        }
        
        @Override
        public void onClose(int code, String reason, boolean remote) {
            String closeReason = (reason == null || reason.isEmpty()) ? "未提供原因" : reason;
            String initiator = remote ? "服务器" : "客户端";
            
            if (code == 1000) {
                logger.info("WebSocket 正常关闭: code={}, reason={}, 发起方={}", code, closeReason, initiator);
            } else if (code == -1) {
                logger.warn("WebSocket 异常关闭: code={}, reason={}, 发起方={} (可能是连接失败或SSL握手失败)", 
                    code, closeReason, initiator);
            } else {
                logger.warn("WebSocket 连接关闭: code={}, reason={}, 发起方={}", code, closeReason, initiator);
            }
        }
        
        @Override
        public void onError(Exception ex) {
            String errorType = ex.getClass().getSimpleName();
            String errorMsg = ex.getMessage();
            
            if (ex instanceof javax.net.ssl.SSLHandshakeException) {
                logger.error("SSL 握手失败: {} - 可能原因: 1) 代理配置错误 2) 网络不稳定 3) 证书问题", errorMsg);
                logger.error("建议检查: 1) 代理地址和端口是否正确 2) 代理是否支持 HTTPS 3) 网络连接是否正常");
            } else if (ex instanceof java.net.ConnectException) {
                logger.error("连接失败: {} - 可能原因: 1) 代理服务未启动 2) 代理地址错误 3) 网络不通", errorMsg);
            } else if (ex instanceof java.net.SocketTimeoutException) {
                logger.error("连接超时: {} - 可能原因: 1) 网络延迟过高 2) 代理响应慢 3) 服务器无响应", errorMsg);
            } else {
                logger.error("WebSocket 错误 [{}]: {}", errorType, errorMsg, ex);
            }
        }
        
        /**
         * 登录认证
         */
        private void login() throws Exception {
            String timestamp = Instant.now().getEpochSecond() + "";
            String method = "GET";
            String requestPath = "/users/self/verify";
            String sign = generateSignature(timestamp, method, requestPath, "");
            
            String loginMsg = String.format(
                "{\"op\":\"login\",\"args\":[{\"apiKey\":\"%s\",\"passphrase\":\"%s\",\"timestamp\":\"%s\",\"sign\":\"%s\"}]}",
                apiKey, passphrase, timestamp, sign
            );
            
            send(loginMsg);
            logger.info("发送登录请求...");
        }
        
        /**
         * 订阅订单频道
         */
        private void subscribeOrders() {
            // 订阅所有交易品种的订单
            String subscribeMsg = "{\"op\":\"subscribe\",\"args\":[" +
                "{\"channel\":\"orders\",\"instType\":\"SPOT\"}," +
                "{\"channel\":\"orders\",\"instType\":\"SWAP\"}" +
                "]}";
            
            send(subscribeMsg);
            logger.info("订阅订单频道: SPOT + SWAP");
        }
    }
}
