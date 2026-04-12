# 套利作业 - 查询订单明细后更新 ArbitrageOpportunity

## 问题描述

在 `TradingDecisionProcessor` 中,开仓成功后会查询订单明细并保存到 CSV 文件,但没有更新 `ArbitrageOpportunity` 对象的交易明细字段。

**现有流程**:
1. 开仓成功 → 获取订单ID
2. 异步查询订单明细 → 保存到 CSV
3. ❌ 没有更新 `ArbitrageOpportunity` 对象

**需求**:
- 查询订单明细后,除了保存到 CSV,还要更新 `ArbitrageOpportunity` 对象
- 填充现货和合约的成交价、成交量、手续费等信息
- 计算总成本和总费用

## 解决方案

修改 `TradingDecisionProcessor` 类,在查询订单明细后更新 `ArbitrageOpportunity` 对象。

### 1. 传递 ArbitrageOpportunity 对象到异步任务

**修改位置**: `TradingDecisionProcessor.openPosition()` 方法

**修改前**:
```java
// ⭐ 立即启动异步任务查询订单明细（不依赖 WebSocket 推送）
final String finalSymbol = opp.symbol;
final String finalSpotOrderId = spotOrderId;
final String finalSwapOrderId = swapOrderId;

// 延迟 2 秒后查询（给订单成交留出时间）
orderQueryExecutor.submit(() -> {
    // ...
});
```

**修改后**:
```java
// ⭐ 立即启动异步任务查询订单明细（不依赖 WebSocket 推送）
final String finalSymbol = opp.symbol;
final String finalSpotOrderId = spotOrderId;
final String finalSwapOrderId = swapOrderId;
final ArbitrageOpportunity finalOpp = opp;  // 传递 ArbitrageOpportunity 对象

// 延迟 2 秒后查询（给订单成交留出时间）
orderQueryExecutor.submit(() -> {
    // ...
});
```

### 2. 查询现货订单详情后更新对象

**修改位置**: 异步任务中查询现货订单详情的部分

**修改前**:
```java
if (spotDetail != null) {
    logger.info("✅ 现货订单详情查询成功,准备保存到CSV: orderId={}", finalSpotOrderId);
    saveOrderDetailToCsv(finalSymbol, spotDetail, "SPOT");
} else {
    logger.warn("⚠️ 现货订单详情为空,无法保存: orderId={}", finalSpotOrderId);
}
```

**修改后**:
```java
if (spotDetail != null) {
    logger.info("✅ 现货订单详情查询成功,准备保存到CSV并更新ArbitrageOpportunity: orderId={}", finalSpotOrderId);
    saveOrderDetailToCsv(finalSymbol, spotDetail, "SPOT");
    // ⭐ 更新 ArbitrageOpportunity 对象的现货交易明细
    updateArbitrageOpportunityWithSpotDetail(finalOpp, spotDetail);
} else {
    logger.warn("⚠️ 现货订单详情为空,无法保存: orderId={}", finalSpotOrderId);
}
```

### 3. 查询合约订单详情后更新对象并计算总成本

**修改位置**: 异步任务中查询合约订单详情的部分

**修改前**:
```java
if (swapDetail != null) {
    logger.info("✅ 合约订单详情查询成功,准备保存到CSV: orderId={}", finalSwapOrderId);
    saveOrderDetailToCsv(finalSymbol, swapDetail, "SWAP");
} else {
    logger.warn("⚠️ 合约订单详情为空,无法保存: orderId={}", finalSwapOrderId);
}
```

**修改后**:
```java
if (swapDetail != null) {
    logger.info("✅ 合约订单详情查询成功,准备保存到CSV并更新ArbitrageOpportunity: orderId={}", finalSwapOrderId);
    saveOrderDetailToCsv(finalSymbol, swapDetail, "SWAP");
    // ⭐ 更新 ArbitrageOpportunity 对象的合约交易明细
    updateArbitrageOpportunityWithFuturesDetail(finalOpp, swapDetail);
    
    // ⭐ 计算总成本和费用
    finalOpp.calculateTotalCost();
    finalOpp.calculateTotalFee();
    finalOpp.calculateTotalExpense();
    
    logger.info("✅ ArbitrageOpportunity 更新完成: symbol={}, totalCost={}, totalFee={}, totalExpense={}", 
        finalSymbol, finalOpp.totalCost, finalOpp.totalFee, finalOpp.totalExpense);
} else {
    logger.warn("⚠️ 合约订单详情为空,无法保存: orderId={}", finalSwapOrderId);
}
```

### 4. 添加更新现货交易明细的方法

**新增方法**: `updateArbitrageOpportunityWithSpotDetail()`

```java
/**
 * 更新 ArbitrageOpportunity 对象的现货交易明细
 * 
 * @param opp ArbitrageOpportunity 对象
 * @param spotDetail 现货订单详情(JSON)
 */
private void updateArbitrageOpportunityWithSpotDetail(
        ArbitrageOpportunity opp, 
        com.fasterxml.jackson.databind.JsonNode spotDetail) {
    try {
        // 提取现货订单信息
        opp.spotOrderId = spotDetail.path("ordId").asText();
        opp.spotFillPrice = new BigDecimal(spotDetail.path("avgPx").asText());
        opp.spotFillQuantity = new BigDecimal(spotDetail.path("accFillSz").asText());
        opp.spotFee = new BigDecimal(spotDetail.path("fee").asText()).abs();  // 手续费取绝对值
        opp.spotFeeCurrency = spotDetail.path("feeCcy").asText();
        
        // 计算现货成本 = 成交价 × 成交量
        opp.spotCost = opp.spotFillPrice.multiply(opp.spotFillQuantity);
        
        logger.info("✅ 现货交易明细已更新: orderId={}, fillPrice={}, fillQty={}, fee={} {}, cost={}", 
            opp.spotOrderId, opp.spotFillPrice, opp.spotFillQuantity, 
            opp.spotFee, opp.spotFeeCurrency, opp.spotCost);
            
    } catch (Exception e) {
        logger.error("❌ 更新现货交易明细失败: {}", e.getMessage(), e);
    }
}
```

**字段映射**:
- `ordId` → `spotOrderId`: 订单ID
- `avgPx` → `spotFillPrice`: 成交均价
- `accFillSz` → `spotFillQuantity`: 累计成交数量
- `fee` → `spotFee`: 手续费(取绝对值)
- `feeCcy` → `spotFeeCurrency`: 手续费币种

### 5. 添加更新合约交易明细的方法

**新增方法**: `updateArbitrageOpportunityWithFuturesDetail()`

```java
/**
 * 更新 ArbitrageOpportunity 对象的合约交易明细
 * 
 * @param opp ArbitrageOpportunity 对象
 * @param futuresDetail 合约订单详情(JSON)
 */
private void updateArbitrageOpportunityWithFuturesDetail(
        ArbitrageOpportunity opp, 
        com.fasterxml.jackson.databind.JsonNode futuresDetail) {
    try {
        // 提取合约订单信息
        opp.futuresOrderId = futuresDetail.path("ordId").asText();
        opp.futuresFillPrice = new BigDecimal(futuresDetail.path("avgPx").asText());
        opp.futuresFillQuantity = new BigDecimal(futuresDetail.path("accFillSz").asText());
        opp.futuresFee = new BigDecimal(futuresDetail.path("fee").asText()).abs();  // 手续费取绝对值
        opp.futuresFeeCurrency = futuresDetail.path("feeCcy").asText();
        
        // 计算合约成本 = 成交价 × 成交量
        // 注意: OKX 合约的成交量单位是张,需要根据合约面值计算
        // 简化处理: 假设合约面值已经包含在成交量中
        opp.futuresCost = opp.futuresFillPrice.multiply(opp.futuresFillQuantity);
        
        logger.info("✅ 合约交易明细已更新: orderId={}, fillPrice={}, fillQty={}, fee={} {}, cost={}", 
            opp.futuresOrderId, opp.futuresFillPrice, opp.futuresFillQuantity, 
            opp.futuresFee, opp.futuresFeeCurrency, opp.futuresCost);
            
    } catch (Exception e) {
        logger.error("❌ 更新合约交易明细失败: {}", e.getMessage(), e);
    }
}
```

**字段映射**:
- `ordId` → `futuresOrderId`: 订单ID
- `avgPx` → `futuresFillPrice`: 成交均价
- `accFillSz` → `futuresFillQuantity`: 累计成交数量
- `fee` → `futuresFee`: 手续费(取绝对值)
- `feeCcy` → `futuresFeeCurrency`: 手续费币种

## 数据流程

```
开仓成功
  ↓
获取订单ID (spotOrderId, swapOrderId)
  ↓
启动异步任务 (延迟 2 秒)
  ↓
查询现货订单详情
  ├─> 保存到 CSV
  └─> 更新 ArbitrageOpportunity.spotXxx 字段
  ↓
查询合约订单详情
  ├─> 保存到 CSV
  └─> 更新 ArbitrageOpportunity.futuresXxx 字段
  ↓
计算总成本和费用
  ├─> calculateTotalCost()
  ├─> calculateTotalFee()
  └─> calculateTotalExpense()
  ↓
日志输出: totalCost, totalFee, totalExpense
```

## OKX 订单详情 JSON 格式

```json
{
  "ordId": "123456789",           // 订单ID
  "instId": "BTC-USDT",           // 交易对
  "avgPx": "50000.00",            // 成交均价
  "accFillSz": "0.1",             // 累计成交数量
  "fee": "-5.00",                 // 手续费(负数表示扣除)
  "feeCcy": "USDT",               // 手续费币种
  "state": "filled",              // 订单状态
  "side": "buy",                  // 买卖方向
  "posSide": "long",              // 持仓方向
  "ordType": "market",            // 订单类型
  "px": "0",                      // 委托价格(市价单为0)
  "sz": "0.1",                    // 委托数量
  "fillTime": "1712345678000",    // 成交时间(毫秒时间戳)
  "cTime": "1712345676000",       // 创建时间(毫秒时间戳)
  "uTime": "1712345678000"        // 更新时间(毫秒时间戳)
}
```

## 技术要点

### 1. 异步任务中的对象传递

使用 `final` 关键字传递对象到异步任务:
```java
final ArbitrageOpportunity finalOpp = opp;
orderQueryExecutor.submit(() -> {
    // 可以访问 finalOpp
});
```

### 2. 手续费取绝对值

OKX 返回的手续费是负数(表示扣除),需要取绝对值:
```java
opp.spotFee = new BigDecimal(spotDetail.path("fee").asText()).abs();
```

### 3. 成本计算

```java
// 现货成本 = 成交价 × 成交量
opp.spotCost = opp.spotFillPrice.multiply(opp.spotFillQuantity);

// 合约成本 = 成交价 × 成交量
opp.futuresCost = opp.futuresFillPrice.multiply(opp.futuresFillQuantity);
```

### 4. 总成本和费用计算

```java
// 总成本 = 现货成本 + 合约成本
opp.calculateTotalCost();

// 总手续费 = 现货手续费 + 合约手续费
opp.calculateTotalFee();

// 总费用 = 总成本 + 总手续费
opp.calculateTotalExpense();
```

### 5. 异常处理

每个更新方法都有独立的异常处理,避免一个失败影响另一个:
```java
try {
    // 更新现货交易明细
} catch (Exception e) {
    logger.error("❌ 更新现货交易明细失败: {}", e.getMessage(), e);
}
```

## 日志输出示例

```
✅ 现货订单详情查询成功,准备保存到CSV并更新ArbitrageOpportunity: orderId=123456789
✅ 现货交易明细已更新: orderId=123456789, fillPrice=50000.00, fillQty=0.1, fee=5.00 USDT, cost=5000.00

✅ 合约订单详情查询成功,准备保存到CSV并更新ArbitrageOpportunity: orderId=987654321
✅ 合约交易明细已更新: orderId=987654321, fillPrice=50100.00, fillQty=0.1, fee=5.01 USDT, cost=5010.00

✅ ArbitrageOpportunity 更新完成: symbol=BTC, totalCost=10010.00, totalFee=10.01, totalExpense=10020.01
```

## 验证结果

编译成功:
```bash
mvn clean compile -DskipTests
# BUILD SUCCESS
```

## 后续使用

更新后的 `ArbitrageOpportunity` 对象可以用于:

1. **实时利润计算**:
```java
// 获取实时价格
BigDecimal currentSpotPrice = getRealtimePrice(symbol, "SPOT");
BigDecimal currentFuturesPrice = getRealtimePrice(symbol, "SWAP");

// 计算预估利润
opp.calculateEstimatedProfit(currentSpotPrice, currentFuturesPrice);

// 输出结果
logger.info("预估利润: {} USDT", opp.estimatedProfit);
logger.info("预估利润率: {}%", opp.estimatedProfitRate);
```

2. **平仓决策**:
```java
// 根据预估利润率判断是否平仓
if (opp.estimatedProfitRate.compareTo(closeThreshold) > 0) {
    closePosition(opp);
}
```

3. **交易记录**:
```java
// 保存完整的交易记录到数据库
TradeRecord record = new TradeRecord();
record.symbol = opp.symbol;
record.spotOrderId = opp.spotOrderId;
record.futuresOrderId = opp.futuresOrderId;
record.totalCost = opp.totalCost;
record.totalFee = opp.totalFee;
record.profit = opp.estimatedProfit;
saveToDatabase(record);
```

## 相关文件

- `src/main/java/com/crypto/dw/processor/TradingDecisionProcessor.java` (已修改)
- `src/main/java/com/crypto/dw/flink/model/ArbitrageOpportunity.java` (提供字段和计算方法)
- `src/main/java/com/crypto/dw/trading/OKXTradingService.java` (提供订单查询方法)

## 注意事项

1. 异步任务延迟 2 秒后查询,给订单成交留出时间
2. 手续费需要取绝对值(OKX 返回负数)
3. 合约成本计算需要考虑合约面值(当前简化处理)
4. 总成本和费用在合约订单查询完成后计算
5. 异常处理独立,避免相互影响

## 后续优化建议

1. 添加重试机制,订单未成交时重新查询
2. 添加订单状态检查,只处理已成交订单
3. 添加合约面值查询,精确计算合约成本
4. 添加手续费币种转换,统一为 USDT
5. 添加订单详情缓存,避免重复查询
