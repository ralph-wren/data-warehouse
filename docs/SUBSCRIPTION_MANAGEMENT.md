# 订阅管理指南（Flink 版本）

## 概述

data-warehouse 是一个 Flink ETL 项目，使用 `FlinkDataCollectorJob` 从 OKX WebSocket 采集数据并写入 Kafka。

## 架构说明

```
OKX WebSocket API
    ↓
FlinkDataCollectorJob (Flink Source Function)
    ↓
Kafka Topics
    ├─ crypto-ticker-spot (现货)
    └─ crypto-ticker-swap (合约)
    ↓
    ├─→ Flink ODS/DWD/DWS 作业 (数据仓库)
    └─→ okx-trading (实时交易，可选)
```

## 订阅管理方式

### 方式 1：配置文件管理（推荐）

通过修改配置文件管理订阅的交易对。

#### 配置文件位置

- 开发环境：`src/main/resources/config/application-dev.yml`
- Docker 环境：`src/main/resources/config/application-docker.yml`
- 生产环境：`src/main/resources/config/application-prod.yml`

#### 配置示例

```yaml
# OKX 交易所配置
okx:
  # 订阅的交易对（逗号分隔）
  symbols:
    spot: BTC-USDT,ETH-USDT,SOL-USDT,BNB-USDT,XRP-USDT,LTC-USDT
    swap: BTC-USDT-SWAP,ETH-USDT-SWAP,SOL-USDT-SWAP,BNB-USDT-SWAP
```

#### 添加新交易对

1. 编辑配置文件，添加新的交易对：

```yaml
okx:
  symbols:
    spot: BTC-USDT,ETH-USDT,SOL-USDT,BNB-USDT,DOGE-USDT  # 添加 DOGE-USDT
    swap: BTC-USDT-SWAP,ETH-USDT-SWAP,SOL-USDT-SWAP,BNB-USDT-SWAP,DOGE-USDT-SWAP
```

2. 重新编译项目：

```bash
mvn clean compile
```

3. 重启 Flink 作业：

```bash
# 停止旧作业
# 在 Flink Web UI (http://localhost:8085) 中取消作业
# 或使用命令行
flink cancel <job-id>

# 启动新作业
bash run-flink-collector.sh
```

#### 移除交易对

1. 编辑配置文件，移除不需要的交易对：

```yaml
okx:
  symbols:
    spot: BTC-USDT,ETH-USDT,SOL-USDT  # 移除 BNB-USDT
    swap: BTC-USDT-SWAP,ETH-USDT-SWAP,SOL-USDT-SWAP
```

2. 重新编译并重启作业（同上）

### 方式 2：命令行参数（临时测试）

通过命令行参数指定订阅的交易对，适合临时测试。

```bash
# 订阅指定的交易对
bash run-flink-collector.sh BTC-USDT ETH-USDT SOL-USDT

# 或使用 Maven
mvn exec:java -Dexec.mainClass="com.crypto.dw.flink.FlinkTickerCollectorJob" \
  -Dexec.args="BTC-USDT ETH-USDT SOL-USDT"
```

**注意**：命令行参数优先级高于配置文件。

### 方式 3：StreamPark 管理（生产环境推荐）

使用 StreamPark 平台管理 Flink 作业，支持在线修改配置。

#### 访问 StreamPark

- URL: http://localhost:10000
- 默认账号: admin/streampark

#### 修改订阅配置

1. 登录 StreamPark
2. 进入 "Application" → "Flink Data Collector Job"
3. 点击 "Edit"
4. 在 "Program Args" 中添加交易对：

```
--env docker BTC-USDT ETH-USDT SOL-USDT DOGE-USDT
```

5. 点击 "Save" 并 "Start" 作业

## 查看订阅状态

### 方法 1：查看 Flink 日志

```bash
# 查看 Flink 作业日志
tail -f logs/flink-data-collector.log | grep "Subscribing to symbols"

# 输出示例
2024-03-24 12:34:56 INFO  Subscribing to symbols: [BTC-USDT, ETH-USDT, SOL-USDT, BNB-USDT]
```

### 方法 2：查看 Flink Web UI

1. 访问 http://localhost:8085
2. 查看 "Running Jobs" → "Flink Data Collector Job"
3. 查看 "Source: OKX WebSocket Source" 的 Metrics

### 方法 3：查看 Kafka Topics

```bash
# 查看 Kafka 消息
docker exec -it kafka-okx-trading kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic crypto-ticker-spot \
  --max-messages 10

# 查看不同交易对的消息
docker exec -it kafka-okx-trading kafka-console-consumer.sh \
  --bootstrap-server localhost:9092 \
  --topic crypto-ticker-spot \
  --from-beginning | grep "BTC-USDT"
```

## 监控和统计

### Flink Metrics

Flink 作业会自动推送 Metrics 到 Prometheus：

- **numRecordsIn**: 接收的消息数
- **numRecordsOut**: 发送的消息数
- **numRecordsInPerSecond**: 每秒接收消息数
- **currentInputWatermark**: 当前 Watermark

访问 Grafana 查看监控面板：
- URL: http://localhost:3000
- Dashboard: "Flink 监控"

### Kafka Metrics

查看 Kafka Topic 的消息数量：

```bash
# 查看 Topic 详情
docker exec -it kafka-okx-trading kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --describe \
  --topic crypto-ticker-spot

# 输出示例
Topic: crypto-ticker-spot	PartitionCount: 4	ReplicationFactor: 1
	Topic: crypto-ticker-spot	Partition: 0	Leader: 1	Replicas: 1	Isr: 1
	Topic: crypto-ticker-spot	Partition: 1	Leader: 1	Replicas: 1	Isr: 1
	Topic: crypto-ticker-spot	Partition: 2	Leader: 1	Replicas: 1	Isr: 1
	Topic: crypto-ticker-spot	Partition: 3	Leader: 1	Replicas: 1	Isr: 1
```

## 最佳实践

### 1. 订阅数量

- **开发环境**：3-5 个交易对（BTC, ETH, SOL）
- **生产环境**：10-20 个交易对
- **最大建议**：50 个交易对（避免 WebSocket 连接过载）

### 2. 配置管理

- ✅ 使用配置文件管理订阅（易于版本控制）
- ✅ 区分开发/生产环境配置
- ✅ 定期备份配置文件
- ❌ 避免在代码中硬编码交易对

### 3. 作业重启

- ✅ 使用 Savepoint 重启作业（保留状态）
- ✅ 在低峰期重启作业
- ✅ 验证新配置后再重启
- ❌ 避免频繁重启（影响数据连续性）

### 4. 监控告警

- ✅ 监控 Flink 作业状态
- ✅ 监控 Kafka 消息堆积
- ✅ 监控 WebSocket 连接状态
- ✅ 设置告警规则

## 故障排查

### 问题 1：作业启动失败

**症状**：Flink 作业无法启动

**解决方案**：
```bash
# 1. 检查配置文件
cat src/main/resources/config/application-dev.yml | grep "okx.symbols"

# 2. 检查 Kafka 是否启动
docker ps | grep kafka

# 3. 查看详细日志
tail -f logs/flink-data-collector.log
```

### 问题 2：WebSocket 连接失败

**症状**：日志显示 "WebSocket connection failed"

**解决方案**：
```bash
# 1. 检查网络连接
ping ws.okx.com

# 2. 检查代理设置（如果使用代理）
echo $HTTP_PROXY
echo $HTTPS_PROXY

# 3. 验证交易对格式
# 正确: BTC-USDT, ETH-USDT
# 错误: BTCUSDT, BTC/USDT
```

### 问题 3：数据未写入 Kafka

**症状**：Kafka Topic 中没有数据

**解决方案**：
```bash
# 1. 检查 Flink 作业状态
curl http://localhost:8085/jobs

# 2. 查看 Flink Metrics
curl http://localhost:8085/jobs/<job-id>/metrics

# 3. 查看 Kafka Topic
docker exec -it kafka-okx-trading kafka-topics.sh \
  --bootstrap-server localhost:9092 \
  --list
```

## 与 okx-trading 集成

okx-trading 项目可以从 Kafka 消费 data-warehouse 采集的数据：

### 配置 okx-trading

```properties
# 启用 Kafka 消费者
kafka.consumer.enabled=true

# Kafka 服务器地址
spring.kafka.bootstrap-servers=localhost:9093

# 订阅的 Topics
kafka.consumer.topics=crypto-ticker-spot,crypto-ticker-swap

# 消费起始位置
spring.kafka.consumer.auto-offset-reset=latest
```

### 数据格式

data-warehouse 写入 Kafka 的数据格式：

```json
{
  "instId": "BTC-USDT",
  "last": "50000.5",
  "askPx": "50001.0",
  "askSz": "1.5",
  "bidPx": "49999.5",
  "bidSz": "2.0",
  "open24h": "49500.0",
  "high24h": "50500.0",
  "low24h": "49000.0",
  "volCcy24h": "1000000.0",
  "vol24h": "20.5",
  "ts": "1711234567890"
}
```

okx-trading 会自动转换为内部格式（使用 `TickerDataConverter`）。

## 总结

data-warehouse 使用 Flink 作业管理 WebSocket 订阅，通过配置文件或命令行参数控制订阅的交易对。

**推荐流程**：
1. 修改配置文件添加/移除交易对
2. 重新编译项目
3. 使用 Savepoint 重启 Flink 作业
4. 验证数据流向（Flink → Kafka → Doris）
5. 监控作业状态和数据质量

**核心优势**：
- 🚀 利用 Flink 的容错机制
- 📊 统一的监控和管理
- 🔄 支持 Savepoint 和状态恢复
- 🛡️ 精准一次性语义保证
- 📈 易于扩展和维护
