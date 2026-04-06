# K线采集器更新说明

## 更新时间
2024-04-06

## 更新内容

### 使用现有的 RedisConnectionManager

已将 `OKXKlineWebSocketSourceFunction` 更新为使用项目中现有的 `RedisConnectionManager` 类，而不是直接创建 `JedisPool`。

### 更新的文件

**data-warehouse/src/main/java/com/crypto/dw/flink/source/OKXKlineWebSocketSourceFunction.java**

### 主要变更

#### 1. 移除直接的 Jedis 依赖

**之前:**
```java
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

private transient JedisPool jedisPool;
private final String redisHost;
private final int redisPort;
private final String redisPassword;
```

**之后:**
```java
import com.crypto.dw.redis.RedisConnectionManager;

private transient RedisConnectionManager redisManager;
```

#### 2. 简化构造函数

**之前:**
```java
public OKXKlineWebSocketSourceFunction(ConfigLoader config) {
    this.config = config;
    this.redisHost = config.getString("redis.host", "localhost");
    this.redisPort = config.getInt("redis.port", 6379);
    this.redisPassword = config.getString("redis.password", null);
    this.refreshIntervalSeconds = config.getInt("kline.subscription.refresh-interval-seconds", 300);
    // ...
}
```

**之后:**
```java
public OKXKlineWebSocketSourceFunction(ConfigLoader config) {
    this.config = config;
    this.refreshIntervalSeconds = config.getInt("kline.subscription.refresh-interval-seconds", 300);
    // ...
}
```

#### 3. 简化 open() 方法

**之前:**
```java
@Override
public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    
    // 初始化 Redis 连接池
    JedisPoolConfig poolConfig = new JedisPoolConfig();
    poolConfig.setMaxTotal(10);
    poolConfig.setMaxIdle(5);
    poolConfig.setMinIdle(1);
    poolConfig.setTestOnBorrow(true);
    
    if (redisPassword != null && !redisPassword.isEmpty()) {
        jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000, redisPassword);
    } else {
        jedisPool = new JedisPool(poolConfig, redisHost, redisPort, 2000);
    }
    
    logger.info("Redis connection pool initialized");
}
```

**之后:**
```java
@Override
public void open(Configuration parameters) throws Exception {
    super.open(parameters);
    
    // 初始化 Redis 连接管理器
    redisManager = new RedisConnectionManager(config);
    
    // 测试 Redis 连接
    if (!redisManager.testConnection()) {
        throw new RuntimeException("Failed to connect to Redis");
    }
    
    logger.info("✓ Redis connection manager initialized");
}
```

#### 4. 简化 Redis 操作

**之前:**
```java
private Set<String> getSubscriptionsFromRedis() {
    try (Jedis jedis = jedisPool.getResource()) {
        Set<String> subscriptions = jedis.smembers("kline:subscriptions");
        logger.debug("Fetched {} subscriptions from Redis", subscriptions.size());
        return subscriptions;
    } catch (Exception e) {
        logger.error("Failed to fetch subscriptions from Redis: {}", e.getMessage(), e);
        return Collections.emptySet();
    }
}
```

**之后:**
```java
private Set<String> getSubscriptionsFromRedis() {
    try {
        Set<String> subscriptions = redisManager.getSet("kline:subscriptions");
        logger.debug("Fetched {} subscriptions from Redis", subscriptions.size());
        return subscriptions;
    } catch (Exception e) {
        logger.error("Failed to fetch subscriptions from Redis: {}", e.getMessage(), e);
        return Collections.emptySet();
    }
}
```

#### 5. 简化 cancel() 方法

**之前:**
```java
@Override
public void cancel() {
    running.set(false);
    
    try {
        closeWebSocketQuietly();
        
        // 关闭 Redis 连接池
        if (jedisPool != null) {
            jedisPool.close();
            logger.info("Redis connection pool closed");
        }
        
        // 打印统计信息
        // ...
    } catch (Exception e) {
        logger.error("Error during cancellation", e);
    }
}
```

**之后:**
```java
@Override
public void cancel() {
    running.set(false);
    
    try {
        closeWebSocketQuietly();
        
        // 关闭 Redis 连接管理器
        if (redisManager != null) {
            redisManager.close();
            logger.info("✓ Redis connection manager closed");
        }
        
        // 打印统计信息
        // ...
    } catch (Exception e) {
        logger.error("Error during cancellation", e);
    }
}
```

## 优势

### 1. 代码复用
- 使用项目统一的 Redis 连接管理器
- 避免重复的连接池配置代码
- 减少代码维护成本

### 2. 一致性
- 与项目其他模块保持一致的 Redis 访问方式
- 统一的错误处理和日志记录
- 统一的连接池配置

### 3. 简化代码
- 减少了约 50 行代码
- 更清晰的代码结构
- 更容易理解和维护

### 4. 功能增强
- 自动连接测试
- 更好的错误处理
- 连接池状态监控
- 统一的日志格式

## RedisConnectionManager 功能

### 核心功能
- 连接池管理（自动初始化、配置、关闭）
- 连接测试（testConnection）
- String 操作（get, set, setex, del, exists）
- Set 操作（addToSet, removeFromSet, getSet, isMemberOfSet, getSetSize）
- Hash 操作（hset, hget, hdel, hexists）
- 连接池状态监控（getPoolStatus）

### 配置参数
```yaml
redis:
  host: localhost
  port: 6379
  timeout: 2000
  password:  # 可选
```

### 连接池配置
- 最大连接数: 10
- 最大空闲连接数: 5
- 最小空闲连接数: 1
- 最大等待时间: 3000ms
- 连接测试: 启用（borrow/return/idle）

## 使用示例

### 基本使用
```java
// 创建连接管理器
RedisConnectionManager redisManager = new RedisConnectionManager(config);

// 测试连接
if (!redisManager.testConnection()) {
    throw new RuntimeException("Failed to connect to Redis");
}

// 获取 Set
Set<String> subscriptions = redisManager.getSet("kline:subscriptions");

// 添加元素到 Set
redisManager.addToSet("kline:subscriptions", "BTC-USDT:4H");

// 移除元素
redisManager.removeFromSet("kline:subscriptions", "BTC-USDT:4H");

// 检查元素是否存在
boolean exists = redisManager.isMemberOfSet("kline:subscriptions", "BTC-USDT:4H");

// 获取 Set 大小
long size = redisManager.getSetSize("kline:subscriptions");

// 关闭连接管理器
redisManager.close();
```

### 在 Flink Source Function 中使用
```java
public class MySourceFunction extends RichSourceFunction<String> {
    
    private transient RedisConnectionManager redisManager;
    
    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        
        // 初始化 Redis 连接管理器
        redisManager = new RedisConnectionManager(config);
        
        // 测试连接
        if (!redisManager.testConnection()) {
            throw new RuntimeException("Failed to connect to Redis");
        }
    }
    
    @Override
    public void run(SourceContext<String> ctx) throws Exception {
        while (running) {
            // 从 Redis 读取数据
            Set<String> data = redisManager.getSet("my-key");
            
            // 处理数据
            for (String item : data) {
                ctx.collect(item);
            }
            
            Thread.sleep(1000);
        }
    }
    
    @Override
    public void cancel() {
        running = false;
        
        // 关闭 Redis 连接管理器
        if (redisManager != null) {
            redisManager.close();
        }
    }
}
```

## 兼容性

### 向后兼容
- 功能完全兼容，无需修改配置文件
- API 调用方式更简单
- 行为保持一致

### 依赖
- 无需添加新的依赖
- 使用项目现有的 `RedisConnectionManager` 类
- Jedis 依赖已存在于 `pom.xml`

## 测试建议

### 1. 单元测试
```java
@Test
public void testRedisConnection() {
    RedisConnectionManager redisManager = new RedisConnectionManager(config);
    assertTrue(redisManager.testConnection());
    redisManager.close();
}

@Test
public void testGetSet() {
    RedisConnectionManager redisManager = new RedisConnectionManager(config);
    
    // 添加测试数据
    redisManager.addToSet("test:set", "item1", "item2");
    
    // 获取数据
    Set<String> items = redisManager.getSet("test:set");
    assertEquals(2, items.size());
    
    // 清理
    redisManager.del("test:set");
    redisManager.close();
}
```

### 2. 集成测试
```bash
# 1. 启动 Redis
docker run -d --name redis -p 6379:6379 redis:latest

# 2. 添加测试订阅
redis-cli SADD kline:subscriptions "BTC-USDT:4H"

# 3. 启动 Flink 作业
bash run-flink-kline-collector.sh

# 4. 查看日志
tail -f logs/crypto-dw.log | grep "Redis"

# 5. 验证订阅
redis-cli SMEMBERS kline:subscriptions
```

## 相关文档

- [RedisConnectionManager 源码](../src/main/java/com/crypto/dw/redis/RedisConnectionManager.java)
- [K线采集器使用指南](KLINE_COLLECTOR_GUIDE.md)
- [Redis 配置指南](KLINE_REDIS_SETUP.md)
- [实现总结](KLINE_IMPLEMENTATION_SUMMARY.md)

## 版本历史

### v1.1.0 (2024-04-06)
- ✓ 使用 RedisConnectionManager 替代直接的 JedisPool
- ✓ 简化代码结构
- ✓ 提高代码复用性
- ✓ 增强错误处理

### v1.0.0 (2024-04-06)
- ✓ 初始版本发布
- ✓ 支持 Redis 动态订阅管理
- ✓ 支持所有 OKX K线周期

---

**更新完成时间**: 2024-04-06  
**更新状态**: ✓ 完成  
**测试状态**: 待测试
