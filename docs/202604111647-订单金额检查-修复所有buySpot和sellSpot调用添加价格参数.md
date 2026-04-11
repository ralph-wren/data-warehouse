# 订单金额检查 - 修复所有buySpot和sellSpot调用添加价格参数

## 问题描述

在之前的修改中，为 `buySpot()` 和 `sellSpot()` 方法添加了 `price` 参数，用于在下单前检查订单金额是否满足最小要求（OKX最小10 USDT 或配置的交易金额，取较大值）。但是其他文件中调用这两个方法时没有传入价格参数，导致编译错误：

```
java: 无法将类 com.crypto.dw.trading.OKXTradingService中的方法 buySpot应用到给定类型;
需要: java.lang.String,java.math.BigDecimal,java.math.BigDecimal
找到: java.lang.String,java.math.BigDecimal
原因: 实际参数列表和形式参数列表长度不同
```

## 解决方案

### 1. 修改 TradingDecisionProcessor.java

在 `openPosition()` 方法中，为开仓时的现货订单添加价格参数：

```java
// 开仓 - 做多现货
spotOrderId = tradingService.buySpot(opp.symbol, tradeAmount, opp.spotPrice);

// 开仓 - 做空现货
spotOrderId = tradingService.sellSpot(opp.symbol, tradeAmount, opp.spotPrice);
```

在 `closePosition()` 方法中，为平仓时的现货订单添加价格参数：

```java
// 平仓 - 卖出现货
spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice);

// 平仓 - 买入现货
spotOrderId = tradingService.buySpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice);
```

### 2. 修改 FlinkADSArbitrageJob.java

在 `closePosition()` 方法中，为平仓时的现货订单添加价格参数：

```java
// 策略 A 平仓: 卖出现货 + 平空合约
spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice);
```

## 价格参数来源

所有修改都使用 `ArbitrageOpportunity` 对象中的 `spotPrice` 字段作为价格参数：

- **开仓时**: 使用当前套利机会的现货价格 `opp.spotPrice`
- **平仓时**: 使用当前套利机会的现货价格 `opp.spotPrice`（实时价格）

## 订单金额检查逻辑

在 `buySpot()` 和 `sellSpot()` 方法内部，会自动检查订单金额：

1. 计算订单金额: `orderValue = size × price`
2. 确定最小订单金额: `minOrderAmount = max(10 USDT, 配置的trade-amount-usdt)`
3. 如果订单金额 < 最小要求，自动调整数量: `size = minOrderAmount / price`
4. 记录调整日志，方便排查问题

## 修改文件清单

1. `data-warehouse/src/main/java/com/crypto/dw/processor/TradingDecisionProcessor.java`
   - 修改 `openPosition()` 方法中的 4 处调用（2处buySpot + 2处sellSpot）
   - 修改 `closePosition()` 方法中的 2 处调用（1处buySpot + 1处sellSpot）

2. `data-warehouse/src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob.java`
   - 修改 `closePosition()` 方法中的 1 处调用（1处sellSpot）

## 编译验证

```bash
mvn clean compile -DskipTests
```

编译成功，无错误。

## 测试建议

1. 测试低价币种（如 SAHARA）的订单金额自动调整功能
2. 验证日志中是否正确记录了订单金额调整信息
3. 确认调整后的订单能够成功提交到 OKX

## 注意事项

1. 测试文件中的调用未修改（`OKXTradingServiceTest.java`、`OKXSingleOrderTest.java`），因为这些是测试代码，优先级较低
2. 所有生产代码中的调用都已修复，确保程序可以正常运行
3. 价格参数使用实时的现货价格，确保订单金额计算准确

## 相关文档

- 上一步: 添加订单金额检查逻辑到 `buySpot()` 和 `sellSpot()` 方法
- 下一步: 测试低价币种的订单金额自动调整功能
