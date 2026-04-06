# K线采集器快速开始

## 5分钟快速启动

### 步骤 1: 启动 Redis

```bash
docker run -d --name redis -p 6379:6379 redis:latest
```

### 步骤 2: 添加订阅

```bash
# 添加 BTC 4小时 K线
redis-cli SADD kline:subscriptions "BTC-USDT:4H"

# 添加 ETH 1小时 K线
redis-cli SADD kline:subscriptions "ETH-USDT:1H"

# 验证订阅
redis-cli SMEMBERS kline:subscriptions
```

### 步骤 3: 启动 Flink 作业

```bash
cd data-warehouse
bash run-flink-kline-collector.sh
```

### 步骤 4: 查看 Web UI

打开浏览器: http://localhost:8086

### 步骤 5: 验证数据

```bash
# 查看 Kafka 数据
kafka-console-consumer.sh \
  --bootstrap-server localhost:9093 \
  --topic okx-kline-data \
  --from-beginning
```

## 常用命令

### Redis 订阅管理

```bash
# 添加订阅
redis-cli SADD kline:subscriptions "BTC-USDT:4H"

# 查看所有订阅
redis-cli SMEMBERS kline:subscriptions

# 删除订阅
redis-cli SREM kline:subscriptions "BTC-USDT:4H"

# 查看订阅数量
redis-cli SCARD kline:subscriptions
```

### 日志查看

```bash
# 实时查看日志
tail -f logs/crypto-dw.log

# 搜索订阅相关日志
grep "subscription" logs/crypto-dw.log

# 搜索错误日志
grep ERROR logs/crypto-dw.log
```

### Kafka 验证

```bash
# 查看 Topic 列表
kafka-topics.sh --bootstrap-server localhost:9093 --list

# 查看 Topic 详情
kafka-topics.sh --bootstrap-server localhost:9093 \
  --describe --topic okx-kline-data

# 消费数据
kafka-console-consumer.sh \
  --bootstrap-server localhost:9093 \
  --topic okx-kline-data \
  --from-beginning
```

## 支持的 K线周期

| 周期 | 说明 | 示例 |
|------|------|------|
| 1m, 3m, 5m, 15m, 30m | 分钟级 | `BTC-USDT:1m` |
| 1H, 2H, 4H, 6H, 12H | 小时级 | `BTC-USDT:4H` |
| 1D, 1W, 1M, 3M | 日/周/月级 | `BTC-USDT:1D` |

## 常见问题

### Q1: Redis 连接失败

```bash
# 检查 Redis 是否运行
redis-cli ping

# 如果返回 PONG 则正常
```

### Q2: 订阅未生效

等待 5 分钟（默认刷新间隔），或查看日志:

```bash
tail -f logs/crypto-dw.log | grep "Added subscription"
```

### Q3: Kafka 写入失败

```bash
# 检查 Kafka 是否运行
kafka-broker-api-versions.sh --bootstrap-server localhost:9093

# 创建 Topic（如果不存在）
kafka-topics.sh --bootstrap-server localhost:9093 \
  --create --topic okx-kline-data \
  --partitions 4 --replication-factor 1
```

## 推荐订阅配置

### 短线交易

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:1m" "BTC-USDT:5m" \
  "ETH-USDT:1m" "ETH-USDT:5m"
```

### 中线交易

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:15m" "BTC-USDT:1H" \
  "ETH-USDT:15m" "ETH-USDT:1H"
```

### 长线交易

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:4H" "BTC-USDT:1D" \
  "ETH-USDT:4H" "ETH-USDT:1D"
```

## 更多文档

- [完整使用指南](KLINE_COLLECTOR_GUIDE.md)
- [Redis 配置指南](KLINE_REDIS_SETUP.md)
- [实现总结](KLINE_IMPLEMENTATION_SUMMARY.md)

## 技术支持

- 日志: `logs/crypto-dw.log`
- Web UI: http://localhost:8086
- 项目文档: `README.md`
