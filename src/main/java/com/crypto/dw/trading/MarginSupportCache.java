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
 * - Value: flags ("1" = 支持策略A, "2" = 支持策略B, "12" = 都支持)
 * 
 * 性能优化：
 * - 内存缓存，查询时间 O(1)
 * - 减少 Redis 访问，降低网络延迟
 * - 线程安全，支持并发访问
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
        
        // 启动时加载所有数据
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
     * 检查是否支持杠杆交易（策略A：做空现货+做多合约）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return true = 支持，false = 不支持
     */
    public boolean supportsMargin(String symbol) {
        String flags = cache.get(symbol);
        if (flags == null || flags.isEmpty()) {
            return false;
        }
        
        // 检查是否支持策略A（做空现货+做多合约，需要杠杆）
        return flags.contains("1");
    }
    
    /**
     * 检查是否支持现货交易（策略B：做多现货+做空合约）
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return true = 支持，false = 不支持
     */
    public boolean supportsSpot(String symbol) {
        String flags = cache.get(symbol);
        if (flags == null || flags.isEmpty()) {
            return false;
        }
        
        // 检查是否支持策略B（做多现货+做空合约，不需要杠杆）
        return flags.contains("2");
    }
    
    /**
     * 获取原始 flags 值
     * 
     * @param symbol 交易对（如 "BTC-USDT"）
     * @return flags 值（"1", "2", "12" 或 null）
     */
    public String getFlags(String symbol) {
        return cache.get(symbol);
    }
    
    /**
     * 获取缓存大小
     */
    public int size() {
        return cache.size();
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
