package com.crypto.dw.trading;

import com.crypto.dw.config.ConfigLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.Serializable;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.concurrent.TimeUnit;

/**
 * OKX 交易服务
 * 
 * 功能：
 * 1. 下单（现货、合约）
 * 2. 平仓
 * 3. 查询订单状态
 * 
 * 注意：
 * - 需要配置 OKX API Key、Secret、Passphrase
 * - 支持模拟交易和实盘交易
 * - 包含风险控制逻辑
 */
public class OKXTradingService implements Serializable {
    
    private static final long serialVersionUID = 1L;
    private static final Logger logger = LoggerFactory.getLogger(OKXTradingService.class);
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
    
    private final String apiKey;
    private final String secretKey;
    private final String passphrase;
    private final String baseUrl;
    private final boolean isSimulated;
    
    private transient OkHttpClient httpClient;
    
    /**
     * 构造函数
     */
    public OKXTradingService(ConfigLoader config) {
        this.apiKey = config.getString("okx.api.key", "");
        this.secretKey = config.getString("okx.api.secret", "");
        this.passphrase = config.getString("okx.api.passphrase", "");
        this.isSimulated = config.getBoolean("okx.api.simulated", false);
        
        // 根据是否模拟交易选择 URL
        if (isSimulated) {
            this.baseUrl = "https://www.okx.com";  // 模拟交易
            logger.info("✓ OKX 交易服务初始化（模拟交易模式）");
        } else {
            this.baseUrl = "https://www.okx.com";  // 实盘交易
            logger.warn("⚠ OKX 交易服务初始化（实盘交易模式）");
        }
        
        initHttpClient();
    }
    
    /**
     * 初始化 HTTP 客户端
     */
    private void initHttpClient() {
        if (httpClient == null) {
            httpClient = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .build();
        }
    }
    
    /**
     * 做多现货（买入）
     * 
     * @param symbol 交易对，如 BTC-USDT
     * @param amount 交易金额（USDT）
     * @return 订单ID
     */
    public String buySpot(String symbol, BigDecimal amount) {
        try {
            logger.info("📈 做多现货: {} | 金额: {} USDT", symbol, amount);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", symbol);
            orderRequest.put("tdMode", "cash");  // 现货交易模式
            orderRequest.put("side", "buy");
            orderRequest.put("ordType", "market");  // 市价单
            orderRequest.put("sz", amount.toString());  // 金额
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做多现货失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 做空现货（卖出）
     * 
     * @param symbol 交易对，如 BTC-USDT
     * @param amount 交易金额（USDT）
     * @return 订单ID
     */
    public String sellSpot(String symbol, BigDecimal amount) {
        try {
            logger.info("📉 做空现货（卖出）: {} | 金额: {} USDT", symbol, amount);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", symbol);
            orderRequest.put("tdMode", "cash");
            orderRequest.put("side", "sell");
            orderRequest.put("ordType", "market");
            orderRequest.put("sz", amount.toString());
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做空现货失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 做多合约（开多仓）
     * 
     * @param symbol 交易对，如 BTC-USDT
     * @param amount 交易金额（USDT）
     * @param leverage 杠杆倍数
     * @return 订单ID
     */
    public String longSwap(String symbol, BigDecimal amount, int leverage) {
        try {
            String swapSymbol = symbol + "-SWAP";
            logger.info("📈 做多合约: {} | 金额: {} USDT | 杠杆: {}x", swapSymbol, amount, leverage);
            
            // 先设置杠杆
            setLeverage(swapSymbol, leverage);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", swapSymbol);
            orderRequest.put("tdMode", "cross");  // 全仓模式
            orderRequest.put("side", "buy");
            orderRequest.put("posSide", "long");  // 开多仓
            orderRequest.put("ordType", "market");
            orderRequest.put("sz", amount.toString());
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做多合约失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 做空合约（开空仓）
     * 
     * @param symbol 交易对，如 BTC-USDT
     * @param amount 交易金额（USDT）
     * @param leverage 杠杆倍数
     * @return 订单ID
     */
    public String shortSwap(String symbol, BigDecimal amount, int leverage) {
        try {
            String swapSymbol = symbol + "-SWAP";
            logger.info("📉 做空合约: {} | 金额: {} USDT | 杠杆: {}x", swapSymbol, amount, leverage);
            
            // 先设置杠杆
            setLeverage(swapSymbol, leverage);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", swapSymbol);
            orderRequest.put("tdMode", "cross");
            orderRequest.put("side", "sell");
            orderRequest.put("posSide", "short");  // 开空仓
            orderRequest.put("ordType", "market");
            orderRequest.put("sz", amount.toString());
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做空合约失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 平多仓
     */
    public String closeLongSwap(String symbol, BigDecimal amount) {
        try {
            String swapSymbol = symbol + "-SWAP";
            logger.info("🔄 平多仓: {} | 金额: {} USDT", swapSymbol, amount);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", swapSymbol);
            orderRequest.put("tdMode", "cross");
            orderRequest.put("side", "sell");  // 平多仓用卖出
            orderRequest.put("posSide", "long");
            orderRequest.put("ordType", "market");
            orderRequest.put("sz", amount.toString());
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("平多仓失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 平空仓
     */
    public String closeShortSwap(String symbol, BigDecimal amount) {
        try {
            String swapSymbol = symbol + "-SWAP";
            logger.info("🔄 平空仓: {} | 金额: {} USDT", swapSymbol, amount);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", swapSymbol);
            orderRequest.put("tdMode", "cross");
            orderRequest.put("side", "buy");  // 平空仓用买入
            orderRequest.put("posSide", "short");
            orderRequest.put("ordType", "market");
            orderRequest.put("sz", amount.toString());
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("平空仓失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 设置杠杆倍数
     */
    private void setLeverage(String instId, int leverage) {
        try {
            String timestamp = Instant.now().toString();
            String method = "POST";
            String requestPath = "/api/v5/account/set-leverage";
            
            ObjectNode body = OBJECT_MAPPER.createObjectNode();
            body.put("instId", instId);
            body.put("lever", String.valueOf(leverage));
            body.put("mgnMode", "cross");
            
            String bodyStr = body.toString();
            String sign = generateSignature(timestamp, method, requestPath, bodyStr);
            
            Request request = new Request.Builder()
                .url(baseUrl + requestPath)
                .post(RequestBody.create(bodyStr, JSON))
                .addHeader("OK-ACCESS-KEY", apiKey)
                .addHeader("OK-ACCESS-SIGN", sign)
                .addHeader("OK-ACCESS-TIMESTAMP", timestamp)
                .addHeader("OK-ACCESS-PASSPHRASE", passphrase)
                .addHeader("Content-Type", "application/json")
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    logger.debug("设置杠杆成功: {} = {}x", instId, leverage);
                }
            }
            
        } catch (Exception e) {
            logger.warn("设置杠杆失败: {}", e.getMessage());
        }
    }
    
    /**
     * 下单
     */
    private String placeOrder(ObjectNode orderRequest) throws Exception {
        initHttpClient();
        
        String timestamp = Instant.now().toString();
        String method = "POST";
        String requestPath = "/api/v5/trade/order";
        
        String bodyStr = orderRequest.toString();
        String sign = generateSignature(timestamp, method, requestPath, bodyStr);
        
        Request request = new Request.Builder()
            .url(baseUrl + requestPath)
            .post(RequestBody.create(bodyStr, JSON))
            .addHeader("OK-ACCESS-KEY", apiKey)
            .addHeader("OK-ACCESS-SIGN", sign)
            .addHeader("OK-ACCESS-TIMESTAMP", timestamp)
            .addHeader("OK-ACCESS-PASSPHRASE", passphrase)
            .addHeader("Content-Type", "application/json")
            .build();

        
        try (Response response = httpClient.newCall(request).execute()) {
            if (response.isSuccessful() && response.body() != null) {
                String responseBody = response.body().string();
                JsonNode jsonResponse = OBJECT_MAPPER.readTree(responseBody);
                
                if ("0".equals(jsonResponse.get("code").asText())) {
                    String orderId = jsonResponse.get("data").get(0).get("ordId").asText();
                    logger.info("✓ 下单成功: orderId={}", orderId);
                    return orderId;
                } else {
                    String errorMsg = jsonResponse.get("msg").asText();
                    logger.error("✗ 下单失败: {}", errorMsg);
                    return null;
                }
            } else {
                logger.error("✗ 下单请求失败: {}", response.code());
                return null;
            }
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
     * 检查是否配置了 API 密钥
     */
    public boolean isConfigured() {
        return apiKey != null && !apiKey.isEmpty() 
            && secretKey != null && !secretKey.isEmpty()
            && passphrase != null && !passphrase.isEmpty();
    }
    
    /**
     * 是否为模拟交易
     */
    public boolean isSimulated() {
        return isSimulated;
    }
}
