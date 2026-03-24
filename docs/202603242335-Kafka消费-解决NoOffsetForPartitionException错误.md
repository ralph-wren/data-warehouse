# Kafka 消费 - 解决 NoOffsetForPartitionException 错误

## 问题描述

Flink 作业启动时报错:
```
org.apache.kafka.clients.consumer.NoOffsetForPartitionException: 
Undefined offset with no reset policy for partitions: [crypto-ticker-spot-2]
```

## 问题现象

1. **作业启动失败**: Flink 作业在启动后立即失败
2. **重试 3 次后停止**: 根据重启策略,重试 3 次后作业完全停止
3. **错误信息**: Kafka Consumer 找不到分区的消费位置(offset)

## 错误堆栈

```
Caused by: org.apache.kafka.clients.consumer.NoOffsetForPartitionException: 
Undefined offset with no reset policy for partitions: [crypto-ticker-spot-2]
    at org.apache.kafka.clients.consumer.internals.SubscriptionState.resetInitializingPositions
    at org.apache.kafka.clients.consumer.KafkaConsumer.updateFetchPositions
    at org.apache.kafka.clients.consumer.KafkaConsumer.position
```

## 根本原因

**`committed` 消费模式缺少回退策略** ⭐⭐⭐

1. **配置的消费模式**: `kafka.consumer.startup-mode = committed`
   - 表示从上次提交的 offset 开始消费
   - 适合故障恢复场景

2. **Consumer Group 没有提交过 offset**:
   - 这是一个新的 Consumer Group
   - 或者之前的 offset 已经过期被清理
   - Kafka 中找不到这个 Consumer Group 的 offset 记录

3. **没有回退策略**:
   - 原代码: `OffsetsInitializer.committedOffsets()`
   - 当找不到 offset 时,没有指定如何处理
   - 导致抛出 `NoOffsetForPartitionException` 异常

## 解决方案

**为 `committed` 模式添加回退策略** ✅

修改 `KafkaSourceFactory.java` 中的 offset 初始化逻辑:

```java
// 修改前 ❌ - 没有回退策略
case "committed":
    offsetsInitializer = OffsetsInitializer.committedOffsets();
    logger.info("  Startup Mode: committed（从上次提交的 offset 开始）");
    break;

// 修改后 ✅ - 添加回退策略
case "committed":
    // 使用 committedOffsets 并指定回退策略
    // 如果没有提交的 offset,则从最新数据开始（避免重复消费历史数据）
    offsetsInitializer = OffsetsInitializer.committedOffsets(
        org.apache.kafka.clients.consumer.OffsetResetStrategy.LATEST
    );
    logger.info("  Startup Mode: committed（从上次提交的 offset 开始,无 offset 时从最新数据开始）");
    break;
```

## 技术细节

### Kafka Offset 初始化策略

**1. committedOffsets() - 无回退策略**:
```java
OffsetsInitializer.committedOffsets()
```
- 从上次提交的 offset 开始消费
- 如果找不到 offset,抛出异常
- ❌ 不适合新的 Consumer Group

**2. committedOffsets(OffsetResetStrategy) - 有回退策略**:
```java
OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST)
```
- 从上次提交的 offset 开始消费
- 如果找不到 offset,使用回退策略
- ✅ 适合所有场景

### OffsetResetStrategy 选项

| 策略 | 说明 | 使用场景 |
|-----|------|---------|
| `LATEST` | 从最新数据开始 | 实时处理,不关心历史数据 |
| `EARLIEST` | 从最早数据开始 | 需要处理所有历史数据 |
| `NONE` | 抛出异常 | 严格要求必须有 offset |

### 为什么选择 LATEST?

1. **避免重复消费**: 如果选择 EARLIEST,会消费所有历史数据
2. **实时处理优先**: 大多数场景下,我们关心的是实时数据
3. **减少启动时间**: 不需要处理大量历史数据
4. **符合预期**: 新的 Consumer Group 应该从当前时间点开始

## 消费模式对比

### 1. earliest - 从最早数据开始
```java
OffsetsInitializer.earliest()
```
- **优点**: 可以处理所有历史数据
- **缺点**: 启动时间长,可能重复消费
- **适用**: 数据回填、历史数据分析

### 2. latest - 从最新数据开始
```java
OffsetsInitializer.latest()
```
- **优点**: 启动快,只处理新数据
- **缺点**: 丢失启动前的数据
- **适用**: 实时监控、告警

### 3. committed - 从上次提交位置开始
```java
OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST)
```
- **优点**: 故障恢复不丢数据,不重复消费
- **缺点**: 需要正确配置回退策略
- **适用**: 生产环境、需要 Exactly-Once 语义

## 配置建议

### 开发环境
```yaml
kafka:
  consumer:
    startup-mode: latest  # 快速启动,只处理新数据
```

### 测试环境
```yaml
kafka:
  consumer:
    startup-mode: earliest  # 处理所有数据,验证完整性
```

### 生产环境
```yaml
kafka:
  consumer:
    startup-mode: committed  # 故障恢复,不丢数据
```

## 验证方法

### 1. 检查 Consumer Group
```bash
# 查看 Consumer Group 列表
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# 查看 Consumer Group 详情
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group flink-dwd-group --describe
```

### 2. 重置 Offset（如果需要）
```bash
# 重置到最早
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group flink-dwd-group --reset-offsets --to-earliest \
  --topic crypto-ticker-spot --execute

# 重置到最新
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group flink-dwd-group --reset-offsets --to-latest \
  --topic crypto-ticker-spot --execute
```

### 3. 测试作业启动
```bash
# 编译项目
mvn clean compile -DskipTests

# 启动作业
bash run-flink-dwd-sql.sh

# 查看日志
tail -f logs/app/flink-app.log
```

## 涉及文件

**修改的文件**:
- `src/main/java/com/crypto/dw/flink/factory/KafkaSourceFactory.java`
  - 为 `committed` 模式添加 `OffsetResetStrategy.LATEST` 回退策略
  - 更新日志输出,说明回退行为

**配置文件**:
- `src/main/resources/config/application-dev.yml`
  - `kafka.consumer.startup-mode: committed`

## 相关问题

本次问题与以下问题相关:
1. [202603242304-Kafka配置-为每个Flink作业配置独立Consumer-Group-ID.md](202603242304-Kafka配置-为每个Flink作业配置独立Consumer-Group-ID.md)
2. [202603200140-数据流修复-解决Kafka消费模式导致无新数据问题.md](202603200140-数据流修复-解决Kafka消费模式导致无新数据问题.md)

## 最佳实践

### 1. 始终为 committed 模式指定回退策略
```java
// ❌ 错误 - 没有回退策略
OffsetsInitializer.committedOffsets()

// ✅ 正确 - 有回退策略
OffsetsInitializer.committedOffsets(OffsetResetStrategy.LATEST)
```

### 2. 根据场景选择合适的消费模式
- **开发调试**: `latest` - 快速启动
- **数据回填**: `earliest` - 处理历史数据
- **生产环境**: `committed` - 故障恢复

### 3. 监控 Consumer Lag
```bash
# 定期检查消费延迟
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group flink-dwd-group --describe | grep LAG
```

### 4. 定期备份 Offset
```bash
# 导出 offset
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group flink-dwd-group --describe > offset-backup.txt
```

## 总结

通过为 `committed` 消费模式添加 `OffsetResetStrategy.LATEST` 回退策略,解决了 `NoOffsetForPartitionException` 错误。这个修改确保了:

1. ✅ 有 offset 时从上次位置继续消费（故障恢复）
2. ✅ 无 offset 时从最新数据开始消费（避免重复消费历史数据）
3. ✅ 不会因为找不到 offset 而抛出异常
4. ✅ 适用于所有场景（新 Consumer Group、offset 过期等）

这是一个通用的解决方案,建议在所有使用 `committed` 模式的地方都添加回退策略。

---

**作者**: Kiro AI Assistant  
**日期**: 2026-03-24 23:35  
**类型**: Bug 修复
