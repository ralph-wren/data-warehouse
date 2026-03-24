# Kafka 配置 - 为每个 Flink 作业配置独立 Consumer Group ID

## 需求描述

为每个 Flink 作业配置不同的 Kafka Consumer Group ID,实现以下目标:

1. **独立消费进度管理**: 每个作业独立管理自己的消费进度
2. **避免相互影响**: 不同作业不会相互干扰消费 offset
3. **灵活的重启策略**: 可以独立重启某个作业而不影响其他作业
4. **便于监控和调试**: 可以清晰地看到每个作业的消费情况

## 问题分析

### 原有问题

之前所有 Flink 作业使用相同的 Consumer Group ID:
- 多个作业共享同一个 Consumer Group
- 消费进度相互影响
- 重启一个作业会影响其他作业的消费
- 难以独立管理和监控

### 解决思路

1. 在配置文件中为每个作业定义独立的 Consumer Group ID
2. 更新 KafkaSourceFactory 支持按作业类型读取 Group ID
3. 更新所有 Flink 作业使用新的 API
4. 保持向后兼容性

## 实施方案

### 1. 配置文件更新

在 `application-dev.yml` 和 `application-docker.yml` 中添加 Group ID 配置:

```yaml
kafka:
  # Consumer 配置
  consumer:
    auto-offset-reset: latest
    enable-auto-commit: false
    max-poll-records: 500
    startup-mode: earliest
    
    # 每个 Flink 作业使用不同的 Consumer Group ID
    # 这样可以独立管理每个作业的消费进度，避免相互影响
    group-id:
      collector: flink-collector-group        # 数据采集作业
      ods-datastream: flink-ods-datastream-group  # ODS DataStream 作业
      ods-sql: flink-ods-sql-group            # ODS SQL 作业
      dwd: flink-dwd-group                    # DWD 作业
      dws-1min: flink-dws-1min-group          # DWS 1分钟聚合作业
      ads-metrics: flink-ads-metrics-group    # ADS 实时指标作业
      ads-arbitrage: flink-ads-arbitrage-group  # ADS 套利计算作业
      ads-monitor: flink-ads-monitor-group    # ADS 市场监控作业
```

### 2. KafkaSourceFactory 增强

添加新的方法支持按作业类型创建 Kafka Source:

```java
/**
 * 创建 Kafka Source - 指定作业类型（推荐使用）
 * 
 * 从配置文件读取对应作业的 Consumer Group ID:
 * - collector: kafka.consumer.group-id.collector
 * - ods-datastream: kafka.consumer.group-id.ods-datastream
 * - ods-sql: kafka.consumer.group-id.ods-sql
 * - dwd: kafka.consumer.group-id.dwd
 * - dws-1min: kafka.consumer.group-id.dws-1min
 * - ads-metrics: kafka.consumer.group-id.ads-metrics
 * - ads-arbitrage: kafka.consumer.group-id.ads-arbitrage
 * - ads-monitor: kafka.consumer.group-id.ads-monitor
 * 
 * @param jobType 作业类型
 * @return 配置好的 KafkaSource
 */
public KafkaSource<String> createKafkaSourceForJob(String jobType) {
    String topic = config.getString("kafka.topic.crypto-ticker-spot");
    String groupIdKey = "kafka.consumer.group-id." + jobType;
    String groupId = config.getString(groupIdKey, "flink-" + jobType + "-group");
    String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
    
    return createKafkaSource(topic, groupId, startupMode);
}

/**
 * 创建 Kafka Source - 指定作业类型和 Topic（最灵活）
 * 
 * @param jobType 作业类型
 * @param topic Topic 名称
 * @return 配置好的 KafkaSource
 */
public KafkaSource<String> createKafkaSourceForJob(String jobType, String topic) {
    String groupIdKey = "kafka.consumer.group-id." + jobType;
    String groupId = config.getString(groupIdKey, "flink-" + jobType + "-group");
    String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
    
    return createKafkaSource(topic, groupId, startupMode);
}
```

### 3. 更新 Flink 作业

#### FlinkODSJobDataStream.java

```java
// 使用工厂类创建 Kafka Source（减少重复代码）
// 优化说明：统一 Kafka Source 创建逻辑，便于维护和复用
// 重要：使用作业专属的 Consumer Group ID，避免与其他作业冲突
KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);
KafkaSource<String> kafkaSource = kafkaSourceFactory.createKafkaSourceForJob("ods-datastream");
```

#### FlinkADSMarketMonitorJob.java

```java
// 使用工厂类创建 Kafka Source
// 重要：使用作业专属的 Consumer Group ID，避免与其他作业冲突
KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);
KafkaSource<String> kafkaSource = kafkaSourceFactory.createKafkaSourceForJob("ads-monitor");
```

#### FlinkADSArbitrageJob.java

```java
// 使用工厂类创建 Kafka Source
// 重要：套利作业需要同时读取现货和合约数据，使用不同的 Consumer Group
KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);

// 创建现货价格流
KafkaSource<String> spotKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
    "ads-arbitrage",  // 作业类型
    config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot")  // 现货 Topic
);

// 创建合约价格流
KafkaSource<String> swapKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
    "ads-arbitrage",  // 作业类型（与现货使用相同的 Group ID）
    config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap")  // 合约 Topic
);
```

## Consumer Group ID 命名规范

### 命名格式

```
flink-{job-type}-group
```

### 作业类型映射

| 作业类型 | Consumer Group ID | 说明 |
|---------|-------------------|------|
| collector | flink-collector-group | 数据采集作业 |
| ods-datastream | flink-ods-datastream-group | ODS DataStream 作业 |
| ods-sql | flink-ods-sql-group | ODS SQL 作业 |
| dwd | flink-dwd-group | DWD 作业 |
| dws-1min | flink-dws-1min-group | DWS 1分钟聚合作业 |
| ads-metrics | flink-ads-metrics-group | ADS 实时指标作业 |
| ads-arbitrage | flink-ads-arbitrage-group | ADS 套利计算作业 |
| ads-monitor | flink-ads-monitor-group | ADS 市场监控作业 |

### 命名优势

1. **清晰的作业标识**: 从 Group ID 可以直接看出是哪个作业
2. **统一的命名规范**: 所有作业遵循相同的命名格式
3. **便于监控**: Kafka 监控工具可以清晰地显示每个作业的消费情况
4. **便于调试**: 出现问题时可以快速定位到具体作业

## 使用示例

### 示例 1: ODS 作业

```java
// 创建 Kafka Source
KafkaSourceFactory factory = new KafkaSourceFactory(config);
KafkaSource<String> source = factory.createKafkaSourceForJob("ods-datastream");

// 实际使用的 Consumer Group ID: flink-ods-datastream-group
```

### 示例 2: DWD 作业

```java
// 创建 Kafka Source
KafkaSourceFactory factory = new KafkaSourceFactory(config);
KafkaSource<String> source = factory.createKafkaSourceForJob("dwd");

// 实际使用的 Consumer Group ID: flink-dwd-group
```

### 示例 3: 套利作业（多 Topic）

```java
// 创建 Kafka Source Factory
KafkaSourceFactory factory = new KafkaSourceFactory(config);

// 现货数据流
KafkaSource<String> spotSource = factory.createKafkaSourceForJob(
    "ads-arbitrage",
    "crypto-ticker-spot"
);

// 合约数据流
KafkaSource<String> swapSource = factory.createKafkaSourceForJob(
    "ads-arbitrage",
    "crypto-ticker-swap"
);

// 两个流使用相同的 Consumer Group ID: flink-ads-arbitrage-group
// 这样可以统一管理套利作业的消费进度
```

## 技术要点

### 1. Consumer Group 隔离

每个作业使用独立的 Consumer Group ID:
- 消费进度独立管理
- 不会相互影响
- 可以独立重启

### 2. 向后兼容

保留原有的 API,支持自定义 Group ID:

```java
// 方式 1: 使用作业类型（推荐）
KafkaSource<String> source1 = factory.createKafkaSourceForJob("dwd");

// 方式 2: 自定义 Group ID（兼容旧代码）
KafkaSource<String> source2 = factory.createKafkaSource("my-custom-group");

// 方式 3: 完全自定义（最灵活）
KafkaSource<String> source3 = factory.createKafkaSource(
    "my-topic",
    "my-group",
    "earliest"
);
```

### 3. 配置优先级

1. 配置文件中的 `kafka.consumer.group-id.{job-type}`
2. 默认值: `flink-{job-type}-group`

### 4. 监控和调试

查看 Consumer Group 消费情况:

```bash
# 查看所有 Consumer Group
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# 查看特定 Consumer Group 的消费情况
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group flink-dwd-group --describe

# 重置 Consumer Group 的 offset
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group flink-dwd-group --reset-offsets --to-earliest \
    --topic crypto-ticker-spot --execute
```

## 验证结果

### 编译验证

```bash
mvn clean compile -DskipTests
# 结果: BUILD SUCCESS ✅
```

### 配置验证

启动作业后,可以在日志中看到:

```
==========================================
创建 Kafka Source (DataStream API)
==========================================
Kafka Source 配置:
  Bootstrap Servers: localhost:9092
  Topic: crypto-ticker-spot
  Consumer Group: flink-ods-datastream-group
  Startup Mode: earliest（从最早数据开始）
==========================================
```

### Kafka 验证

```bash
# 查看所有 Consumer Group
kafka-consumer-groups.sh --bootstrap-server localhost:9092 --list

# 预期输出:
flink-collector-group
flink-ods-datastream-group
flink-ods-sql-group
flink-dwd-group
flink-dws-1min-group
flink-ads-metrics-group
flink-ads-arbitrage-group
flink-ads-monitor-group
```

## 最佳实践

### 1. 统一命名规范

所有 Consumer Group ID 遵循 `flink-{job-type}-group` 格式:
- 便于识别和管理
- 便于监控和调试
- 便于团队协作

### 2. 配置文件管理

在配置文件中集中管理所有 Consumer Group ID:
- 便于修改和维护
- 避免硬编码
- 支持不同环境配置

### 3. 独立消费进度

每个作业独立管理消费进度:
- 可以独立重启
- 可以独立回溯数据
- 不会相互影响

### 4. 监控告警

为每个 Consumer Group 配置监控告警:
- 消费延迟告警
- 消费速率告警
- 消费异常告警

## 注意事项

### 1. Consumer Group 数量

- 每个作业使用一个 Consumer Group
- 不要让多个作业共享同一个 Consumer Group
- Consumer Group 数量不要超过 Topic 分区数

### 2. 消费模式

配置文件中的 `startup-mode` 影响首次启动时的消费位置:
- `earliest`: 从最早的数据开始（适合处理历史数据）
- `latest`: 从最新的数据开始（适合实时处理）
- `committed`: 从上次提交的 offset 开始（适合故障恢复）

### 3. Offset 管理

Flink 会自动管理 Consumer Group 的 offset:
- Checkpoint 成功时提交 offset
- 故障恢复时从上次提交的 offset 开始
- 不需要手动管理 offset

### 4. 重置 Offset

如果需要重新消费数据,可以重置 offset:

```bash
# 停止作业
flink cancel <job-id>

# 重置 offset 到最早
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
    --group flink-dwd-group --reset-offsets --to-earliest \
    --topic crypto-ticker-spot --execute

# 重新启动作业
bash run-flink-dwd-sql.sh
```

## 修改文件

- `src/main/resources/config/application-dev.yml` - 添加 Consumer Group ID 配置
- `src/main/resources/config/application-docker.yml` - 添加 Consumer Group ID 配置
- `src/main/java/com/crypto/dw/flink/factory/KafkaSourceFactory.java` - 添加按作业类型创建 Source 的方法
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - 使用新 API
- `src/main/java/com/crypto/dw/flink/FlinkADSMarketMonitorJob.java` - 使用新 API
- `src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob.java` - 使用新 API

## 总结

通过为每个 Flink 作业配置独立的 Consumer Group ID,实现了:

1. ✅ 独立的消费进度管理
2. ✅ 避免作业之间相互影响
3. ✅ 灵活的重启和回溯策略
4. ✅ 清晰的监控和调试
5. ✅ 统一的命名规范
6. ✅ 便于维护和扩展

这是 Kafka 消费者的最佳实践,建议所有 Flink 作业都遵循这个规范!
