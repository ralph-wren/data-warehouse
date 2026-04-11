package com.crypto.dw.redis;

import com.crypto.dw.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.io.Serializable;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Redis 连接管理器
 * 
 * 功能：
 * - 管理 Redis 连接池
 * - 提供常用的 Redis 操作方法
 * - 自动处理连接的获取和释放
 * - 统一的异常处理和日志记录
 * 
 * 使用示例：
 * <pre>
 * // 创建连接管理器
 * RedisConnectionManager redisManager = new RedisConnectionManager(config);
 * 
 * // 读取 Set
 * Set<String> whitelist = redisManager.getSet("crypto:whitelist");
 * 
 * // 添加元素到 Set
 * redisManager.addToSet("crypto:whitelist", "BTC-USDT");
 * 
 * // 关闭连接池
 * redisManager.close();
 * </pre>
 * 
 * 性能优化：
 * - 使用连接池，避免频繁创建连接
 * - 自动释放连接，防止连接泄漏
 * - 配置合理的连接池参数
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class RedisConnectionManager implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    private static final Logger logger = LoggerFactory.getLogger(RedisConnectionManager.class);
    
    private transient JedisPool jedisPool;
    private final String host;
    private final int port;
    private final int timeout;
    private final String password;
    
    /**
     * 构造函数
     * 
     * @param config 配置加载器
     */
    public RedisConnectionManager(ConfigLoader config) {
        this.host = config.getString("redis.host", "localhost");
        this.port = config.getInt("redis.port", 6379);
        this.timeout = config.getInt("redis.timeout", 2000);
        this.password = config.getString("redis.password", null);
        
        logger.info("==========================================");
        logger.info("Redis 连接管理器初始化");
        logger.info("==========================================");
        logger.info("Redis 配置:");
        logger.info("  Host: {}", host);
        logger.info("  Port: {}", port);
        logger.info("  Timeout: {} ms", timeout);
        logger.info("  Password: {}", password != null && !password.isEmpty() ? "已配置" : "未配置");
        
        initPool();
    }
    
    /**
     * 构造函数（指定主机和端口）
     * 
     * @param host Redis 主机
     * @param port Redis 端口
     */
    public RedisConnectionManager(String host, int port) {
        this.host = host;
        this.port = port;
        this.timeout = 2000;
        this.password = null;
        
        logger.info("Redis 连接管理器初始化: {}:{}", host, port);
        
        initPool();
    }
    
    /**
     * 初始化连接池
     */
    private void initPool() {
        if (jedisPool != null) {
            return;
        }
        
        try {
            // 配置连接池参数
            JedisPoolConfig poolConfig = new JedisPoolConfig();
            
            // 最大连接数
            poolConfig.setMaxTotal(10);
            
            // 最大空闲连接数
            poolConfig.setMaxIdle(5);
            
            // 最小空闲连接数
            poolConfig.setMinIdle(1);
            
            // 获取连接时测试连接是否可用
            poolConfig.setTestOnBorrow(true);
            
            // 归还连接时测试连接是否可用
            poolConfig.setTestOnReturn(true);
            
            // 空闲时测试连接是否可用
            poolConfig.setTestWhileIdle(true);
            
            // 最大等待时间（毫秒）
            poolConfig.setMaxWaitMillis(3000);
            
            // 创建连接池
            if (password != null && !password.isEmpty()) {
                jedisPool = new JedisPool(poolConfig, host, port, timeout, password);
            } else {
                jedisPool = new JedisPool(poolConfig, host, port, timeout);
            }
            
            // 测试连接
            try (Jedis jedis = jedisPool.getResource()) {
                String pong = jedis.ping();
                logger.info("✓ Redis 连接测试成功: {}", pong);
            }
            
            logger.info("✓ Redis 连接池初始化成功");
            logger.info("  最大连接数: {}", poolConfig.getMaxTotal());
            logger.info("  最大空闲连接数: {}", poolConfig.getMaxIdle());
            logger.info("  最小空闲连接数: {}", poolConfig.getMinIdle());
            logger.info("==========================================");
            
        } catch (Exception e) {
            logger.error("Redis 连接池初始化失败: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to initialize Redis connection pool", e);
        }
    }
    
    /**
     * 获取 Jedis 连接
     * 
     * 注意：使用完毕后必须调用 close() 方法释放连接
     * 
     * @return Jedis 连接
     */
    public Jedis getConnection() {
        if (jedisPool == null) {
            initPool();
        }
        return jedisPool.getResource();
    }
    
    /**
     * 测试连接
     * 
     * @return true 表示连接正常，false 表示连接失败
     */
    public boolean testConnection() {
        try (Jedis jedis = getConnection()) {
            String pong = jedis.ping();
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            logger.error("Redis 连接测试失败: {}", e.getMessage());
            return false;
        }
    }
    
    // ========== String 操作 ==========
    
    /**
     * 设置字符串值
     * 
     * @param key 键
     * @param value 值
     * @return 操作是否成功
     */
    public boolean set(String key, String value) {
        try (Jedis jedis = getConnection()) {
            String result = jedis.set(key, value);
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            logger.error("Redis SET 操作失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }
    
    /**
     * 设置字符串值（带过期时间）
     * 
     * @param key 键
     * @param value 值
     * @param expireSeconds 过期时间（秒）
     * @return 操作是否成功
     */
    public boolean setex(String key, String value, int expireSeconds) {
        try (Jedis jedis = getConnection()) {
            String result = jedis.setex(key, expireSeconds, value);
            return "OK".equalsIgnoreCase(result);
        } catch (Exception e) {
            logger.error("Redis SETEX 操作失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取字符串值
     * 
     * @param key 键
     * @return 值，如果不存在返回 null
     */
    public String get(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.get(key);
        } catch (Exception e) {
            logger.error("Redis GET 操作失败: key={}, error={}", key, e.getMessage());
            return null;
        }
    }
    
    /**
     * 删除键
     * 
     * @param key 键
     * @return 删除的键数量
     */
    public long del(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.del(key);
        } catch (Exception e) {
            logger.error("Redis DEL 操作失败: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 检查键是否存在
     * 
     * @param key 键
     * @return true 表示存在，false 表示不存在
     */
    public boolean exists(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.exists(key);
        } catch (Exception e) {
            logger.error("Redis EXISTS 操作失败: key={}, error={}", key, e.getMessage());
            return false;
        }
    }
    
    // ========== Set 操作 ==========
    
    /**
     * 添加元素到 Set
     * 
     * @param key 键
     * @param members 成员
     * @return 添加的元素数量
     */
    public long addToSet(String key, String... members) {
        try (Jedis jedis = getConnection()) {
            return jedis.sadd(key, members);
        } catch (Exception e) {
            logger.error("Redis SADD 操作失败: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 从 Set 中移除元素
     * 
     * @param key 键
     * @param members 成员
     * @return 移除的元素数量
     */
    public long removeFromSet(String key, String... members) {
        try (Jedis jedis = getConnection()) {
            return jedis.srem(key, members);
        } catch (Exception e) {
            logger.error("Redis SREM 操作失败: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 获取 Set 的所有成员
     * 
     * @param key 键
     * @return Set 的所有成员，如果不存在返回空 Set
     */
    public Set<String> getSet(String key) {
        try (Jedis jedis = getConnection()) {
            Set<String> members = jedis.smembers(key);
            return members != null ? members : new HashSet<>();
        } catch (Exception e) {
            logger.error("Redis SMEMBERS 操作失败: key={}, error={}", key, e.getMessage());
            return new HashSet<>();
        }
    }
    
    /**
     * 检查元素是否在 Set 中
     * 
     * @param key 键
     * @param member 成员
     * @return true 表示存在，false 表示不存在
     */
    public boolean isMemberOfSet(String key, String member) {
        try (Jedis jedis = getConnection()) {
            return jedis.sismember(key, member);
        } catch (Exception e) {
            logger.error("Redis SISMEMBER 操作失败: key={}, member={}, error={}", 
                key, member, e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取 Set 的元素数量
     * 
     * @param key 键
     * @return Set 的元素数量
     */
    public long getSetSize(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.scard(key);
        } catch (Exception e) {
            logger.error("Redis SCARD 操作失败: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }
    
    // ========== Hash 操作 ==========
    
    /**
     * 设置 Hash 字段值
     * 
     * @param key 键
     * @param field 字段
     * @param value 值
     * @return 操作是否成功
     */
    public boolean hset(String key, String field, String value) {
        try (Jedis jedis = getConnection()) {
            jedis.hset(key, field, value);
            return true;
        } catch (Exception e) {
            logger.error("Redis HSET 操作失败: key={}, field={}, error={}", 
                key, field, e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取 Hash 字段值
     * 
     * @param key 键
     * @param field 字段
     * @return 字段值，如果不存在返回 null
     */
    public String hget(String key, String field) {
        try (Jedis jedis = getConnection()) {
            return jedis.hget(key, field);
        } catch (Exception e) {
            logger.error("Redis HGET 操作失败: key={}, field={}, error={}", 
                key, field, e.getMessage());
            return null;
        }
    }
    
    /**
     * 删除 Hash 字段
     * 
     * @param key 键
     * @param fields 字段
     * @return 删除的字段数量
     */
    public long hdel(String key, String... fields) {
        try (Jedis jedis = getConnection()) {
            return jedis.hdel(key, fields);
        } catch (Exception e) {
            logger.error("Redis HDEL 操作失败: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }
    
    /**
     * 检查 Hash 字段是否存在
     * 
     * @param key 键
     * @param field 字段
     * @return true 表示存在，false 表示不存在
     */
    public boolean hexists(String key, String field) {
        try (Jedis jedis = getConnection()) {
            return jedis.hexists(key, field);
        } catch (Exception e) {
            logger.error("Redis HEXISTS 操作失败: key={}, field={}, error={}", 
                key, field, e.getMessage());
            return false;
        }
    }
    
    /**
     * 获取 Hash 的所有字段和值
     * 
     * @param key 键
     * @return 所有字段和值的 Map，失败返回 null
     */
    public Map<String, String> hgetAll(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.hgetAll(key);
        } catch (Exception e) {
            logger.error("Redis HGETALL 操作失败: key={}, error={}", key, e.getMessage());
            return null;
        }
    }
    
    // ========== 计数器操作 ==========
    
    /**
     * 原子性递增计数器
     * 
     * @param key 键
     * @return 递增后的值，失败返回 -1
     */
    public long incr(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.incr(key);
        } catch (Exception e) {
            logger.error("Redis INCR 操作失败: key={}, error={}", key, e.getMessage());
            return -1;
        }
    }
    
    /**
     * 原子性递减计数器
     * 
     * @param key 键
     * @return 递减后的值，失败返回 -1
     */
    public long decr(String key) {
        try (Jedis jedis = getConnection()) {
            return jedis.decr(key);
        } catch (Exception e) {
            logger.error("Redis DECR 操作失败: key={}, error={}", key, e.getMessage());
            return -1;
        }
    }
    
    /**
     * 获取计数器的值
     * 
     * @param key 键
     * @return 计数器的值，如果不存在或失败返回 0
     */
    public long getCounter(String key) {
        try (Jedis jedis = getConnection()) {
            String value = jedis.get(key);
            return value == null ? 0 : Long.parseLong(value);
        } catch (Exception e) {
            logger.error("Redis GET 计数器操作失败: key={}, error={}", key, e.getMessage());
            return 0;
        }
    }
    
    // ========== 连接池管理 ==========
    
    /**
     * 获取连接池状态信息
     * 
     * @return 连接池状态信息
     */
    public String getPoolStatus() {
        if (jedisPool == null) {
            return "连接池未初始化";
        }
        
        return String.format(
            "连接池状态 - 活跃连接: %d, 空闲连接: %d, 等待线程: %d",
            jedisPool.getNumActive(),
            jedisPool.getNumIdle(),
            jedisPool.getNumWaiters()
        );
    }
    
    /**
     * 关闭连接池
     */
    public void close() {
        if (jedisPool != null && !jedisPool.isClosed()) {
            logger.info("关闭 Redis 连接池...");
            logger.info(getPoolStatus());
            jedisPool.close();
            logger.info("✓ Redis 连接池已关闭");
        }
    }
    
    /**
     * 检查连接池是否已关闭
     * 
     * @return true 表示已关闭，false 表示未关闭
     */
    public boolean isClosed() {
        return jedisPool == null || jedisPool.isClosed();
    }
}
