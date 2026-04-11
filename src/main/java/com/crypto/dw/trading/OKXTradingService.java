package com.crypto.dw.trading;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.redis.RedisConnectionManager;
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
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
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
    
    // Redis 连接管理器,用于缓存交易对信息
    private transient RedisConnectionManager redisManager;
    
    // 杠杆支持信息缓存（从 Redis 加载到内存）
    private transient MarginSupportCache marginCache;
    
    // Redis Hash Key,用于存储交易对信息
    private static final String INSTRUMENT_CACHE_KEY = "okx:instrument:cache";
    
    // 最近查询的借币利息率（用于在开仓后获取）
    private BigDecimal lastBorrowInterestRate = null;
    
    // 最近的错误信息（用于在下单失败后获取详细错误）
    private String lastErrorMessage = null;
    
    /**
     * 交易对信息
     */
    public static class InstrumentInfo implements Serializable {
        private static final long serialVersionUID = 1L;
        
        public final String instId;      // 交易对ID,如 "COMP-USDT-SWAP"
        public final BigDecimal ctVal;   // 合约面值,如 0.1 表示 1 张 = 0.1 个币
        public final BigDecimal lotSz;   // 最小交易单位,如 1 表示最小下单 1 张
        public final BigDecimal minSz;   // 最小下单数量
        
        @com.fasterxml.jackson.annotation.JsonCreator
        public InstrumentInfo(
                @com.fasterxml.jackson.annotation.JsonProperty("instId") String instId,
                @com.fasterxml.jackson.annotation.JsonProperty("ctVal") BigDecimal ctVal,
                @com.fasterxml.jackson.annotation.JsonProperty("lotSz") BigDecimal lotSz,
                @com.fasterxml.jackson.annotation.JsonProperty("minSz") BigDecimal minSz) {
            this.instId = instId;
            this.ctVal = ctVal;
            this.lotSz = lotSz;
            this.minSz = minSz;
        }
        
        @Override
        public String toString() {
            return String.format("InstrumentInfo{instId='%s', ctVal=%s, lotSz=%s, minSz=%s}", 
                instId, ctVal, lotSz, minSz);
        }
    }
    
    /**
     * 构造函数（不带杠杆缓存）
     */
    public OKXTradingService(ConfigLoader config) {
        this(config, null);
    }
    
    /**
     * 构造函数（带杠杆缓存）
     * 
     * @param config 配置加载器
     * @param marginCache 杠杆支持信息缓存（可选，如果提供则使用内存缓存，否则调用 API）
     */
    public OKXTradingService(ConfigLoader config, MarginSupportCache marginCache) {
        this.apiKey = config.getString("okx.api.key", "");
        this.secretKey = config.getString("okx.api.secret", "");
        this.passphrase = config.getString("okx.api.passphrase", "");
        this.isSimulated = config.getBoolean("okx.api.simulated", false);
        this.marginCache = marginCache;
        
        // 根据是否模拟交易选择 URL
        if (isSimulated) {
            this.baseUrl = "https://www.okx.com";  // 模拟交易
            logger.info("✓ OKX 交易服务初始化（模拟交易模式）");
        } else {
            this.baseUrl = "https://www.okx.com";  // 实盘交易
            logger.warn("⚠ OKX 交易服务初始化（实盘交易模式）");
        }
        
        // 检查 API 配置
        if (!isConfigured()) {
            logger.error("✗ OKX API 未配置! 请设置环境变量: OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE");
            logger.error("✗ 当前配置: apiKey={}, secretKey={}, passphrase={}", 
                apiKey.isEmpty() ? "未设置" : "已设置", 
                secretKey.isEmpty() ? "未设置" : "已设置",
                passphrase.isEmpty() ? "未设置" : "已设置");
        } else {
            logger.info("✓ OKX API 配置已加载");
        }
        
        // 初始化 Redis 连接管理器
        try {
            this.redisManager = new RedisConnectionManager(config);
            logger.info("✓ Redis 连接管理器初始化成功,用于缓存交易对信息");
        } catch (Exception e) {
            logger.error("✗ Redis 连接管理器初始化失败: {}", e.getMessage(), e);
            this.redisManager = null;
        }
        
        // 如果提供了杠杆缓存，输出日志
        if (marginCache != null) {
            logger.info("✓ 杠杆支持信息缓存已注入，将使用内存缓存查询");
        }
        
        initHttpClient();
    }
    
    /**
     * 初始化 HTTP 客户端
     */
    private void initHttpClient() {
        if (httpClient == null) {
            OkHttpClient.Builder builder = new OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS);
            
            // 配置代理（如果启用）
            ConfigLoader config = ConfigLoader.getInstance();
            boolean proxyEnabled = config.getBoolean("okx.proxy.enabled", false);
            if (proxyEnabled) {
                String proxyHost = config.getString("okx.proxy.host", "localhost");
                int proxyPort = config.getInt("okx.proxy.port", 10809);
                
                java.net.Proxy proxy = new java.net.Proxy(
                    java.net.Proxy.Type.HTTP,
                    new java.net.InetSocketAddress(proxyHost, proxyPort)
                );
                builder.proxy(proxy);
                
                logger.info("✓ HTTP 客户端代理已启用: {}:{}", proxyHost, proxyPort);
            }
            
            httpClient = builder.build();
        }
    }
    
    /**
     * 做多现货（买入）
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#order-book-trading-trade-post-place-order
     * 
     * 请求示例:
     * POST /api/v5/trade/order
     * {
     *   "instId": "BTC-USDT",
     *   "tdMode": "cash",
     *   "side": "buy",
     *   "ordType": "market",
     *   "sz": "0.001",
     *   "tgtCcy": "base_ccy"
     * }
     * 
     * 成功响应示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 BTC (方法内部会拼接为 BTC-USDT)
     * @param size 交易数量（币的数量,如 0.001 BTC）
     * @param price 当前价格(用于检查订单金额是否满足最小要求)
     * @return 订单ID
     */
    public String buySpot(String symbol, BigDecimal size, BigDecimal price) {
        try {
            // 拼接完整交易对: BTC → BTC-USDT
            String instId = symbol + "-USDT";
            logger.info("📈 做多现货（买入）: {} | 数量: {} {}", instId, size, symbol);
            
            // 检查订单金额是否满足最小要求,如果不满足则调整为最小金额
            // 最小订单金额 = max(OKX最小限额10 USDT, 配置的交易金额)
            BigDecimal orderValue = size.multiply(price);
            BigDecimal okxMinAmount = new BigDecimal("10");  // OKX 最小 10 USDT
            
            // 从配置读取交易金额(如果有的话)
            ConfigLoader config = ConfigLoader.getInstance();
            BigDecimal configTradeAmount = new BigDecimal(
                config.getString("arbitrage.trading.trade-amount-usdt", "10")
            );
            
            // 取两者中的较大值作为最小订单金额
            BigDecimal minOrderAmount = okxMinAmount.max(configTradeAmount);
            
            // 如果订单金额小于最小要求,调整数量以满足最小金额
            if (orderValue.compareTo(minOrderAmount) < 0) {
                logger.warn("⚠ 订单金额 {} USDT < 最小要求 {} USDT,自动调整数量", 
                    orderValue.setScale(2, RoundingMode.HALF_UP), minOrderAmount);
                logger.info("  └─ 原始: {} {} × {} USDT = {} USDT", 
                    size, symbol, price, orderValue.setScale(2, RoundingMode.HALF_UP));
                
                // 重新计算数量: 数量 = 最小金额 / 价格
                size = minOrderAmount.divide(price, 8, RoundingMode.UP);
                orderValue = size.multiply(price);
                
                logger.info("  └─ 调整: {} {} × {} USDT = {} USDT", 
                    size, symbol, price, orderValue.setScale(2, RoundingMode.HALF_UP));
                logger.info("  └─ OKX最小限额: {} USDT | 配置交易金额: {} USDT", 
                    okxMinAmount, configTradeAmount);
            }
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", instId);
            orderRequest.put("tdMode", "cross");  // 全仓杠杆模式（支持借币做空）
            orderRequest.put("ccy", "USDT");  // 保证金币种(杠杆模式必需)
            orderRequest.put("side", "buy");  // 买入
            orderRequest.put("ordType", "market");  // 市价单
            // 注意: 现货杠杆模式不支持 tgtCcy 参数,直接使用 sz 表示币数量
            // 使用 toPlainString() 避免科学计数法(如 1E-5),OKX API 不接受科学计数法
            orderRequest.put("sz", size.toPlainString());  // 币的数量(避免科学计数法)
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做多现货失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 做空现货（卖出）
     * 
     * 注意: 现货卖出需要先持有对应的币种
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#order-book-trading-trade-post-place-order
     * 
     * 请求示例:
     * POST /api/v5/trade/order
     * {
     *   "instId": "BTC-USDT",
     *   "tdMode": "cash",
     *   "side": "sell",
     *   "ordType": "market",
     *   "sz": "0.001",
     *   "tgtCcy": "base_ccy"
     * }
     * 
     * 成功响应示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 BTC (方法内部会拼接为 BTC-USDT)
     * @param size 交易数量（币的数量,如 0.001 BTC）
     * @return 订单ID
     */
    /**
     * 卖出现货（支持杠杆模式）
     * 
     * 注意:
     * - 使用杠杆模式(tdMode: "cross" 或 "isolated")可以借币卖出
     * - 如果提供了 marginCache，会从内存缓存查询杠杆支持信息，否则调用 API
     * - 借币利息按小时计算,需要考虑持仓时间
     * - 查询到的利息率会保存到 lastBorrowInterestRate 字段,可通过 getLastBorrowInterestRate() 获取
     * 
     * @param symbol 币种名称,如 BTC
     * @param size 卖出数量
     * @param price 当前价格(用于检查订单金额是否满足最小要求)
     * @param leverage 杠杆倍数
     * @return 订单ID
     */
    public String sellSpot(String symbol, BigDecimal size, BigDecimal price, int leverage) {
        try {
            // 拼接完整交易对: BTC → BTC-USDT
            String instId = symbol + "-USDT";
            logger.info("📉 做空现货（卖出）: {} | 数量: {} {} | 杠杆: {}x", instId, size, symbol, leverage);
            
            // 检查是否支持杠杆交易（优先使用内存缓存）
            boolean supportsMargin = false;
            if (marginCache != null) {
                // 从内存缓存查询
                supportsMargin = marginCache.supportsMargin(instId);
                logger.debug("从内存缓存查询杠杆支持: {} -> {}", instId, supportsMargin);
            }
            
            // 如果不支持杠杆，直接返回 null（不下单）
            if (!supportsMargin) {
                logger.warn("⚠ {} 不支持杠杆交易，取消下单", instId);
                return null;
            }
            
            // 支持杠杆，设置杠杆倍数
            setMarginLeverage(instId, leverage);
            logger.info("✓ {} 支持杠杆交易，已设置杠杆倍数: {}x", instId, leverage);
            
            // 检查订单金额是否满足最小要求,如果不满足则调整为最小金额
            // 最小订单金额 = max(OKX最小限额10 USDT, 配置的交易金额)
            BigDecimal orderValue = size.multiply(price);
            
            // 从配置读取交易金额(如果有的话)
            ConfigLoader config = ConfigLoader.getInstance();
            BigDecimal configTradeAmount = new BigDecimal(
                config.getString("arbitrage.trading.trade-amount-usdt")
            );
            
            // 取两者中的较大值作为最小订单金额
            BigDecimal minOrderAmount = orderValue.max(configTradeAmount);
            
            // 如果订单金额小于最小要求,调整数量以满足最小金额
            if (orderValue.compareTo(minOrderAmount) < 0) {
                logger.warn("⚠ 订单金额 {} USDT < 最小要求 {} USDT,自动调整数量", 
                    orderValue.setScale(2, RoundingMode.HALF_UP), minOrderAmount);
                logger.info("  └─ 原始: {} {} × {} USDT = {} USDT", 
                    size, symbol, price, orderValue.setScale(2, RoundingMode.HALF_UP));
                
                // 重新计算数量: 数量 = 最小金额 / 价格
                size = minOrderAmount.divide(price, 8, RoundingMode.UP);
                orderValue = size.multiply(price);
                
                logger.info("  └─ 调整: {} {} × {} USDT = {} USDT", 
                    size, symbol, price, orderValue.setScale(2, RoundingMode.HALF_UP));
                logger.info("  └─ OKX最小限额: {} USDT ", configTradeAmount);
            }
            
            // 从 Redis 查询借币利息率
            if (marginCache != null && marginCache.getRedisManager() != null) {
                try (redis.clients.jedis.Jedis jedis = marginCache.getRedisManager().getConnection()) {
                    String interestRateStr = jedis.hget("okx:borrow:interest", symbol);
                    if (interestRateStr != null) {
                        lastBorrowInterestRate = new BigDecimal(interestRateStr);
                        logger.debug("从 Redis 查询借币利息率: {} -> {} (小时利率)", symbol, lastBorrowInterestRate);
                    } else {
                        logger.warn("⚠ Redis 中未找到 {} 的借币利息率", symbol);
                        lastBorrowInterestRate = null;
                    }
                } catch (Exception e) {
                    logger.warn("⚠ 从 Redis 查询借币利息率失败: {}", e.getMessage());
                    lastBorrowInterestRate = null;
                }
            } else {
                logger.warn("⚠ 未提供 Redis 连接,无法查询借币利息率");
                lastBorrowInterestRate = null;
            }
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", instId);
            orderRequest.put("tdMode", "cross");  // 全仓杠杆模式（支持借币做空）
            orderRequest.put("ccy", "USDT");  // 保证金币种(杠杆模式必需)
            orderRequest.put("side", "sell");  // 卖出
            orderRequest.put("ordType", "market");  // 市价单
            // 注意: 现货杠杆模式不支持 tgtCcy 参数,直接使用 sz 表示币数量
            // 使用 toPlainString() 避免科学计数法(如 1E-5),OKX API 不接受科学计数法
            orderRequest.put("sz", size.toPlainString());  // 币的数量(避免科学计数法)
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做空现货失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 获取最近查询的借币利息率
     * 
     * 注意: 此方法返回最近一次调用 sellSpot() 时查询到的借币利息率
     * 
     * @return 小时利率,如果未查询过或查询失败返回 null
     */
    public BigDecimal getLastBorrowInterestRate() {
        return lastBorrowInterestRate;
    }
    
    /**
     * 获取最近的错误信息
     * 
     * 注意: 此方法返回最近一次下单失败时的详细错误信息(sMsg字段)
     * 
     * @return 错误信息,如果没有错误返回 null
     */
    public String getLastErrorMessage() {
        return lastErrorMessage;
    }
    
    /**
     * 做多合约（开多仓）
     * 
     * 注意: 
     * - 合约交易需要设置 posSide 参数
     * - 合约不支持 tgtCcy 参数,sz 表示张数
     * - USDT 合约: 1 张 = 1 个币(如 1 BTC, 1 ETH)
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#order-book-trading-trade-post-place-order
     * 
     * 请求示例:
     * POST /api/v5/trade/order
     * {
     *   "instId": "BTC-USDT-SWAP",
     *   "tdMode": "cross",
     *   "side": "buy",
     *   "posSide": "long",
     *   "ordType": "market",
     *   "sz": "0.001"
     * }
     * 
     * 成功响应示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 BTC (方法内部会拼接为 BTC-USDT-SWAP)
     * @param size 交易数量（币的数量,如 0.001 BTC,会自动转换为张数）
     * @param leverage 杠杆倍数
     * @return 订单ID
     */
    public String longSwap(String symbol, BigDecimal size, int leverage) {
        try {
            // 拼接完整合约交易对: BTC → BTC-USDT-SWAP
            String instId = symbol + "-USDT-SWAP";
            logger.info("📈 做多合约: {} | 数量: {} {} | 杠杆: {}x", instId, size, symbol, leverage);
            
            // 先设置杠杆
            setLeverage(instId, leverage);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", instId);
            orderRequest.put("tdMode", "cross");  // 全仓模式
            orderRequest.put("side", "buy");  // 买入开多
            orderRequest.put("posSide", "long");  // 持仓方向：多头（必填）
            orderRequest.put("ordType", "market");  // 市价单
            orderRequest.put("sz", size.toString());  // 张数（USDT合约: 1张=1币）
            // 注意: 合约不支持 tgtCcy 参数
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做多合约失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 做空合约（开空仓）
     * 
     * 注意: 
     * - 合约交易需要设置 posSide 参数
     * - 合约不支持 tgtCcy 参数,sz 表示张数
     * - USDT 合约: 1 张 = 1 个币(如 1 BTC, 1 ETH)
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#order-book-trading-trade-post-place-order
     * 
     * 请求示例:
     * POST /api/v5/trade/order
     * {
     *   "instId": "BTC-USDT-SWAP",
     *   "tdMode": "cross",
     *   "side": "sell",
     *   "posSide": "short",
     *   "ordType": "market",
     *   "sz": "0.001"
     * }
     * 
     * 成功响应示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 BTC (方法内部会拼接为 BTC-USDT-SWAP)
     * @param size 交易数量（币的数量,如 0.001 BTC）
     * @param leverage 杠杆倍数
     * @return 订单ID
     */
    public String shortSwap(String symbol, BigDecimal size, int leverage) {
        try {
            // 拼接完整合约交易对: BTC → BTC-USDT-SWAP
            String instId = symbol + "-USDT-SWAP";
            logger.info("📉 做空合约: {} | 数量: {} {} | 杠杆: {}x", instId, size, symbol, leverage);
            
            // 先设置杠杆
            setLeverage(instId, leverage);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", instId);
            orderRequest.put("tdMode", "cross");  // 全仓模式
            orderRequest.put("side", "sell");  // 卖出开空
            orderRequest.put("posSide", "short");  // 持仓方向：空头（必填）
            orderRequest.put("ordType", "market");  // 市价单
            orderRequest.put("sz", size.toString());  // 张数（USDT合约: 1张=1币）
            // 注意: 合约不支持 tgtCcy 参数
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("做空合约失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 平多仓
     * 
     * 注意: 合约不支持 tgtCcy 参数,sz 表示张数
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#order-book-trading-trade-post-place-order
     * 
     * 请求示例:
     * POST /api/v5/trade/order
     * {
     *   "instId": "BTC-USDT-SWAP",
     *   "tdMode": "cross",
     *   "side": "sell",
     *   "posSide": "long",
     *   "ordType": "market",
     *   "sz": "0.001"
     * }
     * 
     * 成功响应示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 BTC (方法内部会拼接为 BTC-USDT-SWAP)
     * @param size 交易数量（币的数量,如 0.001 BTC）
     * @return 订单ID
     */
    public String closeLongSwap(String symbol, BigDecimal size) {
        try {
            // 拼接完整合约交易对: BTC → BTC-USDT-SWAP
            String instId = symbol + "-USDT-SWAP";
            logger.info("🔄 平多仓: {} | 数量: {} {}", instId, size, symbol);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", instId);
            orderRequest.put("tdMode", "cross");  // 全仓模式
            orderRequest.put("side", "sell");  // 卖出平多
            orderRequest.put("posSide", "long");  // 持仓方向：多头（必填）
            orderRequest.put("ordType", "market");  // 市价单
            orderRequest.put("sz", size.toString());  // 张数（USDT合约: 1张=1币）
            // 注意: 合约不支持 tgtCcy 参数
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("平多仓失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 平空仓
     * 
     * 注意: 合约不支持 tgtCcy 参数,sz 表示张数
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#order-book-trading-trade-post-place-order
     * 
     * 请求示例:
     * POST /api/v5/trade/order
     * {
     *   "instId": "BTC-USDT-SWAP",
     *   "tdMode": "cross",
     *   "side": "buy",
     *   "posSide": "short",
     *   "ordType": "market",
     *   "sz": "0.001"
     * }
     * 
     * 成功响应示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 BTC (方法内部会拼接为 BTC-USDT-SWAP)
     * @param size 交易数量（币的数量,如 0.001 BTC）
     * @return 订单ID
     */
    public String closeShortSwap(String symbol, BigDecimal size) {
        try {
            // 拼接完整合约交易对: BTC → BTC-USDT-SWAP
            String instId = symbol + "-USDT-SWAP";
            logger.info("🔄 平空仓: {} | 数量: {} {}", instId, size, symbol);
            
            ObjectNode orderRequest = OBJECT_MAPPER.createObjectNode();
            orderRequest.put("instId", instId);
            orderRequest.put("tdMode", "cross");  // 全仓模式
            orderRequest.put("side", "buy");  // 买入平空
            orderRequest.put("posSide", "short");  // 持仓方向：空头（必填）
            orderRequest.put("ordType", "market");  // 市价单
            orderRequest.put("sz", size.toString());  // 张数（USDT合约: 1张=1币）
            // 注意: 合约不支持 tgtCcy 参数
            
            return placeOrder(orderRequest);
            
        } catch (Exception e) {
            logger.error("平空仓失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 设置杠杆倍数（合约）
     */
    private void setLeverage(String instId, int leverage) {
        try {
            // 生成 OKX API 要求的时间戳格式: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
            String timestamp = getIsoTimestamp();
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
                    logger.debug("设置合约杠杆成功: {} = {}x", instId, leverage);
                }
            }
            
        } catch (Exception e) {
            logger.warn("设置合约杠杆失败: {}", e.getMessage());
        }
    }
    
    /**
     * 设置现货杠杆倍数
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#trading-account-rest-api-set-leverage
     * 
     * 注意: 现货杠杆和合约杠杆使用不同的 API
     */
    private void setMarginLeverage(String instId, int leverage) {
        try {
            // 生成 OKX API 要求的时间戳格式: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
            String timestamp = getIsoTimestamp();
            String method = "POST";
            String requestPath = "/api/v5/account/set-leverage";
            
            ObjectNode body = OBJECT_MAPPER.createObjectNode();
            body.put("instId", instId);
            body.put("lever", String.valueOf(leverage));
            body.put("mgnMode", "cross");  // 全仓模式
            
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
                String responseBody = response.body() != null ? response.body().string() : "";
                
                if (response.isSuccessful() && !responseBody.isEmpty()) {
                    JsonNode jsonResponse = OBJECT_MAPPER.readTree(responseBody);
                    
                    if ("0".equals(jsonResponse.get("code").asText())) {
                        logger.info("✓ 设置现货杠杆成功: {} = {}x", instId, leverage);
                    } else {
                        String errorMsg = jsonResponse.get("msg").asText();
                        logger.warn("⚠ 设置现货杠杆失败: {} - {}", instId, errorMsg);
                    }
                } else {
                    logger.warn("⚠ 设置现货杠杆请求失败: HTTP {}", response.code());
                }
            }
            
        } catch (Exception e) {
            logger.warn("设置现货杠杆异常: {}", e.getMessage());
        }
    }
    
    /**
     * 下单
     * 
     * @param orderRequest 订单请求参数
     * @return 订单ID,失败返回 null
     * 
     * @apiNote 成功响应示例:
     * <pre>
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ordId": "123456789",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "0",
     *     "sMsg": ""
     *   }]
     * }
     * </pre>
     * 
     * @apiNote 失败响应示例:
     * <pre>
     * {
     *   "code": "1",
     *   "msg": "Operation failed",
     *   "data": [{
     *     "ordId": "",
     *     "clOrdId": "",
     *     "tag": "",
     *     "sCode": "51000",
     *     "sMsg": "Parameter instId  error"
     *   }]
     * }
     * </pre>
     */
    private String placeOrder(ObjectNode orderRequest) throws Exception {
        initHttpClient();
        
        // 生成 OKX API 要求的时间戳格式: yyyy-MM-dd'T'HH:mm:ss.SSS'Z'
        String timestamp = getIsoTimestamp();
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
            String responseBody = response.body() != null ? response.body().string() : "";
            
            // 打印完整的请求信息
            logger.info("========== OKX API 请求 ==========");
            logger.info("URL: {}", baseUrl + requestPath);
            logger.info("Method: {}", method);
            logger.info("Headers:");
            logger.info("  OK-ACCESS-KEY: {}", apiKey.substring(0, Math.min(8, apiKey.length())) + "***");
            logger.info("  OK-ACCESS-TIMESTAMP: {}", timestamp);
            logger.info("  Content-Type: application/json");
            logger.info("Request Body:");
            logger.info("{}", bodyStr);
            
            // 打印完整的响应信息
            logger.info("========== OKX API 响应 ==========");
            logger.info("HTTP Status: {}", response.code());
            logger.info("Response Body:");
            logger.info("{}", responseBody);
            logger.info("===================================");
            
            if (response.isSuccessful() && !responseBody.isEmpty()) {
                JsonNode jsonResponse = OBJECT_MAPPER.readTree(responseBody);
                
                if ("0".equals(jsonResponse.get("code").asText())) {
                    String orderId = jsonResponse.get("data").get(0).get("ordId").asText();
                    logger.info("✓ 下单成功: orderId={}", orderId);
                    return orderId;
                } else {
                    // 解析错误信息
                    String errorCode = jsonResponse.get("code").asText();
                    String errorMsg = jsonResponse.get("msg").asText();
                    
                    // 检查 data 数组中的详细错误
                    if (jsonResponse.has("data") && jsonResponse.get("data").isArray() 
                        && jsonResponse.get("data").size() > 0) {
                        JsonNode dataNode = jsonResponse.get("data").get(0);
                        String sCode = dataNode.get("sCode").asText();
                        String sMsg = dataNode.get("sMsg").asText();
                        
                        // 保存错误信息供外部获取
                        lastErrorMessage = sMsg;
                        
                        // 格式化错误信息,让错误原因更清晰
                        logger.error("✗ 下单失败!");
                        logger.error("  └─ 错误代码: {} (详细代码: {})", errorCode, sCode);
                        logger.error("  └─ 错误原因: {}", translateErrorMessage(sCode, sMsg));
                        logger.error("  └─ 原始消息: {} | {}", errorMsg, sMsg);
                        // 错误信息已通过 logOrderDetail 记录到 order_detail.csv
                    } else {
                        // 保存错误信息供外部获取
                        lastErrorMessage = errorMsg;
                        
                        logger.error("✗ 下单失败!");
                        logger.error("  └─ 错误代码: {}", errorCode);
                        logger.error("  └─ 错误原因: {}", errorMsg);
                        // 错误信息已通过 logOrderDetail 记录到 order_detail.csv
                    }
                    return null;
                }
            } else {
                logger.error("✗ 下单请求失败!");
                logger.error("  └─ HTTP 状态码: {}", response.code());
                logger.error("  └─ 响应内容: {}", responseBody.isEmpty() ? "空响应" : responseBody);
                // 错误信息已通过 logOrderDetail 记录到 order_detail.csv
                return null;
            }
        }
    }
    
    /**
     * 获取 OKX API 要求的 ISO 时间戳
     * 
     * @return ISO 8601 格式的时间戳,例如: 2023-01-09T08:15:39.924Z
     */
    private String getIsoTimestamp() {
        // OKX API 要求的时间戳格式为:yyyy-MM-dd'T'HH:mm:ss.SSSZ
        // 使用 DateTimeFormatter 确保生成精确的毫秒格式
        return DateTimeFormatter
                .ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
                .withZone(ZoneOffset.UTC)
                .format(Instant.now());
    }
    
    /**
     * 翻译 OKX 错误信息为中文说明
     * 
     * @param errorCode OKX 错误代码
     * @param originalMsg 原始错误消息
     * @return 中文错误说明
     */
    private String translateErrorMessage(String errorCode, String originalMsg) {
        switch (errorCode) {
            case "51121":
                return "订单数量不符合最小交易单位 - 数量必须是 lot size 的整数倍,请调整交易数量";
            case "51001":
                return "交易对不存在 - 该币种在 OKX 上不存在或交易对名称错误,请检查交易对是否正确";
            case "51008":
                return "账户余额不足 - 请检查账户中的 USDT 余额是否充足";
            case "51169":
                return "没有可平仓的持仓 - 该方向没有持仓或持仓已被平掉";
            case "59110":
                return "合约不支持 tgtCcy 参数 - 合约交易的 sz 参数表示张数,不是金额";
            case "51000":
                return "参数错误 - 请检查交易对、金额等参数是否正确";
            case "50112":
                return "时间戳格式错误 - API 签名时间戳格式不正确";
            case "50113":
                return "请求过期 - 请求时间戳与服务器时间相差超过30秒";
            case "50111":
                return "签名验证失败 - API Key、Secret 或 Passphrase 不正确";
            case "51020":
                return "订单金额太小 - 订单金额低于最小限额";
            case "51021":
                return "订单金额太大 - 订单金额超过最大限额";
            case "51119":
                return "仓位模式错误 - 请检查是否设置了正确的持仓模式";
            case "51120":
                return "杠杆倍数错误 - 杠杆倍数超出允许范围";
            case "51024":
                return "交易对已暂停交易 - 该交易对当前不可交易";
            case "51025":
                return "交易对不支持该交易模式 - 请检查交易模式是否正确";
            default:
                return originalMsg;  // 未知错误,返回原始消息
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



    /**
     * 获取交易对信息（合约面值、最小交易单位等）
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#public-data-rest-api-get-instruments
     * 
     * 请求示例:
     * GET /api/v5/public/instruments?instType=SWAP&instId=COMP-USDT-SWAP
     * 
     * 响应示例:
     * {
     *   "code": "0",
     *   "data": [{
     *     "instId": "COMP-USDT-SWAP",
     *     "ctVal": "0.1",      // 合约面值: 1 张 = 0.1 个 COMP
     *     "lotSz": "1",        // 最小交易单位: 1 张
     *     "minSz": "1"         // 最小下单数量: 1 张
     *   }]
     * }
     * 
     * @param symbol 币种名称,如 COMP (方法内部会拼接为 COMP-USDT-SWAP)
     * @return 交易对信息,失败返回 null
     */
    public InstrumentInfo getInstrumentInfo(String symbol) {
        try {
            String instId = symbol + "-USDT-SWAP";
            
            // 1. 先从 Redis 缓存中查找
            if (redisManager != null) {
                String cachedJson = redisManager.hget(INSTRUMENT_CACHE_KEY, instId);
                if (cachedJson != null && !cachedJson.isEmpty()) {
                    try {
                        InstrumentInfo info = OBJECT_MAPPER.readValue(cachedJson, InstrumentInfo.class);
                        logger.debug("✓ 从 Redis 缓存获取交易对信息: {}", info);
                        return info;
                    } catch (Exception e) {
                        logger.warn("解析 Redis 缓存失败,将重新查询: {}", e.getMessage());
                    }
                }
            }
            
            // 2. 缓存中没有,调用 API 查询
            initHttpClient();
            
            String url = baseUrl + "/api/v5/public/instruments?instType=SWAP&instId=" + instId;
            Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                if (response.isSuccessful() && response.body() != null) {
                    String responseBody = response.body().string();
                    JsonNode jsonResponse = OBJECT_MAPPER.readTree(responseBody);
                    
                    if ("0".equals(jsonResponse.get("code").asText())) {
                        JsonNode data = jsonResponse.get("data").get(0);
                        
                        BigDecimal ctVal = new BigDecimal(data.get("ctVal").asText());
                        BigDecimal lotSz = new BigDecimal(data.get("lotSz").asText());
                        BigDecimal minSz = new BigDecimal(data.get("minSz").asText());
                        
                        InstrumentInfo info = new InstrumentInfo(instId, ctVal, lotSz, minSz);
                        
                        // 3. 缓存结果到 Redis
                        if (redisManager != null) {
                            try {
                                String infoJson = OBJECT_MAPPER.writeValueAsString(info);
                                redisManager.hset(INSTRUMENT_CACHE_KEY, instId, infoJson);
                                logger.info("✓ 获取交易对信息并缓存到 Redis: {}", info);
                            } catch (Exception e) {
                                logger.warn("缓存交易对信息到 Redis 失败: {}", e.getMessage());
                                logger.info("✓ 获取交易对信息: {}", info);
                            }
                        } else {
                            logger.info("✓ 获取交易对信息 (Redis 未连接): {}", info);
                        }
                        
                        return info;
                    } else {
                        logger.error("✗ 获取交易对信息失败: {}", responseBody);
                        return null;
                    }
                } else {
                    logger.error("✗ 获取交易对信息请求失败: HTTP {}", response.code());
                    return null;
                }
            }
            
        } catch (Exception e) {
            logger.error("获取交易对信息异常: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 根据 USDT 金额和价格计算现货数量
     * 
     * 计算公式:
     * 币数量 = USDT金额 / 价格
     * 
     * 示例:
     * - BTC 价格 50000 USDT, 交易金额 100 USDT
     * - 币数量 = 100 / 50000 = 0.002 BTC
     * 
     * @param usdtAmount USDT 金额
     * @param price 当前价格
     * @return 币的数量
     */
    /**
     * 计算交易数量（现货和合约统一使用币的数量）
     * 
     * 计算公式: 币数量 = USDT金额 / 价格（向上取整）
     * 
     * 示例:
     * - BTC 价格 50000 USDT, 交易金额 100 USDT
     * - 币数量 = 100 / 50000 = 0.002 BTC (向上取整)
     * 
     * @param usdtAmount USDT 金额
     * @param price 当前价格
     * @return 币数量（现货和合约都使用这个数量）
     */
    public BigDecimal calculateSpotSize(BigDecimal usdtAmount, BigDecimal price) {
        try {
            // 计算币数量（向上取整，确保订单金额不会因为精度问题低于最小金额）
            BigDecimal coinAmount = usdtAmount.divide(price, 8, java.math.RoundingMode.UP);
            
            logger.info("  └─ 计算交易数量:");
            logger.info("     - 交易金额: {} USDT", usdtAmount);
            logger.info("     - 当前价格: {} USDT", price);
            logger.info("     - 币数量: {} (现货和合约统一使用)", coinAmount);
            
            return coinAmount;
            
        } catch (Exception e) {
            logger.error("计算交易数量失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 根据 USDT 金额和价格计算合约张数
     * 
     * 计算公式:
     * 1. 计算需要的币数量: 币数量 = USDT金额 / 价格
     * 2. 计算需要的张数: 张数 = 币数量 / ctVal
     * 3. 按 lotSz 向下取整: 最终张数 = floor(张数 / lotSz) * lotSz
     * 
     * 示例:
     * - COMP 价格 20 USDT, 交易金额 100 USDT
     * - ctVal = 0.1 (1 张 = 0.1 COMP), lotSz = 1
     * - 币数量 = 100 / 20 = 5 COMP
     * - 张数 = 5 / 0.1 = 50 张
     * - 最终张数 = floor(50 / 1) * 1 = 50 张
     * 
     * @param symbol 币种名称,如 COMP
     * @param usdtAmount USDT 金额
     * @param price 当前价格
     * @return 合约张数,如果无法计算返回 null
     */
    public BigDecimal calculateContractSize(String symbol, BigDecimal usdtAmount, BigDecimal price) {
        return calculateContractSize(symbol, usdtAmount, price, null);
    }
    
    /**
     * 根据 USDT 金额和价格计算合约张数（支持从 Redis 缓存读取）
     * 
     * @param symbol 币种名称,如 COMP
     * @param usdtAmount USDT 金额
     * @param price 当前价格
     * @param redisManager Redis 连接管理器（可选，如果提供则优先从缓存读取）
     * @return 合约张数,如果无法计算返回 null
     */
    public BigDecimal calculateContractSize(String symbol, BigDecimal usdtAmount, BigDecimal price,
            com.crypto.dw.redis.RedisConnectionManager redisManager) {
        try {
            // 优先从 Redis 缓存读取交易对信息
            InstrumentInfo info = null;
            if (redisManager != null) {
                try {
                    String json = redisManager.hget("okx:instrument:cache", symbol);
                    if (json != null && !json.isEmpty()) {
                        ObjectMapper mapper = new ObjectMapper();
                        info = mapper.readValue(json, InstrumentInfo.class);
                        logger.debug("从缓存读取交易对信息: {} -> minSz={}, ctVal={}, lotSz={}", 
                            symbol, info.minSz, info.ctVal, info.lotSz);
                    }
                } catch (Exception e) {
                    logger.warn("从缓存读取交易对信息失败: {}, 将调用 API 查询", e.getMessage());
                }
            }
            
            // 如果缓存中没有,调用 API 查询（兜底方案）
            if (info == null) {
                logger.debug("缓存中没有 {} 的交易对信息,调用 API 查询", symbol);
                info = getInstrumentInfo(symbol);
                if (info == null) {
                    logger.error("✗ 无法获取 {} 的交易对信息", symbol);
                    return null;
                }
            }
            
            // 1. 计算需要的币数量
            BigDecimal coinAmount = usdtAmount.divide(price, 8, java.math.RoundingMode.DOWN);
            
            // 2. 计算需要的张数
            BigDecimal rawSize = coinAmount.divide(info.ctVal, 8, java.math.RoundingMode.DOWN);
            
            // 3. 按 lotSz 向下取整
            BigDecimal lots = rawSize.divide(info.lotSz, 0, java.math.RoundingMode.DOWN);
            BigDecimal finalSize = lots.multiply(info.lotSz);
            
            logger.info("  └─ 计算合约张数:");
            logger.info("     - 交易金额: {} USDT", usdtAmount);
            logger.info("     - 当前价格: {} USDT", price);
            logger.info("     - 币数量: {} {}", coinAmount, symbol);
            logger.info("     - 合约面值: {} {} / 张", info.ctVal, symbol);
            logger.info("     - 原始张数: {}", rawSize);
            logger.info("     - 最小单位: {} 张", info.lotSz);
            logger.info("     - 最终张数: {} 张", finalSize);
            
            // 检查是否小于最小下单数量
            if (finalSize.compareTo(info.minSz) < 0) {
                logger.warn("⚠ 计算的张数 {} 小于最小下单数量 {},建议增加交易金额", finalSize, info.minSz);
                return BigDecimal.ZERO;
            }
            
            return finalSize;
            
        } catch (Exception e) {
            logger.error("计算合约张数失败: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 查询借币利息率
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#trading-account-rest-api-get-interest-rate-and-loan-quota
     * 
     * 接口: GET /api/v5/account/interest-rate
     * 参数: ccy - 币种,如 BTC
     * 
     * 返回示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ccy": "BTC",
     *     "interestRate": "0.0000040833333334"  // 小时利率
     *   }]
     * }
     * 
     * @param ccy 币种,如 BTC
     * @return 小时利率,如果查询失败返回 null
     */
    
    /**
     * 查询币种是否支持杠杆交易
     * 
     * @param symbol 币种符号（如 BTC）
     * @return true=支持杠杆，false=不支持杠杆
     */
    public boolean checkMarginSupport(String symbol) {
        try {
            initHttpClient();
            
            // 查询现货杠杆交易对信息
            // API: GET /api/v5/public/instruments?instType=MARGIN&instId=BTC-USDT
            // 注意：symbol 可能已经包含 -USDT 后缀，需要处理
            String instId = symbol.contains("-") ? symbol : (symbol + "-USDT");
            String url = baseUrl + "/api/v5/public/instruments?instType=MARGIN&instId=" + instId;
            
            Request request = new Request.Builder()
                .url(url)
                .get()
                .build();
            
            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                
                if (response.isSuccessful() && !responseBody.isEmpty()) {
                    JsonNode jsonResponse = OBJECT_MAPPER.readTree(responseBody);
                    
                    if ("0".equals(jsonResponse.get("code").asText())) {
                        JsonNode dataArray = jsonResponse.get("data");
                        
                        // 如果 data 数组不为空，说明支持杠杆交易
                        if (dataArray != null && dataArray.isArray() && dataArray.size() > 0) {
                            logger.info("✓ {} 支持杠杆交易", instId);
                            return true;
                        } else {
                            logger.info("✗ {} 不支持杠杆交易", instId);
                            return false;
                        }
                    } else {
                        String errorMsg = jsonResponse.get("msg").asText();
                        logger.warn("查询杠杆支持失败: {}", errorMsg);
                        // 如果是 Instrument ID doesn't exist，说明不支持杠杆，返回 false
                        return false;
                    }
                } else {
                    logger.error("查询杠杆支持请求失败: HTTP {}", response.code());
                    return false;
                }
            }
            
        } catch (Exception e) {
            logger.error("查询杠杆支持异常: {}", e.getMessage(), e);
            return false;
        }
    }
    
    /**
     * 查询借币利息率（公开方法，供外部调用）
     * 
     * OKX API 文档: https://www.okx.com/docs-v5/en/#trading-account-rest-api-get-interest-rate-and-loan-quota
     * 
     * 接口: GET /api/v5/account/interest-rate
     * 参数: ccy - 币种,如 BTC
     * 
     * 返回示例:
     * {
     *   "code": "0",
     *   "msg": "",
     *   "data": [{
     *     "ccy": "BTC",
     *     "interestRate": "0.0000040833333334"  // 小时利率
     *   }]
     * }
     * 
     * @param ccy 币种,如 BTC
     * @return 小时利率,如果查询失败返回 null
     */
    public BigDecimal queryBorrowInterestRate(String ccy) {
        try {
            // 构建请求路径
            String requestPath = "/api/v5/account/interest-rate?ccy=" + ccy;
            String timestamp = getIsoTimestamp();
            String method = "GET";
            
            // 生成签名
            String signature = generateSignature(timestamp, method, requestPath, "");
            
            // 构建请求 (使用 OkHttp)
            Request request = new Request.Builder()
                    .url(baseUrl + requestPath)
                    .header("OK-ACCESS-KEY", apiKey)
                    .header("OK-ACCESS-SIGN", signature)
                    .header("OK-ACCESS-TIMESTAMP", timestamp)
                    .header("OK-ACCESS-PASSPHRASE", passphrase)
                    .header("Content-Type", "application/json")
                    .get()
                    .build();
            
            // 发送请求
            Response response = httpClient.newCall(request).execute();
            String responseBody = response.body().string();
            
            // 解析响应
            JsonNode root = OBJECT_MAPPER.readTree(responseBody);
            String code = root.path("code").asText();
            
            if (!"0".equals(code)) {
                String msg = root.path("msg").asText();
                logger.warn("查询借币利息失败: {} - {}", code, msg);
                return null;
            }
            
            // 提取利息率
            JsonNode data = root.path("data");
            if (data.isArray() && data.size() > 0) {
                String interestRateStr = data.get(0).path("interestRate").asText();
                BigDecimal interestRate = new BigDecimal(interestRateStr);
                logger.debug("查询到 {} 借币利息: {} (小时利率)", ccy, interestRate);
                return interestRate;
            }
            
            logger.warn("未查询到 {} 的借币利息数据", ccy);
            return null;
            
        } catch (Exception e) {
            logger.error("查询借币利息异常: {}", e.getMessage(), e);
            return null;
        }
    }
    
    /**
     * 计算借币利息成本
     * 
     * 计算公式: 利息 = 借币数量 * 小时利率 * 持仓小时数
     * 
     * 示例:
     * - 借币数量: 1 BTC
     * - 小时利率: 0.0000040833333334
     * - 持仓时间: 24 小时
     * - 利息 = 1 * 0.0000040833333334 * 24 = 0.000098 BTC
     * 
     * @param borrowAmount 借币数量
     * @param interestRate 小时利率
     * @param hours 持仓小时数
     * @return 利息成本（币的数量）
     */
    public BigDecimal calculateBorrowInterest(BigDecimal borrowAmount, BigDecimal interestRate, int hours) {
        if (borrowAmount == null || interestRate == null || hours <= 0) {
            return BigDecimal.ZERO;
        }
        
        // 利息 = 借币数量 * 小时利率 * 持仓小时数
        BigDecimal interest = borrowAmount
                .multiply(interestRate)
                .multiply(new BigDecimal(hours))
                .setScale(8, java.math.RoundingMode.HALF_UP);
        
        logger.debug("计算借币利息: 数量={}, 利率={}, 小时={}, 利息={}", 
                borrowAmount, interestRate, hours, interest);
        
        return interest;
    }
}
