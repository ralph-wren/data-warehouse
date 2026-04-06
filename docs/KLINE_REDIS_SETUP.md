# K线订阅 Redis 配置指南

## 快速开始

### 1. 启动 Redis

```bash
# Docker 方式（推荐）
docker run -d --name redis -p 6379:6379 redis:latest

# 或本地安装
redis-server
```

### 2. 添加初始订阅

```bash
# 添加主流币种的 4小时 K线
redis-cli SADD kline:subscriptions "BTC-USDT:4H"
redis-cli SADD kline:subscriptions "ETH-USDT:4H"
redis-cli SADD kline:subscriptions "SOL-USDT:4H"
redis-cli SADD kline:subscriptions "BNB-USDT:4H"

# 验证订阅
redis-cli SMEMBERS kline:subscriptions
```

### 3. 启动 Flink 作业

```bash
cd data-warehouse
bash run-flink-kline-collector.sh
```

## 订阅格式说明

格式: `{交易对}:{K线周期}`

### 交易对格式

- 现货: `BTC-USDT`, `ETH-USDT`, `SOL-USDT`
- 合约: `BTC-USDT-SWAP`, `ETH-USDT-SWAP`

### K线周期

| 周期 | 说明 | 示例 |
|------|------|------|
| 1m | 1分钟 | `BTC-USDT:1m` |
| 3m | 3分钟 | `BTC-USDT:3m` |
| 5m | 5分钟 | `BTC-USDT:5m` |
| 15m | 15分钟 | `BTC-USDT:15m` |
| 30m | 30分钟 | `BTC-USDT:30m` |
| 1H | 1小时 | `BTC-USDT:1H` |
| 2H | 2小时 | `BTC-USDT:2H` |
| 4H | 4小时 | `BTC-USDT:4H` |
| 6H | 6小时 | `BTC-USDT:6H` |
| 12H | 12小时 | `BTC-USDT:12H` |
| 1D | 1天 | `BTC-USDT:1D` |
| 1W | 1周 | `BTC-USDT:1W` |
| 1M | 1月 | `BTC-USDT:1M` |
| 3M | 3月 | `BTC-USDT:3M` |

## Redis 命令参考

### 添加订阅

```bash
# 单个添加
redis-cli SADD kline:subscriptions "BTC-USDT:4H"

# 批量添加
redis-cli SADD kline:subscriptions \
  "BTC-USDT:4H" \
  "ETH-USDT:1H" \
  "SOL-USDT:15m"
```

### 查看订阅

```bash
# 查看所有订阅
redis-cli SMEMBERS kline:subscriptions

# 查看订阅数量
redis-cli SCARD kline:subscriptions

# 检查是否存在
redis-cli SISMEMBER kline:subscriptions "BTC-USDT:4H"
```

### 删除订阅

```bash
# 删除单个
redis-cli SREM kline:subscriptions "BTC-USDT:4H"

# 删除多个
redis-cli SREM kline:subscriptions "BTC-USDT:4H" "ETH-USDT:1H"

# 清空所有
redis-cli DEL kline:subscriptions
```

## 常用订阅配置

### 短线交易（1分钟 + 5分钟）

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:1m" "BTC-USDT:5m" \
  "ETH-USDT:1m" "ETH-USDT:5m" \
  "SOL-USDT:1m" "SOL-USDT:5m"
```

### 中线交易（15分钟 + 1小时）

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:15m" "BTC-USDT:1H" \
  "ETH-USDT:15m" "ETH-USDT:1H" \
  "SOL-USDT:15m" "SOL-USDT:1H"
```

### 长线交易（4小时 + 1天）

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:4H" "BTC-USDT:1D" \
  "ETH-USDT:4H" "ETH-USDT:1D" \
  "SOL-USDT:4H" "SOL-USDT:1D"
```

### 全周期监控

```bash
redis-cli SADD kline:subscriptions \
  "BTC-USDT:1m" "BTC-USDT:5m" "BTC-USDT:15m" \
  "BTC-USDT:1H" "BTC-USDT:4H" "BTC-USDT:1D"
```

## 批量管理脚本

### 从文件批量添加

创建 `subscriptions.txt`:
```
BTC-USDT:4H
ETH-USDT:4H
SOL-USDT:4H
BNB-USDT:4H
XRP-USDT:4H
```

执行脚本:
```bash
cat subscriptions.txt | while read line; do
  redis-cli SADD kline:subscriptions "$line"
done
```

### 批量删除脚本

```bash
# 删除所有 1分钟 K线订阅
redis-cli SMEMBERS kline:subscriptions | grep ":1m" | while read line; do
  redis-cli SREM kline:subscriptions "$line"
done
```

### 导出订阅配置

```bash
# 导出到文件
redis-cli SMEMBERS kline:subscriptions > subscriptions_backup.txt

# 从备份恢复
cat subscriptions_backup.txt | while read line; do
  redis-cli SADD kline:subscriptions "$line"
done
```

## 动态订阅示例

### 场景 1: 添加新币种

```bash
# 作业运行中，添加新订阅
redis-cli SADD kline:subscriptions "DOGE-USDT:4H"

# 等待 5 分钟（默认刷新间隔）
# 或查看日志确认订阅成功
tail -f logs/crypto-dw.log | grep "Added subscription"
```

### 场景 2: 切换周期

```bash
# 从 1小时 切换到 4小时
redis-cli SREM kline:subscriptions "BTC-USDT:1H"
redis-cli SADD kline:subscriptions "BTC-USDT:4H"
```

### 场景 3: 临时监控

```bash
# 添加临时监控
redis-cli SADD kline:subscriptions "PEPE-USDT:1m"

# 监控一段时间后删除
redis-cli SREM kline:subscriptions "PEPE-USDT:1m"
```

## 监控和验证

### 验证订阅生效

```bash
# 1. 查看 Redis 订阅
redis-cli SMEMBERS kline:subscriptions

# 2. 查看 Flink 日志
tail -f logs/crypto-dw.log | grep "subscription"

# 3. 查看 Kafka 数据
kafka-console-consumer.sh \
  --bootstrap-server localhost:9093 \
  --topic okx-kline-data \
  --from-beginning
```

### 统计订阅信息

```bash
# 订阅总数
redis-cli SCARD kline:subscriptions

# 按周期统计
echo "1m: $(redis-cli SMEMBERS kline:subscriptions | grep ':1m' | wc -l)"
echo "5m: $(redis-cli SMEMBERS kline:subscriptions | grep ':5m' | wc -l)"
echo "15m: $(redis-cli SMEMBERS kline:subscriptions | grep ':15m' | wc -l)"
echo "1H: $(redis-cli SMEMBERS kline:subscriptions | grep ':1H' | wc -l)"
echo "4H: $(redis-cli SMEMBERS kline:subscriptions | grep ':4H' | wc -l)"
echo "1D: $(redis-cli SMEMBERS kline:subscriptions | grep ':1D' | wc -l)"
```

## 故障排查

### Redis 连接问题

```bash
# 测试连接
redis-cli ping

# 查看 Redis 配置
redis-cli CONFIG GET bind
redis-cli CONFIG GET protected-mode

# 如果需要远程访问
redis-cli CONFIG SET protected-mode no
redis-cli CONFIG SET bind "0.0.0.0"
```

### 订阅格式错误

```bash
# 错误格式示例
redis-cli SADD kline:subscriptions "BTC-USDT"  # ❌ 缺少周期
redis-cli SADD kline:subscriptions "BTC:4H"    # ❌ 交易对格式错误
redis-cli SADD kline:subscriptions "BTC-USDT:5H"  # ❌ 不支持的周期

# 正确格式
redis-cli SADD kline:subscriptions "BTC-USDT:4H"  # ✓
```

### 清理无效订阅

```bash
# 查看所有订阅
redis-cli SMEMBERS kline:subscriptions

# 手动删除无效订阅
redis-cli SREM kline:subscriptions "INVALID-SUBSCRIPTION"

# 或清空重新添加
redis-cli DEL kline:subscriptions
# 然后重新添加有效订阅
```

## 性能建议

### 订阅数量

- 开发环境: 5-10 个订阅
- 测试环境: 20-30 个订阅
- 生产环境: 50-100 个订阅（根据服务器性能调整）

### 刷新间隔

- 频繁变动: 1-2 分钟
- 正常使用: 5 分钟（默认）
- 稳定运行: 10-15 分钟

### 周期选择

- 高频交易: 1m, 3m, 5m
- 日内交易: 15m, 30m, 1H
- 波段交易: 4H, 1D
- 长线投资: 1D, 1W, 1M

## 相关文档

- [K线采集器使用指南](KLINE_COLLECTOR_GUIDE.md)
- [OKX K线数据格式](https://www.okx.com/docs-v5/en/#websocket-api-public-channel-candlesticks-channel)
- [Redis Set 命令](https://redis.io/commands#set)
