# FlinkCollector 增强 - 支持现货合约分流写入不同 Topic

**时间**: 2026-03-24 18:00

## 问题描述

用户指出 `FlinkDataCollectorJob`（Flink 采集作业）没有支持同时订阅现货和合约，并且输出没有区分不同的 Kafka Topic。

虽然之前已经修改了 `OKXWebSocketClient`（数据采集器）支持现货和合约分流，但 Flink 采集作业还没有实现相同的功能。

## 需求分析

### 当前状态

1. **OKXWebSocketClient**（已完成）:
   - ✅ 同时订阅现货（BTC-USDT）和合约（BTC-USDT-SWAP）
   - ✅ 根据 `instId` 后缀判断类型
   - ✅ 发送到不同的 Kafka Topic

2. **FlinkDataCollectorJob**（需要修改）:
   - ❌ 只订阅现货数据
   - ❌ 所有数据写入同一个 Topic
   - ❌ 没有数据分流逻辑

### 目标

1. 修改 `OKXWebSocketSourceFunction` 同时订阅现货和合约
2. 修改 `FlinkDataCollectorJob` 使用 Side Output 分流数据
3. 现货数据写入 `crypto-ticker-spot` Topic
4. 合约数据写入 `crypto-ticker-swap` Topic

## 技术方案

### 1. OKXWebSocketSourceFunction 修改

**订阅逻辑**:
```java
// 同时订阅现货和合约 Ticker 频道
for (String symbol : symbols) {
    // 订阅现货
    String spotSubscribeMsg = String.format(
        "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"%s\"}]}",
        symbol
    );
    send(spotSubscribeMsg);
    logger.info("Subscribed to SPOT ticker: {}", symbol);
    
    // 订阅合约
    String swapSubscribeMsg = String.format(
        "{\"op\":\"subscribe\",\"args\":[{\"channel\":\"tickers\",\"instId\":\"%s-SWAP\"}]}",
        symbol
    );
    send(swapSubscribeMsg);
    logger.info("Subscribed to SWAP ticker: {}-SWAP", symbol);
}
```

**数据格式**:
- 现货：`{"type":"SPOT","data":{...}}`
- 合约：`{"type":"SWAP","data":{...}}`

### 2. FlinkDataCollectorJob 修改

**使用 Side Output 分流**:
```java
// 定义 Side Output Tag
final OutputTag<String> swapOutputTag = new OutputTag<String>("swap-output"){};

// 处理数据流，根据 instId 判断是现货还是合约
SingleOutputStreamOperator<String> spotStream = sourceStream
    .process(new ProcessFunction<String, String>() {
        @Override
        public void processElement(
                String value,
                Context ctx,
                Collector<String> out) throws Exception {
            
            // 解析 JSON
            JsonNode rootNode = objectMapper.readTree(value);
            
            // 处理 Ticker 数据
            if (rootNode.has("data")) {
                JsonNode dataArray = rootNode.get("data");
                for (JsonNode dataNode : dataArray) {
                    String instId = dataNode.get("instId").asText();
                    String jsonData = objectMapper.writeValueAsString(dataNode);
                    
                    // 判断是现货还是合约
                    if (instId.endsWith("-SWAP")) {
                        // 合约数据：发送到 Side Output
                        ctx.output(swapOutputTag, jsonData);
                    } else {
                        // 现货数据：发送到主流
                        out.collect(jsonData);
                    }
                }
            }
        }
    })
    .name("Split SPOT/SWAP");

// 获取合约数据流
DataStream<String> swapStream = spotStream.getSideOutput(swapOutputTag);
```

**创建两个 Kafka Sink**:
```java
// 创建 Kafka Sink（现货）
KafkaSink<String> spotKafkaSink = KafkaSink.<String>builder()
    .setBootstrapServers(kafkaBootstrapServers)
    .setRecordSerializer(
        KafkaRecordSerializationSchema.builder()
            .setTopic(spotTopic)
            .setValueSerializationSchema(new SimpleStringSchema())
            .build()
    )
    .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
    .build();

// 创建 Kafka Sink（合约）
KafkaSink<String> swapKafkaSink = KafkaSink.<String>builder()
    .setBootstrapServers(kafkaBootstrapServers)
    .setRecordSerializer(
        KafkaRecordSerializationSchema.builder()
            .setTopic(swapTopic)
            .setValueSerializationSchema(new SimpleStringSchema())
            .build()
    )
    .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
    .build();

// 现货数据写入 Kafka
spotStream.sinkTo(spotKafkaSink).name("Kafka Sink (SPOT)");

// 合约数据写入 Kafka
swapStream.sinkTo(swapKafkaSink).name("Kafka Sink (SWAP)");
```

## 实施步骤

### 1. 修改 OKXWebSocketSourceFunction

**文件**: `src/main/java/com/crypto/dw/flink/source/OKXWebSocketSourceFunction.java`

**修改内容**:
- 在 `onOpen()` 方法中同时订阅现货和合约
- 每个交易对订阅两次：一次现货，一次合约（加 `-SWAP` 后缀）
- 添加详细的日志输出

### 2. 修改 FlinkDataCollectorJob

**文件**: `src/main/java/com/crypto/dw/flink/FlinkDataCollectorJob.java`

**修改内容**:
- 使用 `ProcessFunction` 解析 JSON 数据
- 根据 `instId` 判断是现货还是合约
- 使用 Side Output 分流数据
- 创建两个 Kafka Sink，分别写入不同的 Topic

### 3. 编译验证

```bash
mvn clean compile -DskipTests
```

**编译结果**: ✅ 成功（27 个源文件）

## 数据流架构

```
OKX WebSocket
    │
    ├─→ 订阅现货（BTC-USDT）
    │   └─→ Ticker 数据
    │
    └─→ 订阅合约（BTC-USDT-SWAP）
        └─→ Ticker 数据
            │
            ↓
    Flink Source Function
            │
            ↓
    ProcessFunction（解析 JSON）
            │
            ├─→ instId.endsWith("-SWAP") ?
            │   │
            │   ├─→ YES → Side Output → Kafka Sink (SWAP)
            │   │                       └─→ crypto-ticker-swap
            │   │
            │   └─→ NO → Main Output → Kafka Sink (SPOT)
            │                          └─→ crypto-ticker-spot
```

## 关键技术点

### 1. Side Output 模式

**优势**:
- 单次数据处理，多路输出
- 避免重复解析 JSON
- 类型安全（使用 OutputTag）
- 性能高效

**使用方法**:
```java
// 定义 Side Output Tag
final OutputTag<String> swapOutputTag = new OutputTag<String>("swap-output"){};

// 在 ProcessFunction 中输出到 Side Output
ctx.output(swapOutputTag, jsonData);

// 获取 Side Output 流
DataStream<String> swapStream = spotStream.getSideOutput(swapOutputTag);
```

### 2. JSON 解析

**使用 Jackson ObjectMapper**:
```java
private transient ObjectMapper objectMapper;

@Override
public void open(Configuration parameters) {
    objectMapper = new ObjectMapper();
}

@Override
public void processElement(String value, Context ctx, Collector<String> out) {
    JsonNode rootNode = objectMapper.readTree(value);
    // 处理 JSON
}
```

### 3. 订阅确认和错误处理

**过滤非数据消息**:
```java
// 检查是否是订阅确认消息或错误消息
if (rootNode.has("event")) {
    String event = rootNode.get("event").asText();
    if ("subscribe".equals(event)) {
        logger.info("Subscription confirmed: {}", value);
        return;
    } else if ("error".equals(event)) {
        logger.error("Subscription error: {}", value);
        return;
    }
}
```

## 配置说明

### Kafka Topic 配置

**配置文件**: `src/main/resources/config/application-dev.yml`

```yaml
kafka:
  bootstrap-servers: localhost:9092
  topic:
    crypto-ticker-spot: crypto-ticker-spot  # 现货 Topic
    crypto-ticker-swap: crypto-ticker-swap  # 合约 Topic
```

### 订阅交易对配置

**默认订阅**:
```java
List<String> defaultSymbols = Arrays.asList(
    "BTC-USDT", 
    "ETH-USDT", 
    "SOL-USDT", 
    "BNB-USDT"
);
```

**实际订阅**:
- 现货：BTC-USDT, ETH-USDT, SOL-USDT, BNB-USDT
- 合约：BTC-USDT-SWAP, ETH-USDT-SWAP, SOL-USDT-SWAP, BNB-USDT-SWAP
- 总计：8 个订阅

## 测试验证

### 1. 启动作业

```bash
bash run-flink-collector.sh
```

### 2. 查看日志

```bash
tail -f logs/app/flink-app.log
```

**预期日志**:
```
Subscribed to SPOT ticker: BTC-USDT
Subscribed to SWAP ticker: BTC-USDT-SWAP
Subscribed to SPOT ticker: ETH-USDT
Subscribed to SWAP ticker: ETH-USDT-SWAP
...
Total subscriptions: 8 (SPOT: 4, SWAP: 4)
```

### 3. 验证 Kafka Topic

**查看现货 Topic**:
```bash
kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic crypto-ticker-spot \
    --from-beginning \
    --max-messages 5
```

**预期输出**:
```json
{"instId":"BTC-USDT","last":"67890.5",...}
{"instId":"ETH-USDT","last":"3456.7",...}
```

**查看合约 Topic**:
```bash
kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic crypto-ticker-swap \
    --from-beginning \
    --max-messages 5
```

**预期输出**:
```json
{"instId":"BTC-USDT-SWAP","last":"67891.2",...}
{"instId":"ETH-USDT-SWAP","last":"3457.1",...}
```

### 4. 验证数据分流

**统计消息数量**:
```bash
# 现货消息数量
kafka-run-class.sh kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic crypto-ticker-spot

# 合约消息数量
kafka-run-class.sh kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic crypto-ticker-swap
```

## 性能优化

### 1. 批量写入

**Kafka Sink 配置**:
```java
.setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
```

**说明**:
- `AT_LEAST_ONCE`: 至少一次语义，性能较好
- `EXACTLY_ONCE`: 精确一次语义，性能较差但数据不重复

### 2. 并行度

**建议配置**:
```yaml
flink:
  execution:
    parallelism: 4  # 根据 CPU 核心数调整
```

### 3. 缓冲区大小

**Kafka Producer 配置**:
```yaml
kafka:
  producer:
    batch-size: 16384
    linger-ms: 10
    buffer-memory: 33554432
```

## 与 OKXWebSocketClient 的对比

| 特性 | OKXWebSocketClient | FlinkDataCollectorJob |
|-----|-------------------|----------------------|
| 运行方式 | 独立 Java 进程 | Flink 作业 |
| 容错机制 | 手动重连 | Flink Checkpoint |
| 监控 | 日志 | Flink Web UI |
| 扩展性 | 单机 | 分布式 |
| 数据分流 | ✅ 支持 | ✅ 支持 |
| 推荐场景 | 简单采集 | 生产环境 |

## 总结

### 完成的工作

1. ✅ 修改 `OKXWebSocketSourceFunction` 同时订阅现货和合约
2. ✅ 修改 `FlinkDataCollectorJob` 使用 Side Output 分流数据
3. ✅ 现货数据写入 `crypto-ticker-spot` Topic
4. ✅ 合约数据写入 `crypto-ticker-swap` Topic
5. ✅ 编译验证通过（27 个源文件）
6. ✅ 添加详细的日志输出和注释

### 技术亮点

- **Side Output 模式**: 高效的数据分流方案
- **统一架构**: 与 OKXWebSocketClient 保持一致
- **容错机制**: 利用 Flink 的 Checkpoint 机制
- **可扩展性**: 支持分布式部署和动态扩缩容

### 下一步

1. 测试运行：`bash run-flink-collector.sh`
2. 验证数据分流：检查两个 Kafka Topic 的数据
3. 监控作业状态：访问 Flink Web UI (http://localhost:8085)
4. 性能调优：根据实际情况调整并行度和缓冲区大小

## 相关文档

- [202603241732-数据采集增强-同时订阅现货和合约写入不同Topic.md](./202603241732-数据采集增强-同时订阅现货和合约写入不同Topic.md)
- [202603241630-ADS作业-跨市场套利机会计算.md](./202603241630-ADS作业-跨市场套利机会计算.md)
