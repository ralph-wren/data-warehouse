# Kafka 配置 - 修复 startup-mode 枚举值格式错误

## 问题描述

Flink 作业启动时报配置验证错误:
```
org.apache.flink.table.api.ValidationException: Invalid value for option 'scan.startup.mode'.
Could not parse value 'earliest' for key 'scan.startup.mode'.
Expected one of: [[earliest-offset, latest-offset, group-offsets, timestamp, specific-offsets]]
```

## 问题现象

1. **配置验证失败**: Flink Table API 无法识别 `scan.startup.mode` 的值
2. **错误位置**: 创建 Kafka Source 表时
3. **期望值**: `earliest-offset`、`latest-offset`、`group-offsets` 等
4. **实际值**: `earliest`、`latest`、`committed`

## 根本原因

**配置值格式不匹配** ⭐⭐⭐

1. **配置文件使用简写形式**:
   ```yaml
   kafka:
     consumer:
       startup-mode: earliest  # 简写形式
   ```

2. **Flink Table API 需要完整枚举值**:
   - DataStream API: 支持简写（`earliest`、`latest`、`committed`）
   - Table API: 需要完整枚举值（`earliest-offset`、`latest-offset`、`group-offsets`）

3. **两个 API 的差异**:
   ```java
   // DataStream API - 支持简写 ✅
   KafkaSource.builder()
       .setStartingOffsets(OffsetsInitializer.earliest())  // 方法调用
   
   // Table API - 需要完整枚举值 ✅
   CREATE TABLE kafka_source (...) WITH (
       'scan.startup.mode' = 'earliest-offset'  // 字符串配置
   )
   ```

## 解决方案

**添加 startup mode 转换方法** ✅

修改 `FlinkTableFactory.java`,添加转换逻辑:

```java
/**
 * 转换 startup mode 为 Flink Table API 期望的格式
 * 
 * 配置文件使用简写形式,便于理解和配置:
 * - earliest: 从最早数据开始
 * - latest: 从最新数据开始
 * - committed: 从上次提交位置开始
 * 
 * Flink Table API 需要完整的枚举值:
 * - earliest-offset: 从最早数据开始
 * - latest-offset: 从最新数据开始
 * - group-offsets: 从上次提交位置开始
 * 
 * @param startupMode 配置文件中的 startup mode
 * @return Flink Table API 期望的 startup mode
 */
private String convertStartupMode(String startupMode) {
    switch (startupMode.toLowerCase()) {
        case "earliest":
            return "earliest-offset";
        case "latest":
            return "latest-offset";
        case "committed":
        case "group-offsets":
            return "group-offsets";
        case "earliest-offset":
        case "latest-offset":
            // 如果已经是完整格式,直接返回
            return startupMode;
        default:
            logger.warn("未知的 startup mode: {}, 使用默认值 latest-offset", startupMode);
            return "latest-offset";
    }
}
```

**在 createKafkaSourceTable 方法中使用转换**:
```java
public String createKafkaSourceTable(String tableName, String schema, boolean withWatermark, String groupId) {
    String bootstrapServers = config.getString("kafka.bootstrap-servers");
    String topic = config.getString("kafka.topic.crypto-ticker-spot");
    String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
    
    // 转换 startup mode 为 Flink Table API 期望的格式
    String flinkStartupMode = convertStartupMode(startupMode);
    
    logger.info("  Startup Mode: {} -> {}", startupMode, flinkStartupMode);
    
    // 使用转换后的值
    params.put("startupMode", flinkStartupMode);
    ...
}
```

## 技术细节

### Flink Kafka Connector 的两个 API

**1. DataStream API**:
```java
// 使用方法调用,支持简写
KafkaSource<String> source = KafkaSource.<String>builder()
    .setStartingOffsets(OffsetsInitializer.earliest())  // ✅ 方法名
    .build();
```

**2. Table API**:
```sql
-- 使用字符串配置,需要完整枚举值
CREATE TABLE kafka_source (...) WITH (
    'scan.startup.mode' = 'earliest-offset'  -- ✅ 枚举值字符串
)
```

### startup mode 映射关系

| 配置文件（简写） | DataStream API | Table API | 说明 |
|----------------|---------------|-----------|------|
| `earliest` | `OffsetsInitializer.earliest()` | `earliest-offset` | 从最早数据开始 |
| `latest` | `OffsetsInitializer.latest()` | `latest-offset` | 从最新数据开始 |
| `committed` | `OffsetsInitializer.committedOffsets()` | `group-offsets` | 从上次提交位置开始 |

### 为什么使用简写形式?

**配置文件的优势**:
1. ✅ 更简洁易读
2. ✅ 与 DataStream API 保持一致
3. ✅ 便于理解和记忆
4. ✅ 减少配置错误

**转换方法的优势**:
1. ✅ 统一配置格式
2. ✅ 自动处理格式转换
3. ✅ 支持两种格式（简写和完整）
4. ✅ 提供默认值和错误处理

## 验证方法

### 1. 编译项目
```bash
mvn clean compile -DskipTests
```

### 2. 运行作业
```bash
# 运行 ODS SQL 作业
bash run-flink-ods-sql.sh

# 查看日志
tail -f logs/app/flink-app.log
```

### 3. 检查日志输出
```
创建 Kafka Source 表: kafka_source
  Bootstrap Servers: localhost:9093
  Topic: crypto-ticker-spot
  Group ID: flink-ods-sql-group
  Startup Mode: earliest -> earliest-offset  # 转换成功
```

## 涉及文件

**修改的文件**:
- `src/main/java/com/crypto/dw/flink/factory/FlinkTableFactory.java`
  - 添加 `convertStartupMode()` 方法
  - 修改 `createKafkaSourceTable()` 方法,使用转换后的值

**配置文件**:
- `src/main/resources/config/application-dev.yml`
  - `kafka.consumer.startup-mode: earliest` (保持简写形式)

## 相关问题

本次问题与以下问题相关:
1. [202603242335-Kafka消费-解决NoOffsetForPartitionException错误.md](202603242335-Kafka消费-解决NoOffsetForPartitionException错误.md)
2. [202603250000-FlinkSQL解析-移除DDL模板文件注释解决解析错误.md](202603250000-FlinkSQL解析-移除DDL模板文件注释解决解析错误.md)

## 最佳实践

### 1. 配置文件使用简写形式
```yaml
# ✅ 推荐 - 简洁易读
kafka:
  consumer:
    startup-mode: earliest

# ❌ 不推荐 - 冗长难记
kafka:
  consumer:
    startup-mode: earliest-offset
```

### 2. 代码中进行格式转换
```java
// ✅ 推荐 - 自动转换
String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
String flinkStartupMode = convertStartupMode(startupMode);

// ❌ 不推荐 - 直接使用可能导致错误
String startupMode = config.getString("kafka.consumer.startup-mode");
params.put("startupMode", startupMode);  // 可能格式不匹配
```

### 3. 提供默认值和错误处理
```java
// ✅ 推荐 - 有默认值和错误处理
private String convertStartupMode(String startupMode) {
    switch (startupMode.toLowerCase()) {
        case "earliest":
            return "earliest-offset";
        // ...
        default:
            logger.warn("未知的 startup mode: {}, 使用默认值", startupMode);
            return "latest-offset";  // 默认值
    }
}
```

### 4. 记录转换过程
```java
// ✅ 推荐 - 记录转换,便于调试
logger.info("  Startup Mode: {} -> {}", startupMode, flinkStartupMode);
```

## 总结

通过添加 `convertStartupMode()` 方法,实现了配置文件简写形式到 Flink Table API 完整枚举值的自动转换。这个解决方案:

1. ✅ 保持配置文件简洁易读
2. ✅ 自动处理格式转换
3. ✅ 支持两种格式（简写和完整）
4. ✅ 提供默认值和错误处理
5. ✅ 与 DataStream API 保持一致

**关键经验**:
- DataStream API 和 Table API 的配置格式可能不同
- 使用转换方法统一配置格式
- 提供清晰的日志输出,便于调试
- 保持配置文件的简洁性和可读性

---

**作者**: Kiro AI Assistant  
**日期**: 2026-03-25 00:10  
**类型**: Bug 修复
