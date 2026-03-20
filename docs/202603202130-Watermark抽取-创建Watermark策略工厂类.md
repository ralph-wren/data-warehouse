# Watermark 抽取 - 创建 Watermark 策略工厂类

## 问题描述
从 `FlinkODSJobDataStream.java` 中抽取 watermark 相关代码，创建独立的工具类，方便复用和管理。

## 解决方案

### 1. 创建 WatermarkStrategyFactory 工厂类

创建了 `src/main/java/com/crypto/dw/flink/watermark/WatermarkStrategyFactory.java`，提供以下功能：

#### 主要方法：

1. **无 Watermark 策略**
   ```java
   WatermarkStrategy<String> strategy = WatermarkStrategyFactory.noWatermarks();
   ```
   - 适用场景：不需要基于事件时间的窗口计算
   - 当前 FlinkODSJobDataStream 使用的就是这种策略

2. **有界乱序 Watermark（字符串类型）**
   ```java
   WatermarkStrategy<String> strategy = 
       WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5000); // 5秒乱序
   ```
   - 适用场景：数据可能乱序到达，但延迟在可控范围内
   - 自动从 JSON 字符串中提取 "ts" 字段作为时间戳

3. **单调递增 Watermark（字符串类型）**
   ```java
   WatermarkStrategy<String> strategy = 
       WatermarkStrategyFactory.forMonotonousTimestampsString();
   ```
   - 适用场景：数据严格按时间顺序到达
   - 自动从 JSON 字符串中提取 "ts" 字段作为时间戳

4. **通用 Watermark 策略**
   ```java
   WatermarkStrategy<TickerData> strategy = 
       WatermarkStrategyFactory.forBoundedOutOfOrderness(
           5000,
           (element, recordTimestamp) -> element.getTs()
       );
   ```
   - 支持自定义数据类型
   - 需要提供时间戳提取器

5. **空闲检测 Watermark**
   ```java
   WatermarkStrategy<String> baseStrategy = 
       WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5000);
   WatermarkStrategy<String> strategy = 
       WatermarkStrategyFactory.withIdleness(baseStrategy, 60000); // 60秒空闲超时
   ```
   - 适用场景：某些分区可能长时间没有数据

### 2. 在 FlinkODSJobDataStream 中使用

#### 当前代码（第 99 行）：
```java
DataStream<String> rawStream = env.fromSource(
    kafkaSource,
    WatermarkStrategy.noWatermarks(),  // 直接使用 Flink API
    "Kafka Source"
);
```

#### 修改后（使用工厂类）：
```java
import com.crypto.dw.flink.watermark.WatermarkStrategyFactory;

// 方式1：无 Watermark（当前使用）
DataStream<String> rawStream = env.fromSource(
    kafkaSource,
    WatermarkStrategyFactory.noWatermarks(),
    "Kafka Source"
);

// 方式2：有界乱序 Watermark（推荐用于实时计算）
DataStream<String> rawStream = env.fromSource(
    kafkaSource,
    WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5000), // 5秒乱序
    "Kafka Source"
);

// 方式3：有界乱序 + 空闲检测（推荐用于多分区场景）
WatermarkStrategy<String> strategy = WatermarkStrategyFactory.withIdleness(
    WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5000),
    60000  // 60秒空闲超时
);
DataStream<String> rawStream = env.fromSource(
    kafkaSource,
    strategy,
    "Kafka Source"
);
```

### 3. 配置化 Watermark 策略

可以在 `application.yml` 中添加配置：

```yaml
flink:
  watermark:
    strategy: bounded-out-of-orderness  # 策略类型：none, bounded-out-of-orderness, monotonous
    max-out-of-orderness: 5000          # 最大乱序时间（毫秒）
    idle-timeout: 60000                 # 空闲超时时间（毫秒）
```

然后在代码中读取配置：

```java
String strategyType = config.getString("flink.watermark.strategy", "none");
WatermarkStrategy<String> watermarkStrategy;

switch (strategyType) {
    case "bounded-out-of-orderness":
        long maxOutOfOrderness = config.getLong("flink.watermark.max-out-of-orderness", 5000);
        watermarkStrategy = WatermarkStrategyFactory.forBoundedOutOfOrdernessString(maxOutOfOrderness);
        
        // 如果配置了空闲超时，添加空闲检测
        long idleTimeout = config.getLong("flink.watermark.idle-timeout", 0);
        if (idleTimeout > 0) {
            watermarkStrategy = WatermarkStrategyFactory.withIdleness(watermarkStrategy, idleTimeout);
        }
        break;
        
    case "monotonous":
        watermarkStrategy = WatermarkStrategyFactory.forMonotonousTimestampsString();
        break;
        
    case "none":
    default:
        watermarkStrategy = WatermarkStrategyFactory.noWatermarks();
        break;
}

DataStream<String> rawStream = env.fromSource(
    kafkaSource,
    watermarkStrategy,
    "Kafka Source"
);
```

## 优势

1. **代码复用**：Watermark 策略可以在多个 Flink 作业中复用
2. **易于维护**：集中管理 Watermark 相关逻辑
3. **灵活配置**：支持通过配置文件切换不同策略
4. **类型安全**：提供了针对字符串和通用类型的方法
5. **日志记录**：每个策略都有日志输出，方便调试

## 适用场景

### 无 Watermark（当前使用）
- ODS 层数据采集：只需要将数据写入存储，不需要窗口计算
- 简单的 ETL 任务：数据转换和传输

### 有界乱序 Watermark
- DWD 层实时清洗：需要基于时间窗口进行数据去重、过滤
- DWS 层实时聚合：需要按时间窗口计算指标（如 1 分钟 K 线）

### 单调递增 Watermark
- 数据源保证时间顺序：如某些时序数据库
- 测试环境：模拟理想情况

### 空闲检测
- 多分区 Kafka：某些分区可能长时间没有数据
- 多交易对场景：某些交易对交易量很小

## 后续优化建议

1. 支持从配置文件读取 Watermark 策略
2. 添加更多时间戳提取方式（如从 Avro、Protobuf 中提取）
3. 支持自定义 Watermark 生成器
4. 添加 Watermark 延迟监控指标

## 相关文件

- `src/main/java/com/crypto/dw/flink/watermark/WatermarkStrategyFactory.java` - Watermark 策略工厂类
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - ODS 层 Flink 作业（当前使用无 Watermark）
- `src/main/java/com/crypto/dw/flink/FlinkDWDJobSQL.java` - DWD 层 Flink 作业（可能需要 Watermark）
- `src/main/java/com/crypto/dw/flink/FlinkDWSJob1MinSQL.java` - DWS 层 Flink 作业（需要 Watermark）

## 测试建议

1. 在 DWD 或 DWS 层作业中测试有界乱序 Watermark
2. 观察 Flink Web UI 中的 Watermark 指标
3. 验证窗口计算的正确性
4. 测试空闲检测在多分区场景下的效果
