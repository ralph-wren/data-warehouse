# 套利作业 - ArbitrageOpportunity 添加交易明细和实时利润计算

## 问题描述

`ArbitrageOpportunity` 类只包含套利机会发现时的基本信息(价差、价差率等),缺少开仓后的实际交易明细和实时利润计算。

**原有字段**:
- 发现阶段: 现货价格、合约价格、价差、价差率、预估利润
- 缺少: 实际成交价、成交量、手续费、总成本、实时利润

**需求**:
1. 添加开仓后的交易明细(成交价、成交量、手续费等)
2. 计算总成本和总费用
3. 基于实时价格计算预估利润和利润率

## 解决方案

扩展 `ArbitrageOpportunity` 类,添加三个阶段的数据:

### 1. 发现阶段: 套利机会基本信息

**已有字段**(保持不变):
```java
public String symbol;                    // 交易对符号(如 BTC)
public BigDecimal spotPrice;             // 现货价格(发现时)
public BigDecimal spotVolume;            // 现货成交量
public BigDecimal futuresPrice;          // 合约价格(发现时)
public BigDecimal futuresVolume;         // 合约成交量
public BigDecimal spread;                // 价差(合约价格 - 现货价格)
public BigDecimal spreadRate;            // 价差率(%)
public String arbitrageDirection;        // 套利方向
public BigDecimal unitProfitEstimate;    // 单位预估利润
public BigDecimal profitEstimate;        // 预估利润(发现时)
public long timestamp;                   // 发现时间戳
```

### 2. 开仓阶段: 交易明细

**新增字段**:

#### 现货交易明细
```java
public String spotOrderId;               // 现货订单ID
public BigDecimal spotFillPrice;         // 现货成交价格(实际成交价)
public BigDecimal spotFillQuantity;      // 现货成交数量(实际成交量)
public BigDecimal spotFee;               // 现货手续费金额
public String spotFeeCurrency;           // 现货手续费币种(如 USDT 或 BTC)
public BigDecimal spotCost;              // 现货成本(成交价 × 成交量)
```

#### 合约交易明细
```java
public String futuresOrderId;            // 合约订单ID
public BigDecimal futuresFillPrice;      // 合约成交价格(实际成交价)
public BigDecimal futuresFillQuantity;   // 合约成交数量(实际成交量,单位:张)
public BigDecimal futuresFee;            // 合约手续费金额
public String futuresFeeCurrency;        // 合约手续费币种(如 USDT)
public BigDecimal futuresCost;           // 合约成本(成交价 × 成交量 × 合约面值)
```

#### 总成本和费用
```java
public BigDecimal totalCost;             // 总成本(现货成本 + 合约成本)
public BigDecimal totalFee;              // 总手续费(现货手续费 + 合约手续费)
public BigDecimal totalExpense;          // 总费用(总成本 + 总手续费)
```

### 3. 实时计算: 预估利润

**新增字段**:
```java
public BigDecimal currentSpotPrice;      // 当前现货价格(实时)
public BigDecimal currentFuturesPrice;   // 当前合约价格(实时)
public BigDecimal currentSpread;         // 当前价差(实时)
public BigDecimal currentSpreadRate;     // 当前价差率(%,实时)
public BigDecimal estimatedProfit;       // 预估利润(基于实时价格计算)
public BigDecimal estimatedProfitRate;   // 预估利润率(%,预估利润 / 总费用)
public long updateTimestamp;             // 更新时间戳(实时价格更新时间)
```

### 4. 计算方法

#### 计算总成本
```java
/**
 * 计算总成本
 * 总成本 = 现货成本 + 合约成本
 */
public void calculateTotalCost() {
    if (spotCost != null && futuresCost != null) {
        this.totalCost = spotCost.add(futuresCost);
    }
}
```

#### 计算总手续费
```java
/**
 * 计算总手续费
 * 总手续费 = 现货手续费 + 合约手续费
 * 注意: 需要将不同币种的手续费统一转换为 USDT
 */
public void calculateTotalFee() {
    if (spotFee != null && futuresFee != null) {
        // 简化处理: 假设手续费都是 USDT
        // 实际应该根据 spotFeeCurrency 和 futuresFeeCurrency 进行转换
        this.totalFee = spotFee.add(futuresFee);
    }
}
```

#### 计算总费用
```java
/**
 * 计算总费用
 * 总费用 = 总成本 + 总手续费
 */
public void calculateTotalExpense() {
    if (totalCost != null && totalFee != null) {
        this.totalExpense = totalCost.add(totalFee);
    }
}
```

#### 计算预估利润(基于实时价格)
```java
/**
 * 计算预估利润(基于实时价格)
 * 
 * 策略A(做空现货/做多合约):
 *   预估利润 = (当前合约价 - 开仓合约价) × 合约数量 
 *            - (开仓现货价 - 当前现货价) × 现货数量 
 *            - 总手续费
 * 
 * 策略B(做多现货/做空合约):
 *   预估利润 = (当前现货价 - 开仓现货价) × 现货数量 
 *            - (开仓合约价 - 当前合约价) × 合约数量 
 *            - 总手续费
 * 
 * @param currentSpotPrice 当前现货价格
 * @param currentFuturesPrice 当前合约价格
 */
public void calculateEstimatedProfit(
    BigDecimal currentSpotPrice, 
    BigDecimal currentFuturesPrice) {
    
    // 更新当前价格
    this.currentSpotPrice = currentSpotPrice;
    this.currentFuturesPrice = currentFuturesPrice;
    
    // 计算当前价差和价差率
    this.currentSpread = currentFuturesPrice.subtract(currentSpotPrice);
    this.currentSpreadRate = currentSpread.divide(
        currentSpotPrice.min(currentFuturesPrice), 
        6, 
        BigDecimal.ROUND_HALF_UP
    ).multiply(new BigDecimal("100"));
    
    // 根据套利方向计算预估利润
    if (arbitrageDirection.contains("做空现货")) {
        // 策略A: 做空现货/做多合约
        BigDecimal spotPnL = spotFillPrice.subtract(currentSpotPrice)
            .multiply(spotFillQuantity);
        BigDecimal futuresPnL = currentFuturesPrice.subtract(futuresFillPrice)
            .multiply(futuresFillQuantity);
        this.estimatedProfit = spotPnL.add(futuresPnL).subtract(totalFee);
    } else {
        // 策略B: 做多现货/做空合约
        BigDecimal spotPnL = currentSpotPrice.subtract(spotFillPrice)
            .multiply(spotFillQuantity);
        BigDecimal futuresPnL = futuresFillPrice.subtract(currentFuturesPrice)
            .multiply(futuresFillQuantity);
        this.estimatedProfit = spotPnL.add(futuresPnL).subtract(totalFee);
    }
    
    // 计算预估利润率
    if (totalExpense != null && totalExpense.compareTo(BigDecimal.ZERO) > 0) {
        this.estimatedProfitRate = estimatedProfit.divide(
            totalExpense, 
            6, 
            BigDecimal.ROUND_HALF_UP
        ).multiply(new BigDecimal("100"));
    }
    
    // 更新时间戳
    this.updateTimestamp = System.currentTimeMillis();
}
```

## 使用示例

### 1. 开仓后填充交易明细

```java
// 开仓成功后,填充交易明细
ArbitrageOpportunity opp = ...;

// 填充现货交易明细
opp.spotOrderId = "123456789";
opp.spotFillPrice = new BigDecimal("50000.00");
opp.spotFillQuantity = new BigDecimal("0.1");
opp.spotFee = new BigDecimal("5.00");
opp.spotFeeCurrency = "USDT";
opp.spotCost = opp.spotFillPrice.multiply(opp.spotFillQuantity);  // 5000.00

// 填充合约交易明细
opp.futuresOrderId = "987654321";
opp.futuresFillPrice = new BigDecimal("50100.00");
opp.futuresFillQuantity = new BigDecimal("0.1");
opp.futuresFee = new BigDecimal("5.01");
opp.futuresFeeCurrency = "USDT";
opp.futuresCost = opp.futuresFillPrice.multiply(opp.futuresFillQuantity);  // 5010.00

// 计算总成本和费用
opp.calculateTotalCost();      // 10010.00
opp.calculateTotalFee();       // 10.01
opp.calculateTotalExpense();   // 10020.01
```

### 2. 实时计算预估利润

```java
// 获取实时价格
BigDecimal currentSpotPrice = new BigDecimal("50050.00");
BigDecimal currentFuturesPrice = new BigDecimal("50150.00");

// 计算预估利润
opp.calculateEstimatedProfit(currentSpotPrice, currentFuturesPrice);

// 输出结果
logger.info("当前现货价: {}", opp.currentSpotPrice);           // 50050.00
logger.info("当前合约价: {}", opp.currentFuturesPrice);        // 50150.00
logger.info("当前价差: {}", opp.currentSpread);                // 100.00
logger.info("当前价差率: {}%", opp.currentSpreadRate);         // 0.2%
logger.info("预估利润: {} USDT", opp.estimatedProfit);        // 计算结果
logger.info("预估利润率: {}%", opp.estimatedProfitRate);      // 计算结果
```

## 利润计算公式

### 策略A: 做空现货/做多合约

**开仓**:
- 现货: 卖出(做空) @ 50000 USDT
- 合约: 买入(做多) @ 50100 USDT

**平仓**(假设):
- 现货: 买入(平仓) @ 50050 USDT
- 合约: 卖出(平仓) @ 50150 USDT

**盈亏计算**:
- 现货盈亏 = (开仓价 - 平仓价) × 数量 = (50000 - 50050) × 0.1 = -5 USDT (亏损)
- 合约盈亏 = (平仓价 - 开仓价) × 数量 = (50150 - 50100) × 0.1 = 5 USDT (盈利)
- 总盈亏 = -5 + 5 - 10.01(手续费) = -10.01 USDT (亏损)

### 策略B: 做多现货/做空合约

**开仓**:
- 现货: 买入(做多) @ 50000 USDT
- 合约: 卖出(做空) @ 50100 USDT

**平仓**(假设):
- 现货: 卖出(平仓) @ 50050 USDT
- 合约: 买入(平仓) @ 50150 USDT

**盈亏计算**:
- 现货盈亏 = (平仓价 - 开仓价) × 数量 = (50050 - 50000) × 0.1 = 5 USDT (盈利)
- 合约盈亏 = (开仓价 - 平仓价) × 数量 = (50100 - 50150) × 0.1 = -5 USDT (亏损)
- 总盈亏 = 5 - 5 - 10.01(手续费) = -10.01 USDT (亏损)

## 技术要点

### 1. 字段命名规范

- `Fill` 前缀: 表示实际成交信息(如 `spotFillPrice`)
- `Current` 前缀: 表示实时价格信息(如 `currentSpotPrice`)
- `Estimated` 前缀: 表示预估值(如 `estimatedProfit`)

### 2. 精度控制

所有金额字段使用 `BigDecimal`,精度为 8 位小数:
```java
BigDecimal price = new BigDecimal("50000.12345678");
```

### 3. 手续费币种转换

当前简化处理,假设所有手续费都是 USDT。实际应该:
```java
// 如果现货手续费是 BTC,需要转换为 USDT
if ("BTC".equals(spotFeeCurrency)) {
    BigDecimal btcToUsdt = getCurrentPrice("BTC-USDT");
    spotFeeInUsdt = spotFee.multiply(btcToUsdt);
}
```

### 4. 合约面值

OKX 合约的面值通常是:
- BTC: 0.01 BTC/张
- ETH: 0.1 ETH/张
- 其他: 1 币/张

计算合约成本时需要考虑面值:
```java
// 合约成本 = 成交价 × 成交数量 × 合约面值
BigDecimal contractValue = new BigDecimal("0.01");  // BTC 合约面值
futuresCost = futuresFillPrice.multiply(futuresFillQuantity).multiply(contractValue);
```

## 验证结果

编译成功:
```bash
mvn clean compile -DskipTests
# BUILD SUCCESS
```

## 后续集成

### 1. TradingDecisionProcessor 中填充交易明细

开仓成功后,查询订单详情并填充:
```java
// 查询现货订单详情
OrderDetail spotDetail = tradingService.getOrderDetail(spotOrderId);
opp.spotFillPrice = spotDetail.avgPrice;
opp.spotFillQuantity = spotDetail.fillSize;
opp.spotFee = spotDetail.fee;
opp.spotFeeCurrency = spotDetail.feeCurrency;
opp.spotCost = spotDetail.avgPrice.multiply(spotDetail.fillSize);

// 查询合约订单详情
OrderDetail futuresDetail = tradingService.getOrderDetail(futuresOrderId);
opp.futuresFillPrice = futuresDetail.avgPrice;
opp.futuresFillQuantity = futuresDetail.fillSize;
opp.futuresFee = futuresDetail.fee;
opp.futuresFeeCurrency = futuresDetail.feeCurrency;
opp.futuresCost = futuresDetail.avgPrice.multiply(futuresDetail.fillSize);

// 计算总成本和费用
opp.calculateTotalCost();
opp.calculateTotalFee();
opp.calculateTotalExpense();
```

### 2. 实时更新预估利润

在持仓监控中,定期更新预估利润:
```java
// 获取实时价格
BigDecimal currentSpotPrice = getRealtimePrice(symbol, "SPOT");
BigDecimal currentFuturesPrice = getRealtimePrice(symbol, "SWAP");

// 计算预估利润
opp.calculateEstimatedProfit(currentSpotPrice, currentFuturesPrice);

// 判断是否平仓
if (opp.estimatedProfitRate.compareTo(closeThreshold) > 0) {
    closePosition(opp);
}
```

### 3. 日志输出

```java
logger.info("========== 开仓成功 ==========");
logger.info("交易对: {}", opp.symbol);
logger.info("现货订单: {} | 成交价: {} | 成交量: {} | 手续费: {} {}", 
    opp.spotOrderId, opp.spotFillPrice, opp.spotFillQuantity, 
    opp.spotFee, opp.spotFeeCurrency);
logger.info("合约订单: {} | 成交价: {} | 成交量: {} | 手续费: {} {}", 
    opp.futuresOrderId, opp.futuresFillPrice, opp.futuresFillQuantity, 
    opp.futuresFee, opp.futuresFeeCurrency);
logger.info("总成本: {} USDT | 总手续费: {} USDT | 总费用: {} USDT", 
    opp.totalCost, opp.totalFee, opp.totalExpense);

logger.info("========== 实时利润 ==========");
logger.info("当前现货价: {} | 当前合约价: {}", 
    opp.currentSpotPrice, opp.currentFuturesPrice);
logger.info("当前价差: {} | 当前价差率: {}%", 
    opp.currentSpread, opp.currentSpreadRate);
logger.info("预估利润: {} USDT | 预估利润率: {}%", 
    opp.estimatedProfit, opp.estimatedProfitRate);
```

## 相关文件

- `src/main/java/com/crypto/dw/flink/model/ArbitrageOpportunity.java` (已修改)
- `src/main/java/com/crypto/dw/processor/TradingDecisionProcessor.java` (待集成)
- `src/main/java/com/crypto/dw/trading/OKXTradingService.java` (待添加订单查询方法)

## 注意事项

1. 所有金额字段使用 `BigDecimal`,避免精度丢失
2. 手续费币种需要统一转换为 USDT
3. 合约成本计算需要考虑合约面值
4. 实时利润计算需要定期更新
5. 利润计算公式根据套利方向不同而不同

## 后续优化建议

1. 添加手续费币种自动转换功能
2. 添加合约面值自动查询功能
3. 添加历史利润曲线记录
4. 添加利润率预警功能
5. 支持多币种手续费统一计算
