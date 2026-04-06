# OKX K线周期格式指南

## 问题说明

在使用 OKX WebSocket API 订阅 K线数据时，遇到错误：

```
Wrong URL or channel:candle1D,instId:ETH-USDT doesn't exist
```

## OKX 支持的 K线周期

根据 OKX 官方文档和实际测试，支持的 K线周期格式如下：

### 分钟级别
- `1m` - 1分钟
- `3m` - 3分钟
- `5m` - 5分钟
- `15m` - 15分钟
- `30m` - 30分钟

### 小时级别
- `1H` - 1小时
- `2H` - 2小时
- `4H` - 4小时
- `6H` - 6小时
- `12H` - 12小时

### 日/周/月级别
- `1D` 或 `1Dutc` - 1天（UTC时区）
- `1W` 或 `1Wutc` - 1周（UTC时区）
- `1M` 或 `1Mutc` - 1月（UTC时区）
- `3M` 或 `3Mutc` - 3月（UTC时区）

## 订阅格式

### WebSocket 订阅消息格式

```json
{
  "op": "subscribe",
  "args": [{
    "channel": "candle1H",
    "instId": "BTC-USDT"
  }]
}
```

### Channel 名称格式

Channel 名称 = `candle` + 周期

示例：
- 1小时 K线: `candle1H`
- 4小时 K线: `candle4H`
- 1天 K线: `candle1D` 或 `candle1Dutc`

## Redis 订阅格式

在 Redis 中存储订阅时，使用格式：`{交易对}:{周期}`

### 正确格式示例

```bash
# 分钟级别
redis-cli SADD kline:subscriptions "BTC-USDT:1m"
redis-cli SADD kline:subscriptions "ETH-USDT:5m"
redis-cli SADD kline:subscriptions "SOL-USDT:15m"

# 小时级别
redis-cli SADD kline:subscriptions "BTC-USDT:1H"
redis-cli SADD kline:subscriptions "ETH-USDT:4H"
redis-cli SADD kline:subscriptions "BNB-USDT:12H"

# 日/周/月级别
redis-cli SADD kline:subscriptions "BTC-USDT:1D"
redis-cli SADD kline:subscriptions "ETH-USDT:1W"
redis-cli SADD kline:subscriptions "SOL-USDT:1M"
```

### 错误格式示例

```bash
# ❌ 错误：使用小写 h
redis-cli SADD kline:subscriptions "BTC-USDT:1h"

# ❌ 错误：使用小写 d
redis-cli SADD kline:subscriptions "BTC-USDT:1d"

# ❌ 错误：缺少周期
redis-cli SADD kline:subscriptions "BTC-USDT"

# ❌ 错误：格式不对
redis-cli SADD kline:subscriptions "BTC-USDT-1H"
```

## 常见问题

### Q1: 为什么 1D 订阅失败？

**可能原因**：
1. 使用了小写 `1d` 而不是大写 `1D`
2. OKX API 可能要求使用 `1Dutc` 而不是 `1D`
3. WebSocket 端点不支持该周期

**解决方案**：
```bash
# 尝试使用 UTC 后缀
redis-cli SADD kline:subscriptions "BTC-USDT:1Dutc"

# 或使用小时级别替代
redis-cli SADD kline:subscriptions "BTC-USDT:4H"
```

### Q2: 如何验证订阅格式是否正确？

**方法 1：查看日志**
```bash
tail -f logs/crypto-dw.log | grep "Subscription"
```

成功订阅会显示：
```
✓ Subscription confirmed: {"event":"subscribe","arg":{"channel":"candle4H","instId":"BTC-USDT"}}
```

失败订阅会显示：
```
❌ Subscription error [code=60018]: Wrong URL or channel...
```

**方法 2：使用 OKX 官方测试工具**

访问 OKX WebSocket 测试页面，手动测试订阅：
https://www.okx.com/docs-v5/en/#websocket-api-public-channel-candlesticks-channel

### Q3: 哪些周期最常用？

**短线交易**：
- 1m, 5m, 15m

**日内交易**：
- 15m, 30m, 1H

**波段交易**：
- 4H, 1D

**长线投资**：
- 1D, 1W

## 推荐配置

### 多周期监控配置

```bash
# 短期 + 中期 + 长期
redis-cli SADD kline:subscriptions \
  "BTC-USDT:5m" \
  "BTC-USDT:1H" \
  "BTC-USDT:4H"
```

### 多币种配置

```bash
# 主流币种 4小时 K线
redis-cli SADD kline:subscriptions \
  "BTC-USDT:4H" \
  "ETH-USDT:4H" \
  "SOL-USDT:4H" \
  "BNB-USDT:4H"
```

## 调试技巧

### 1. 启用详细日志

修改 `log4j2.properties`:
```properties
logger.okx.name = com.crypto.dw.flink.source.OKXKlineWebSocketSourceFunction
logger.okx.level = DEBUG
```

### 2. 测试单个订阅

```bash
# 清空所有订阅
redis-cli DEL kline:subscriptions

# 添加单个测试订阅
redis-cli SADD kline:subscriptions "BTC-USDT:1H"

# 启动作业并观察日志
bash run-flink-kline-collector.sh
```

### 3. 使用 WebSocket 客户端测试

使用 `wscat` 工具直接测试 OKX WebSocket：

```bash
# 安装 wscat
npm install -g wscat

# 连接到 OKX WebSocket
wscat -c wss://ws.okx.com:8443/ws/v5/public

# 发送订阅消息
{"op":"subscribe","args":[{"channel":"candle1H","instId":"BTC-USDT"}]}
```

## 参考文档

- [OKX WebSocket API 文档](https://www.okx.com/docs-v5/en/#websocket-api-public-channel-candlesticks-channel)
- [OKX K线数据说明](https://www.okx.com/docs-v5/en/#rest-api-market-data-get-candlesticks)
- [K线采集器使用指南](KLINE_COLLECTOR_GUIDE.md)
- [Redis 配置指南](KLINE_REDIS_SETUP.md)

## 更新日志

### 2024-04-06
- 添加详细的错误日志
- 改进订阅确认消息显示
- 添加周期格式验证建议

---

**注意**: 如果遇到订阅错误，请先检查：
1. 周期格式是否正确（大小写）
2. 交易对是否存在于 OKX
3. WebSocket 连接是否正常
4. Redis 订阅数据是否正确
