# FlinkADSArbitrageJob 订单流集成补丁

## 说明

由于完整集成代码较长,这里提供需要在 `FlinkADSArbitrageJob.java` 中添加的代码片段。

## 位置

在 `logger.info("✓ 广播 Join 配置成功");` 之后,`// ========== 步骤 6: 转换为 JSON 并写入 Doris ==========` 之前添加以下代码:

## 代码片段

```java
        // ========== 步骤 6: 创建订单流(可选,用于自动交易)==========
        // 说明:只有启用自动交易时才需要订单流
        boolean tradingEnabled = config.getBoolean("arbitrage.trading.enabled", false);
        
        if (tradingEnabled) {
            logger.info("创建订单 WebSocket 流...");
            
            DataStream<String> orderStream = env
                .addSource(new OKXOrderWebSocketSource(config))
                .name("OKX Order WebSocket Source");
            
            logger.info("✓ 订单流创建成功");
            
            // ========== 步骤 7: 订单匹配处理 ==========
            logger.info("配置订单匹配处理器...");
            
            DataStream<PositionState> confirmedPositionStream = orderStream
                .keyBy(message -> {
                    // 从订单消息中提取交易对作为 key
                    try {
                        JsonNode rootNode = OBJECT_MAPPER.readTree(message);
                        if (rootNode.has("data") && rootNode.get("data").isArray()) {
                            JsonNode order = rootNode.get("data").get(0);
                            String instId = order.get("instId").asText();
                            return instId.replace("-SWAP", "");  // 统一为交易对
                        }
                    } catch (Exception e) {
                        logger.error("解析订单消息失败: {}", e.getMessage());
                    }
                    return "UNKNOWN";
                })
                .process(new OrderMatchProcessor())
                .name("Order Match Processor");
            
            logger.info("✓ 订单匹配处理器配置成功");
            
            // ========== 步骤 8: 套利交易处理 ==========
            logger.info("配置套利交易处理器...");
            
            // 将套利机会流和确认持仓流合并
            DataStream<Object> tradingInputStream = filteredStream
                .map(opp -> (Object) opp)
                .union(confirmedPositionStream.map(pos -> (Object) pos));
            
            DataStream<TradeRecord> tradeStream = tradingInputStream
                .keyBy(obj -> {
                    if (obj instanceof ArbitrageOpportunity) {
                        return ((ArbitrageOpportunity) obj).symbol;
                    } else if (obj instanceof PositionState) {
                        return ((PositionState) obj).getSymbol();
                    }
                    return "UNKNOWN";
                })
                .process(new ArbitrageTraderV2(config))
                .name("Arbitrage Trader V2");
            
            logger.info("✓ 套利交易处理器配置成功");
            
            // ========== 步骤 9: 交易记录写入 Doris ==========
            DataStream<String> tradeJsonStream = tradeStream
                .map(record -> {
                    ObjectNode json = OBJECT_MAPPER.createObjectNode();
                    json.put("symbol", record.symbol);
                    json.put("action", record.action);
                    json.put("direction", record.direction);
                    json.put("amount", record.amount != null ? record.amount.toString() : "0");
                    json.put("spread_rate", record.spreadRate != null ? record.spreadRate.toString() : "0");
                    json.put("spot_price", record.spotPrice != null ? record.spotPrice.toString() : "0");
                    json.put("swap_price", record.swapPrice != null ? record.swapPrice.toString() : "0");
                    json.put("profit", record.profit != null ? record.profit.toString() : "0");
                    json.put("close_reason", record.closeReason != null ? record.closeReason : "");
                    json.put("hold_time_ms", record.holdTimeMs != null ? record.holdTimeMs : 0);
                    json.put("timestamp", record.timestamp);
                    return json.toString();
                })
                .name("Trade To JSON");
            
            // 写入交易记录表
            DorisSink<String> tradeSink = dorisSinkFactory.createDorisSink(
                "crypto_dw",
                "ads_arbitrage_trades",
                "ads-arbitrage-trades"
            );
            
            tradeJsonStream.sinkTo(tradeSink).name("Doris Trade Sink");
            
            logger.info("✓ 交易记录 Sink 创建成功");
            logger.info("  Database: crypto_dw");
            logger.info("  Table: ads_arbitrage_trades");
        } else {
            logger.info("⚠ 自动交易未启用,跳过订单流和交易处理");
        }
        
        // ========== 步骤 10: 套利机会写入 Doris ==========
```

## 注意事项

1. 这段代码添加在原有的步骤6之前
2. 原有的步骤6(写入套利机会到 Doris)保持不变
3. 新增的代码只在 `arbitrage.trading.enabled = true` 时才执行
4. 需要确保已经创建了 `ads_arbitrage_trades` 表

## 完整流程

添加补丁后的完整流程:

```
步骤 1: 创建现货价格流
步骤 2: 创建合约价格流
步骤 3: Interval Join 关联两个流
步骤 4: 从 Redis 读取黑名单(广播流)
步骤 5: 使用广播状态过滤套利机会
步骤 6: 创建订单流(可选,自动交易)
步骤 7: 订单匹配处理(可选,自动交易)
步骤 8: 套利交易处理(可选,自动交易)
步骤 9: 交易记录写入 Doris(可选,自动交易)
步骤 10: 套利机会写入 Doris(原步骤6)
```

## 测试步骤

1. **不启用自动交易**(默认):
   - 只会发现套利机会并写入 Doris
   - 不会下单和交易

2. **启用自动交易**:
   ```yaml
   arbitrage:
     trading:
       enabled: true
   ```
   - 会订阅订单流
   - 发现套利机会时自动下单
   - 等待订单成交确认
   - 监控持仓并自动平仓
   - 记录所有交易到 Doris

---

**文档时间**: 2026-04-08  
**作者**: Kiro AI Assistant
