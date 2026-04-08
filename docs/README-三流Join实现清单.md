# FlinkADSArbitrageJob 三流 Join 实现清单

## 📋 实现清单

### ✅ 已完成

#### 1. 设计文档 (3个)
- ✅ [014-三流Join完整架构设计.md](./014-20260408-三流Join完整架构设计.md)
- ✅ [015-三流Join完整代码实现.md](./015-20260408-三流Join完整代码实现.md)
- ✅ [016-三流Join完整方案总结.md](./016-20260408-三流Join完整方案总结.md)

#### 2. 数据库脚本
- ✅ [create_arbitrage_trade_tables.sql](../sql/create_arbitrage_trade_tables.sql)
  - dwd_arbitrage_trades (交易明细表)
  - ads_position_status (持仓状态表)
  - ads_arbitrage_stats (交易统计表)

#### 3. 核心组件 (已有)
- ✅ OKXOrderWebSocketSource.java - 订单 WebSocket 数据源
- ✅ OKXTradingService.java - 交易服务
- ✅ PositionState.java - 持仓状态
- ✅ TradeRecord.java - 交易记录

### 🔨 待实现

#### 1. 修改 FlinkADSArbitrageJob.java

需要在 main 方法中添加以下代码(在现有双流 Join 之后):

```java
// ========== 步骤 7: 创建订单流 ==========
logger.info("创建订单 WebSocket 流...");

DataStream<OrderUpdate> orderStream = env
    .addSource(new OKXOrderWebSocketSource(config))
    .map(new OrderUpdateParser())
    .name("Parse Order Update");

logger.info("✓ 订单流创建成功");

// ========== 步骤 8: Connect 套利机会流和订单流 ==========
logger.info("配置三流 Join（套利机会 + 订单流）...");

DataStream<TradeRecord> tradeStream = filteredStream
    .keyBy(opp -> opp.symbol)
    .connect(orderStream.keyBy(order -> order.symbol))
    .process(new TradingDecisionProcessor(config))
    .name("Trading Decision");

logger.info("✓ 三流 Join 配置成功");

// ========== 步骤 9: 输出交易明细到 Doris ==========
DataStream<String> tradeJsonStream = tradeStream
    .map(record -> {
        ObjectNode json = OBJECT_MAPPER.createObjectNode();
        json.put("symbol", record.symbol);
        json.put("action", record.action);
        json.put("direction", record.direction);
        json.put("amount", record.amount.toString());
        json.put("spot_price", record.spotPrice.toString());
        json.put("swap_price", record.swapPrice.toString());
        json.put("spread_rate", record.spreadRate != null ? record.spreadRate.toString() : "0");
        json.put("profit", record.profit != null ? record.profit.toString() : "0");
        json.put("close_reason", record.closeReason != null ? record.closeReason : "");
        json.put("hold_time_ms", record.holdTimeMs);
        json.put("timestamp", record.timestamp);
        return json.toString();
    })
    .name("Trade To JSON");

// 写入 Doris 交易明细表
DorisSink<String> tradeSink = dorisSinkFactory.createDorisSink(
    "crypto_dw",
    "dwd_arbitrage_trades",
    "ads-arbitrage-trades"
);

tradeJsonStream.sinkTo(tradeSink).name("Doris Trade Sink");

logger.info("✓ Doris Trade Sink 创建成功");
logger.info("  Database: crypto_dw");
logger.info("  Table: dwd_arbitrage_trades");
```

#### 2. 添加新的数据类

在 FlinkADSArbitrageJob.java 文件末尾添加:

```java
/**
 * 订单更新
 */
public static class OrderUpdate {
    public String orderId;
    public String symbol;
    public String instType;
    public String side;
    public String state;
    public BigDecimal fillPrice;
    public BigDecimal fillSize;
    public long timestamp;
}

/**
 * 待确认订单
 */
public static class PendingOrder implements java.io.Serializable {
    private static final long serialVersionUID = 1L;
    
    public String symbol;
    public String action;
    public String spotOrderId;
    public String swapOrderId;
    public boolean spotFilled;
    public boolean swapFilled;
    public BigDecimal spotFillPrice;
    public BigDecimal swapFillPrice;
    public long createTime;
}
```

#### 3. 添加 OrderUpdateParser

```java
/**
 * 订单更新解析器
 */
public static class OrderUpdateParser extends RichMapFunction<String, OrderUpdate> {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Override
    public OrderUpdate map(String json) throws Exception {
        JsonNode rootNode = OBJECT_MAPPER.readTree(json);
        
        if (!rootNode.has("data")) {
            return null;
        }
        
        JsonNode dataArray = rootNode.get("data");
        if (!dataArray.isArray() || dataArray.size() == 0) {
            return null;
        }
        
        JsonNode orderNode = dataArray.get(0);
        
        OrderUpdate order = new OrderUpdate();
        order.orderId = orderNode.get("ordId").asText();
        order.symbol = extractSymbol(orderNode.get("instId").asText());
        order.instType = orderNode.get("instType").asText();
        order.side = orderNode.get("side").asText();
        order.state = orderNode.get("state").asText();
        
        if (orderNode.has("fillPx")) {
            order.fillPrice = new BigDecimal(orderNode.get("fillPx").asText());
        }
        if (orderNode.has("fillSz")) {
            order.fillSize = new BigDecimal(orderNode.get("fillSz").asText());
        }
        
        order.timestamp = System.currentTimeMillis();
        
        return order;
    }
    
    private String extractSymbol(String instId) {
        if (instId.endsWith("-SWAP")) {
            return instId.substring(0, instId.length() - 5);
        }
        return instId;
    }
}
```

#### 4. 添加 TradingDecisionProcessor

完整代码见: [015-三流Join完整代码实现.md](./015-20260408-三流Join完整代码实现.md) 第5步

这是核心组件,包含:
- 开仓决策逻辑
- 平仓决策逻辑
- 订单确认逻辑
- 状态管理逻辑

## 🚀 实现步骤

### 步骤 1: 创建数据库表

```bash
# 连接到 Doris
mysql -h 127.0.0.1 -P 9030 -u root

# 执行 SQL 脚本
source data-warehouse/sql/create_arbitrage_trade_tables.sql
```

### 步骤 2: 修改代码

按照上面的清单,在 `FlinkADSArbitrageJob.java` 中添加:
1. 订单流创建
2. 三流 Join
3. 新的数据类
4. OrderUpdateParser
5. TradingDecisionProcessor

### 步骤 3: 配置环境变量

```bash
export OKX_API_KEY="your-api-key"
export OKX_SECRET_KEY="your-secret-key"
export OKX_PASSPHRASE="your-passphrase"
```

### 步骤 4: 编译测试

```bash
cd data-warehouse
mvn clean compile -DskipTests
```

### 步骤 5: 启动作业

```bash
bash run-flink-ads-arbitrage.sh
```

## 📊 验证

### 1. 检查日志

```bash
tail -f logs/flink-ads-arbitrage.log
```

应该看到:
- ✓ 现货价格流创建成功
- ✓ 合约价格流创建成功
- ✓ Interval Join 配置成功
- ✓ 订单流创建成功
- ✓ 三流 Join 配置成功
- ✓ Doris Trade Sink 创建成功

### 2. 检查 Doris 数据

```sql
-- 查询交易明细
SELECT * FROM crypto_dw.dwd_arbitrage_trades ORDER BY timestamp DESC LIMIT 10;

-- 查询当前持仓
SELECT * FROM crypto_dw.ads_position_status WHERE is_open = true;
```

### 3. 监控指标

- WebSocket 连接状态
- 订单消息接收速率
- 开仓/平仓次数
- 盈亏情况

## 📚 参考文档

### 核心文档
1. [014-三流Join完整架构设计.md](./014-20260408-三流Join完整架构设计.md) - 架构设计
2. [015-三流Join完整代码实现.md](./015-20260408-三流Join完整代码实现.md) - 代码实现 ⭐
3. [016-三流Join完整方案总结.md](./016-20260408-三流Join完整方案总结.md) - 方案总结

### 基础文档
- [012-套利作业-最终完成总结.md](./012-20260408-套利作业-最终完成总结.md)
- [013-套利作业-编译错误修复.md](./013-20260408-套利作业-编译错误修复.md)

## ⚠️ 注意事项

1. **安全**: 不要提交 API 密钥,使用环境变量
2. **测试**: 先在模拟环境测试,小金额测试
3. **监控**: 密切关注日志和持仓状态
4. **风险**: 设置止损和最大持仓时间

## 🎯 下一步

1. ✅ 按照清单修改代码
2. ✅ 创建数据库表
3. ✅ 配置环境变量
4. ✅ 编译测试
5. ✅ 启动作业
6. ✅ 监控验证

---

**文档时间**: 2026-04-08  
**作者**: Kiro AI Assistant  
**状态**: 实现清单完成,可以开始编码
