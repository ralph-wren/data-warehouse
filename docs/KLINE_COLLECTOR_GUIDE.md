# Flink K线数据采集器使用指南

## 概述

Flink K线数据采集器是一个实时数据采集作业，从 Redis 动态获取订阅配置，订阅 OKX K线数据并发送到 Kafka。

## 核心特性

- **动态订阅管理**: 从 Redis Set `kline:subscriptions` 读取订阅配置
- **自动刷新**: 定期（默认 5 分钟）检查 Redis 订阅变化
- **动态添加/取消**: 无需重启作业即可添加或取消订阅
- **多周期支持**: 支持 OKX 所有 K线周期（1m, 3m, 5m, 15m, 30m, 1H, 2H, 4H, 6H, 12H, 1D, 1W, 1M, 3M）
- **实时数据**: 订阅 OKX candle channel，获取实时 K线数据
- **Kafka 输出**: 写入 `okx-kline-data` topic

## 架构设计

```
Redis (kline:subscriptions)
    │
    ├─ BTC-USDT:4H
    ├─ ETH-USDT:1H
    └─ SOL-USDT:15m
    │
    ▼
OKX WebSocket (candle channel)
    │
    ├─ candle4H-BTC-USDT
    ├─ candle1H-ETH-USDT
    └─ candle15m-SOL-USDT
    │
    ▼
Kafka Topic: okx-kline-data
```

## 快速开始

### 1. 启动 Redis

```bash
# 使用 Docker 启动 Redis
docker run -d --name redis -p 6379:6379 redis:latest

# 或使用本地 Redis
redis-server
```

### 2. 添加订阅

```bash
# 添加 BTC 4小时 K线订阅
redis-cli SADD kline:subscriptions "BTC-USDT:4H"

# 添加 ETH 1小时 K线订阅
redis-cli SADD kline:subscriptions "ETH-USDT:1H"

# 添加 SOL 15分钟 K线订阅
redis-cli SADD kline:subscriptions "SOL-USDT:15m"

# 查看所有订阅
redis-cli SMEMBERS kline:subscriptions
```

### 3. 启动 Flink 作业

```bash
# 启动 K线采集器
bash run-flink-kline-collector.sh
```

### 4. 访问 Web UI

打开浏览器访问: http://localhost:8086

## 订阅格式

订阅格式: `{SYMBOL}:{INTERVAL}`

### 支持的交易对

- 现货: `BTC-USDT`, `ETH-USDT`, `SOL-USDT`, `BNB-USDT`, 等
- 合约: `BTC-USDT-SWAP`, `ETH-USDT-SWAP`, 等

### 支持的 K线周期

| 周期 | 说明 | 示例 |
|------|------|------|
| 1m, 3m, 5m, 15m, 30m | 分钟级 | `BTC-USDT:1m` |
| 1H, 2H, 4H, 6H, 12H | 小时级 | `BTC-USDT:4H` |
| 1D | 日级 | `BTC-USDT:1D` |
| 1W | 周级 | `BTC-USDT:1W` |
| 1M, 3M | 月级 | `BTC-USDT:1M` |

## Redis 订阅管理

### 添加订阅

```bash
# 单个添加
redis-cli SADD kline:subscriptions "BTC-USDT:4H"

# 批量添加
redis-cli SADD kline:subscriptions \
  "BTC-USDT:4H" \
  "ETH-USDT:1H" \
  "SOL-USDT:15m" \
  "BNB-USDT:1D"
```

### 查看订阅

```bash
# 查看所有订阅
redis-cli SMEMBERS kline:subscriptions

# 查看订阅数量
redis-cli SCARD kline:subscriptions

# 检查是否存在某个订阅
redis-cli SISMEMBER kline:subscriptions "BTC-USDT:4H"
```

### 删除订阅

```bash
# 删除单个订阅
redis-cli SREM kline:subscriptions "BTC-USDT:4H"

# 删除多个订阅
redis-cli SREM kline:subscriptions "BTC-USDT:4H" "ETH-USDT:1H"

# 清空所有订阅
redis-cli DEL kline:subscriptions
```

## 配置说明

### application-dev.yml

```yaml
# Redis 配置
redis:
  host: localhost
  port: 6379
  timeout: 2000
  password:  # 如果有密码则填写

# K线数据采集配置
kline:
  kafka:
    topic: okx-kline-data  # Kafka Topic
  
  subscription:
    redis-key: kline:subscriptions  # Redis Key
    refresh-interval-seconds: 300  # 刷新间隔（秒）
```

### 配置项说明

| 配置项 | 说明 | 默认值 |
|--------|------|--------|
| `redis.host` | Redis 主机地址 | localhost |
| `redis.port` | Redis 端口 | 6379 |
| `kline.kafka.topic` | Kafka Topic | okx-kline-data |
| `kline.subscription.refresh-interval-seconds` | 订阅刷新间隔（秒） | 300 (5分钟) |

## 数据格式

### OKX K线数据格式

```json
{
  "arg": {
    "channel": "candle4H",
    "instId": "BTC-USDT"
  },
  "data": [
    {
      "ts": "1609459200000",
      "o": "29000.0",
      "h": "29500.0",
      "l": "28800.0",
      "c": "29200.0",
      "vol": "1234.5678",
      "volCcy": "35800000.0",
      "volCcyQuote": "35800000.0",
      "confirm": "0"
    }
  ]
}
```

### 字段说明

| 字段 | 说明 |
|------|------|
| `ts` | K线开始时间（毫秒时间戳） |
| `o` | 开盘价 |
| `h` | 最高价 |
| `l` | 最低价 |
| `c` | 收盘价 |
| `vol` | 交易量（币） |
| `volCcy` | 交易量（计价币） |
| `confirm` | K线状态（0=未完成，1=已完成） |

## 监控和日志

### 查看日志

```bash
# 实时查看日志
tail -f logs/crypto-dw.log

# 搜索错误日志
grep ERROR logs/crypto-dw.log

# 搜索订阅相关日志
grep "subscription" logs/crypto-dw.log
```

### 统计信息

作业每分钟打印一次统计信息:

```
========================================
Statistics (1 minute interval):
  WebSocket Status: Connected
  Messages Received: 1234
  WebSocket Errors: 0
  Subscription Refreshes: 2
  Current Subscriptions: 5
  Subscriptions: [BTC-USDT:4H, ETH-USDT:1H, SOL-USDT:15m, BNB-USDT:1D, XRP-USDT:1H]
========================================
```

### Kafka 消费验证

```bash
# 查看 Kafka Topic
kafka-topics.sh --bootstrap-server localhost:9093 --list

# 消费 K线数据
kafka-console-consumer.sh \
  --bootstrap-server localhost:9093 \
  --topic okx-kline-data \
  --from-beginning
```

## 常见问题

### 1. Redis 连接失败

**问题**: `Failed to fetch subscriptions from Redis`

**解决方案**:
```bash
# 检查 Redis 是否运行
redis-cli ping

# 检查 Redis 配置
redis-cli CONFIG GET bind
redis-cli CONFIG GET protected-mode

# 如果需要，修改配置
redis-cli CONFIG SET protected-mode no
```

### 2. 订阅未生效

**问题**: 添加订阅后没有收到数据

**解决方案**:
- 检查订阅格式是否正确（格式: `SYMBOL:INTERVAL`）
- 等待刷新间隔（默认 5 分钟）
- 查看日志确认订阅是否成功

### 3. WebSocket 连接断开

**问题**: `WebSocket disconnected`

**解决方案**:
- 作业会自动重连（最多 10 次）
- 检查网络连接
- 查看日志了解断开原因

### 4. Kafka 写入失败

**问题**: `Failed to send message to Kafka`

**解决方案**:
```bash
# 检查 Kafka 是否运行
kafka-broker-api-versions.sh --bootstrap-server localhost:9093

# 检查 Topic 是否存在
kafka-topics.sh --bootstrap-server localhost:9093 --describe --topic okx-kline-data

# 如果不存在，创建 Topic
kafka-topics.sh --bootstrap-server localhost:9093 \
  --create --topic okx-kline-data \
  --partitions 4 --replication-factor 1
```

## 最佳实践

### 1. 订阅管理

- 使用 Redis Set 管理订阅，避免重复
- 定期清理不需要的订阅
- 根据业务需求选择合适的 K线周期

### 2. 性能优化

- 避免订阅过多交易对（建议 < 50）
- 使用较长的刷新间隔（5-10 分钟）
- 监控 WebSocket 消息速率

### 3. 监控告警

- 监控 WebSocket 连接状态
- 监控订阅刷新成功率
- 监控 Kafka 写入延迟

### 4. 数据质量

- 验证 K线数据完整性
- 检查时间戳连续性
- 过滤异常数据

## 进阶使用

### 1. 批量订阅管理

```bash
# 从文件批量添加订阅
cat subscriptions.txt | while read line; do
  redis-cli SADD kline:subscriptions "$line"
done

# subscriptions.txt 内容示例:
# BTC-USDT:4H
# ETH-USDT:1H
# SOL-USDT:15m
```

### 2. 动态调整刷新间隔

修改 `application-dev.yml`:

```yaml
kline:
  subscription:
    refresh-interval-seconds: 60  # 改为 1 分钟
```

### 3. 多环境配置

```bash
# 开发环境
export APP_ENV=dev
bash run-flink-kline-collector.sh

# 生产环境
export APP_ENV=prod
bash run-flink-kline-collector.sh
```

## 相关文档

- [OKX WebSocket API 文档](https://www.okx.com/docs-v5/en/#websocket-api-public-channel-candlesticks-channel)
- [Flink 官方文档](https://flink.apache.org/)
- [Redis 命令参考](https://redis.io/commands)
- [Kafka 文档](https://kafka.apache.org/documentation/)

## 技术支持

如有问题，请查看:
- 日志文件: `logs/crypto-dw.log`
- Web UI: http://localhost:8086
- 项目文档: `README.md`
