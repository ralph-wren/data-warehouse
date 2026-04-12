# WebSocket Ticker 订阅频道修复

## 问题描述

在运行 `FlinkDataCollectorJob` 时，订阅 OKX WebSocket Ticker 频道时出现以下错误：

```
ERROR com.crypto.dw.jobs.FlinkTickerCollectorJob - Subscription error: 
{"event":"error","msg":"Wrong URL or channel:tickers,instId:BTC-USDT doesn't exist. 
Please use the correct URL, channel and parameters referring to API document.","code":"60018","connId":"d5e6891c"}
```

## 问题原因

项目中有两个不同的 Flink 作业，订阅不同类型的数据：

1. **FlinkDataCollectorJob** - 订阅 Ticker 数据（行情快照）
2. **FlinkKlineCollectorJob** - 订阅 K线数据（蜡烛图）

但配置文件中只有一个 `okx.websocket.url` 配置项，导致两个作业共用同一个 WebSocket URL。

原配置：
```yaml
okx:
  websocket:
    url: wss://ws.okx.com:8443/ws/v5/business  # 错误：Ticker 不能使用 business 频道
```

根据 [OKX 官方文档](https://www.okx.com/docs-v5/en/)，WebSocket 频道分为三类：

1. **Public 频道** (`/ws/v5/public`)：用于公共市场数据
   - **tickers**（行情快照）✅ FlinkDataCollectorJob 需要
   - books（订单簿）
   - trades（成交记录）
   - 等

2. **Private 频道** (`/ws/v5/private`)：用于私有账户数据
   - account（账户信息）
   - positions（持仓信息）
   - orders（订单信息）
   - 等

3. **Business 频道** (`/ws/v5/business`)：用于特定业务数据
   - **candles**（K线数据）✅ FlinkKlineCollectorJob 需要
   - 从 2023年6月20日起，K线数据迁移到此频道
   - 等

## 解决方案

### 1. 修改配置文件，分别配置两个 URL

修改所有环境的配置文件（`application-dev.yml`、`application-docker.yml`、`application-prod.yml`）：

```yaml
okx:
  websocket:
    # Ticker 数据使用 public 频道
    ticker-url: wss://ws.okx.com:8443/ws/v5/public
    # K线数据使用 business 频道
    kline-url: wss://ws.okx.com:8443/ws/v5/business
    # 兼容旧配置：默认使用 public 频道
    url: wss://ws.okx.com:8443/ws/v5/public
    reconnect:
      max-retries: 10
      initial-delay: 1000
      max-delay: 60000
```

### 2. 修改 Ticker Source Function

修改 `OKXWebSocketSourceFunction.java`，使用 `ticker-url`：

```java
private void connectWebSocket(SourceContext<String> ctx) throws Exception {
    closeWebSocketQuietly();

    // Ticker 数据使用 public 频道
    String wsUrl = config.getString("okx.websocket.ticker-url", 
            config.getString("okx.websocket.url", "wss://ws.okx.com:8443/ws/v5/public"));
    wsClient = new OKXWebSocketClientInternal(wsUrl, ctx, symbols);
    wsClient.connectBlocking();
}
```

### 3. 修改 K线 Source Function

修改 `OKXKlineWebSocketSourceFunction.java`，使用 `kline-url`：

```java
private void connectWebSocket(SourceContext<String> ctx) throws Exception {
    closeWebSocketQuietly();

    // K线数据使用 business 频道
    String wsUrl = config.getString("okx.websocket.kline-url", 
            config.getString("okx.websocket.url", "wss://ws.okx.com:8443/ws/v5/business"));
    wsClient = new OKXKlineWebSocketClientInternal(wsUrl, ctx);
    wsClient.connectBlocking();
}
```

## 订阅格式说明

### Ticker 订阅格式（Public 频道）

```json
{
  "op": "subscribe",
  "args": [{
    "channel": "tickers",
    "instId": "BTC-USDT"
  }]
}
```

### K线订阅格式（Business 频道）

```json
{
  "op": "subscribe",
  "args": [{
    "channel": "candle4H",
    "instId": "BTC-USDT"
  }]
}
```

## 修改文件清单

### 配置文件
- ✅ `data-warehouse/src/main/resources/config/application-dev.yml`
- ✅ `data-warehouse/src/main/resources/config/application-docker.yml`
- ⚠️ `data-warehouse/src/main/resources/config/application-prod.yml`（未配置 WebSocket URL，会继承默认配置）

### 源代码
- ✅ `data-warehouse/src/main/java/com/crypto/dw/flink/source/OKXWebSocketSourceFunction.java`
- ✅ `data-warehouse/src/main/java/com/crypto/dw/flink/source/OKXKlineWebSocketSourceFunction.java`

## 测试验证

### 测试 Ticker 数据采集

```bash
cd data-warehouse
bash run-flink-collector.sh
```

预期日志输出：

```
INFO  com.crypto.dw.flink.source.OKXWebSocketSourceFunction - WebSocket connection opened
INFO  com.crypto.dw.flink.source.OKXWebSocketSourceFunction - Subscribed to SPOT ticker: BTC-USDT
INFO  com.crypto.dw.flink.source.OKXWebSocketSourceFunction - Subscribed to SWAP ticker: BTC-USDT-SWAP
INFO  com.crypto.dw.jobs.FlinkTickerCollectorJob - Subscription confirmed: {"event":"subscribe",...}
```

### 测试 K线数据采集

```bash
cd data-warehouse
bash run-flink-kline-collector.sh
```

预期日志输出：

```
INFO  com.crypto.dw.flink.source.OKXKlineWebSocketSourceFunction - WebSocket 连接已建立
INFO  com.crypto.dw.flink.source.OKXKlineWebSocketSourceFunction - 订阅K线数据，交易对: BTC-USDT, 间隔: 4H
INFO  com.crypto.dw.jobs.FlinkKlineCollectorJob - 订阅确认: {"event":"subscribe",...}
```

## 配置说明

### 配置优先级

代码中使用了配置回退机制，优先级从高到低：

1. `okx.websocket.ticker-url` / `okx.websocket.kline-url`（新配置，推荐）
2. `okx.websocket.url`（兼容旧配置）
3. 硬编码默认值（最后的保底）

### 兼容性

- 如果只配置了 `okx.websocket.url`，两个作业都会使用这个 URL
- 如果配置了 `ticker-url` 和 `kline-url`，各自使用对应的 URL
- 建议明确配置 `ticker-url` 和 `kline-url`，避免混淆

## 参考资料

1. [OKX WebSocket API 官方文档](https://www.okx.com/docs-v5/en/)
2. [OKX API v5 升级指南](https://www.okx.com/learn/complete-guide-to-okex-api-v5-upgrade)
3. [OKX WebSocket 频道变更说明](https://www.okx.com/help/changes-to-v5-api-websocket-subscription-parameter-and-url)

## 注意事项

1. **Ticker 数据必须使用 Public 频道**，否则会返回 "channel doesn't exist" 错误
2. **K线数据必须使用 Business 频道**（2023年6月20日后），否则无法订阅
3. 不同的频道有不同的订阅限制和速率限制
4. 确保订阅的交易对在 OKX 上存在，否则会返回错误
5. 两个作业可以同时运行，互不影响

---

**修复时间**：2026-04-07  
**修复人员**：Kiro AI Assistant  
**影响范围**：data-warehouse 项目的 WebSocket 数据采集功能（Ticker 和 K线）
