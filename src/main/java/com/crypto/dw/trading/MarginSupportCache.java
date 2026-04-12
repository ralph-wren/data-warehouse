package com.crypto.dw.trading;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.redis.RedisConnectionManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 杠杆支持信息缓存
 * 
 * 功能：
 * 1. 启动时从 Redis 加载所有杠杆支持信息到内存
 * 2. 提供快速查询接口，避免频繁访问 Redis
 * 3. 支持定期刷新（可选）
 * 
 * Redis 数据结构：
 * - Key: "okx:margin:support"
 * - Type: Hash
 * - Field: symbol (如 "BTC-USDT")
 * - Value: leverage (杠杆倍数，如 "10" 或 "0" 表示不支持杠杆)
 * 
 * 策略说明：
 * - 策略A：现货卖出 + 合约买入（需要杠杆借币）
 * - 策略B：现货买入 + 合约卖出（不需要杠杆）
 * 
 * 性能优化：
 * - 内存缓存，查询时间 O(1)
 * - 减少 Redis 访问，降低网络延迟
 * - 线程安全，支持并发访问
 * 
 * 注意：
 * - Redis 中的数据由 FlinkTickerCollectorJob 在启动时初始化
 * - 该类只负责从 Redis 加载数据到内存，不负责查询 API
 */
public class MarginSupportCache {
    
    private static final Logger logger = LoggerFactory.getLogger(MarginSupportCache.class);
    
    private final RedisConnectionManager redisManager;
    private final Map<String, String> cache;
    
    /**
     * 构造函数
     * 
     * @param config 配置加载器
     */
    public MarginSupportCache(ConfigLoader config) {
        this.redisManager = new RedisConnectionManager(config);
        this.cache = new ConcurrentHashMap<>();
        
        // 启动时从 Redis 加载所有数据到内存
        loadAll();
    }
    
    /**
     * 从 Redis 加载所有杠杆支持信息到内存
     */
    public void loadAll() {
        try {
            Map<String, String> data = redisManager.hgetAll("okx:margin:support");
            
            if (data != null && !data.isEmpty()) {
                cache.clear();
                cache.putAll(data);
                logger.info("✓ 杠杆支持信息已加载到内存: {} 个币种", cache.size());
            } else {
                logger.warn("⚠ Redis 中没有杠杆支持信息");
            }
            
        } catch (Exception e) {
            logger.error("✗ 加载杠杆支持信息失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 检查是否支持策略A（现货卖出 + 合约买入，需要杠杆借币）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return true = 支持，false = 不支持
     */
    public boolean supportsStrategyA(String symbol) {
        String leverage = cache.get(symbol);
        if (leverage == null || leverage.isEmpty() || "0".equals(leverage)) {
            return false;
        }
        
        // 杠杆倍数 > 0 表示支持策略A
        try {
            int lev = Integer.parseInt(leverage);
            return lev > 0;
        } catch (NumberFormatException e) {
            logger.warn("⚠️ 无效的杠杆倍数: {} -> {}", symbol, leverage);
            return false;
        }
    }
    
    /**
     * 检查是否支持策略B（现货买入 + 合约卖出，不需要杠杆）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return true = 支持（所有币种都支持策略B）
     */
    public boolean supportsStrategyB(String symbol) {
        // 策略B不需要杠杆，所有币种都支持
        // 只要在缓存中存在，就支持策略B
        return cache.containsKey(symbol);
    }
    
    /**
     * 获取杠杆倍数
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return 杠杆倍数（如 10），如果不支持杠杆则返回 0
     */
    public int getLeverage(String symbol) {
        String leverage = cache.get(symbol);
        if (leverage == null || leverage.isEmpty()) {
            return 0;
        }
        
        try {
            return Integer.parseInt(leverage);
        } catch (NumberFormatException e) {
            logger.warn("⚠️ 无效的杠杆倍数: {} -> {}", symbol, leverage);
            return 0;
        }
    }
    
    /**
     * 检查是否支持杠杆交易（兼容旧方法名）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return true = 支持，false = 不支持
     * @deprecated 使用 supportsStrategyA() 替代
     */
    @Deprecated
    public boolean supportsMargin(String symbol) {
        return supportsStrategyA(symbol);
    }
    
    /**
     * 检查是否支持现货交易（兼容旧方法名）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return true = 支持，false = 不支持
     * @deprecated 使用 supportsStrategyB() 替代
     */
    @Deprecated
    public boolean supportsSpot(String symbol) {
        return supportsStrategyB(symbol);
    }
    
    /**
     * 获取原始 leverage 值
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return leverage 值（如 "10", "0" 或 null）
     */
    public String getLeverageString(String symbol) {
        return cache.get(symbol);
    }
    
    /**
     * 获取原始 flags 值（兼容旧方法名）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return flags 值（如 "10", "0" 或 null）
     * @deprecated 使用 getLeverageString() 替代
     */
    @Deprecated
    public String getFlags(String symbol) {
        return getLeverageString(symbol);
    }
    
    /**
     * 获取缓存大小
     */
    public int size() {
        return cache.size();
    }
    
    /**
     * 获取 Redis 连接管理器
     * 
     * @return Redis 连接管理器
     */
    public RedisConnectionManager getRedisManager() {
        return redisManager;
    }
    
    /**
     * 关闭 Redis 连接
     */
    public void close() {
        try {
            redisManager.close();
            logger.info("✓ 杠杆支持信息缓存已关闭");
        } catch (Exception e) {
            logger.error("✗ 关闭杠杆支持信息缓存失败: {}", e.getMessage(), e);
        }
    }
}
