package com.crypto.dw.source;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.redis.RedisConnectionManager;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;

/**
 * Redis 黑名单数据源
 * 
 * 功能：
 * 1. 定期从 Redis 读取交易对黑名单
 * 2. 发送到广播流
 * 
 * Redis 数据结构：
 * - Key: "crypto:blacklist"
 * - Type: Set
 * - Value: ["SHIB-USDT", "DOGE-USDT", ...]（需要过滤的交易对）
 * 
 * 性能优化：
 * - 使用 RedisConnectionManager 管理连接池
 * - 定期刷新（每 60 秒），减少 Redis 压力
 * - 异常处理，保证稳定性
 */
public class RedisBlacklistSource 
        extends RichMapFunction<Long, Tuple2<String, Boolean>> 
        implements SourceFunction<Tuple2<String, Boolean>> {
    
    private static final Logger logger = LoggerFactory.getLogger(RedisBlacklistSource.class);
    
    private final ConfigLoader config;
    private transient RedisConnectionManager redisManager;
    private volatile boolean isRunning = true;
    
    // 刷新间隔：60 秒
    private static final long REFRESH_INTERVAL_MS = 60000;
    
    // Redis Key
    private static final String BLACKLIST_KEY = "crypto:blacklist";
    
    public RedisBlacklistSource(ConfigLoader config) {
        this.config = config;
    }
    
    @Override
    public void open(Configuration parameters) {
        // 初始化 Redis 连接管理器
        // 性能优化：使用连接池，避免频繁创建连接
        redisManager = new RedisConnectionManager(config);
        
        // 测试连接
        if (redisManager.testConnection()) {
            logger.info("✓ Redis 连接测试成功");
        } else {
            logger.warn("⚠ Redis 连接测试失败，黑名单将为空");
        }
    }
    
    @Override
    public void run(SourceContext<Tuple2<String, Boolean>> ctx) throws Exception {
        while (isRunning) {
            try {
                // 从 Redis 读取黑名单
                Set<String> blacklist = fetchBlacklistFromRedis();
                
                // 发送到广播流
                for (String symbol : blacklist) {
                    ctx.collect(new Tuple2<>(symbol, true));
                }
                
                logger.info("从 Redis 读取黑名单成功，数量: {}", blacklist.size());
                if (logger.isDebugEnabled()) {
                    logger.debug("黑名单内容: {}", blacklist);
                }
                
            } catch (Exception e) {
                logger.error("从 Redis 读取黑名单失败: {}", e.getMessage(), e);
            }
            
            // 等待下一次刷新
            Thread.sleep(REFRESH_INTERVAL_MS);
        }
    }
    
    @Override
    public void cancel() {
        isRunning = false;
    }
    
    @Override
    public void close() {
        if (redisManager != null) {
            logger.info("关闭 Redis 连接管理器...");
            logger.info(redisManager.getPoolStatus());
            redisManager.close();
        }
    }
    
    /**
     * 从 Redis 读取黑名单
     * 
     * Redis 命令：SMEMBERS crypto:blacklist
     */
    private Set<String> fetchBlacklistFromRedis() {
        try {
            // 使用 RedisConnectionManager 读取 Set
            Set<String> blacklist = redisManager.getSet(BLACKLIST_KEY);
            
            // 如果 Redis 中没有数据，返回空黑名单（不过滤任何交易对）
            if (blacklist == null || blacklist.isEmpty()) {
                logger.info("Redis 中没有黑名单数据（Key: {}），不过滤任何交易对", BLACKLIST_KEY);
                return new HashSet<>();
            }
            
            return blacklist;
        } catch (Exception e) {
            logger.error("从 Redis 读取黑名单失败，不过滤任何交易对: {}", e.getMessage());
            return new HashSet<>();
        }
    }
    
    @Override
    public Tuple2<String, Boolean> map(Long value) {
        return null;  // 不使用
    }
}
