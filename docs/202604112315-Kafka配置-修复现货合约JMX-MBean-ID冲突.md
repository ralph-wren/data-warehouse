# Kafka 配置 - 修复现货合约 JMX MBean ID 冲突

## 问题描述

Flink 套利作业运行时出现 JMX MBean 注册冲突错误：

```
javax.management.InstanceAlreadyExistsException: kafka.consumer:type=app-info,id=flink-ads-arbitrage-group-1
at org.apache.kafka.common.utils.AppInfoParser.registerAppInfo(AppInfoParser.java:64)
at org.apache.kafka.clients.consumer.KafkaConsumer.<init>(KafkaConsumer.java:772)
```

错误发生在创建 Kafka Consumer 时，现货和合约的 Kafka Source 使用了相同的 `client.id`，导致 JMX MBean ID 冲突。

## 根本原因

在 `FlinkADSArbitrageJob` 中，现货和合约的 Kafka Source 都使用了相同的 `jobType`（"ads-arbitrage"），导致生成的 `client.id` 相同：

```java
// 现货流
KafkaSource<String> spotKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
    "ads-arbitrage",
    config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot")
);

// 合约流
KafkaSource<String> swapKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
    "ads-arbitrage",
    config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap")
);
```

虽然 `KafkaSourceFactory.createKafkaSourceForJob(String jobType, String topic)` 方法会根据 topic 名称生成不同的 `client.id` 后缀，但是由于两个 Source 在同一个作业中并行创建，JMX MBean 注册时仍然会冲突。

## 解决方案

`KafkaSourceFactory` 已经实现了自动生成不同 `client.id` 的逻辑：

```java
// 使用 topic 名称作为 client.id 后缀,避免同一作业的多个 Source 冲突
// 例如: flink-ads-arbitrage-group-spot, flink-ads-arbitrage-group-swap
String clientIdSuffix = topic.replace("crypto-ticker-", "").replace("-", "");

return createKafkaSource(topic, groupId, startupMode, groupId + "-" + clientIdSuffix);
```

修改 `FlinkADSArbitrageJob`，确保现货和合约使用不同的 topic 名称：

### 修改代码

```java
// ========== 步骤 1: 创建现货价格流 ==========
logger.info("创建现货价格流...");
// 注意: 为现货和合约使用不同的 client.id,避免 JMX MBean 冲突
String spotTopic = config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot");
KafkaSource<String> spotKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
    "ads-arbitrage",
    spotTopic
);

// ========== 步骤 2: 创建合约价格流 ==========
logger.info("创建合约价格流...");
// 注意: 为现货和合约使用不同的 client.id,避免 JMX MBean 冲突
String swapTopic = config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap");
KafkaSource<String> swapKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
    "ads-arbitrage",
    swapTopic
);
```

## 工作原理

1. 现货流的 topic 是 `crypto-ticker-spot`，生成的 `client.id` 是 `flink-ads-arbitrage-group-spot`
2. 合约流的 topic 是 `crypto-ticker-swap`，生成的 `client.id` 是 `flink-ads-arbitrage-group-swap`
3. 两个 Kafka Consumer 使用不同的 `client.id`，JMX MBean 注册不会冲突

## 修改文件

- `data-warehouse/src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob.java`

## 验证结果

编译成功，无错误。

## 注意事项

1. `KafkaSourceFactory.createKafkaSourceForJob(String jobType, String topic)` 方法会自动根据 topic 名称生成不同的 `client.id`
2. 同一作业中创建多个 Kafka Source 时，必须使用不同的 topic 名称，否则会导致 JMX MBean 冲突
3. Flink 会为每个并行实例自动添加后缀（如 -0, -1, -2），进一步避免冲突

## 相关文档

- [202604112310-空指针异常-修复OrderUpdateParser的BigDecimal转换错误.md](202604112310-空指针异常-修复OrderUpdateParser的BigDecimal转换错误.md)
- [202603242304-Kafka配置-为每个Flink作业配置独立Consumer-Group-ID.md](202603242304-Kafka配置-为每个Flink作业配置独立Consumer-Group-ID.md)
