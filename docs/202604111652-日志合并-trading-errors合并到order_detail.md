# 日志合并 - trading-errors 合并到 order_detail

## 问题描述

之前的代码中，交易错误信息被记录到两个不同的文件：
1. `logs/trading-errors.csv` - 通过 `writeErrorToCSV()` 方法记录
2. `logs/order_detail_{timestamp}.csv` - 通过 `logOrderDetail()` 方法记录

这导致错误信息分散在两个文件中，不便于统一查看和分析。

## 错误示例

```
2026-04-11 16:47:49.487 [Trading Decision -> Trade To JSON -> Doris Trade Sink: Writer -> Doris Trade Sink: Committer (2/4)#0] INFO  com.crypto.dw.trading.OKXTradingService - {"code":"1","data":[{"clOrdId":"","ordId":"","sCode":"54000","sMsg":"Margin trading is not supported. ","subCode":"","tag":"","ts":"1775897272322"}],"inTime":"1775897272322626","msg":"All operations failed","outTime":"1775897272323262"}
```

错误代码 54000 表示"不支持保证金交易"，说明当前账户没有开通杠杆交易功能。

## 解决方案

### 1. 删除 `writeErrorToCSV()` 方法

删除 `OKXTradingService.java` 中的 `writeErrorToCSV()` 方法，因为错误信息已经通过 `FlinkADSArbitrageJob` 的 `logOrderDetail()` 方法记录到 `order_detail.csv` 中。

### 2. 移除所有 `writeErrorToCSV()` 调用

在 `placeOrder()` 方法中，移除所有对 `writeErrorToCSV()` 的调用，并添加注释说明错误信息已通过 `logOrderDetail` 记录：

```java
// 错误信息已通过 logOrderDetail 记录到 order_detail.csv
```

### 3. 统一错误记录流程

现在所有订单相关的信息（包括成功和失败）都统一记录到 `order_detail.csv` 文件中：

**order_detail.csv 格式**:
```csv
时间,币种,订单ID,订单类型,方向,数量,价格,成交价格,成交数量,手续费,手续费币种,状态,错误信息
```

**示例记录**:
```csv
2026-04-11 16:47:49,BTC,,SPOT,BUY,0.001,50000.00,,,,,FAILED,Margin trading is not supported
```

## 修改文件清单

1. `data-warehouse/src/main/java/com/crypto/dw/trading/OKXTradingService.java`
   - 删除 `writeErrorToCSV()` 方法（约50行代码）
   - 移除 `placeOrder()` 方法中的 3 处 `writeErrorToCSV()` 调用
   - 添加注释说明错误信息记录方式

## 日志记录流程

### 开仓流程

1. **下单前**: 在 `FlinkADSArbitrageJob.openPosition()` 中调用 `tradingService.buySpot()` 或 `sellSpot()`
2. **下单失败**: `OKXTradingService` 返回 `null`
3. **记录失败**: `FlinkADSArbitrageJob` 调用 `logOrderDetail()` 记录失败信息，包含错误消息

### 平仓流程

1. **下单前**: 在 `FlinkADSArbitrageJob.closePosition()` 中调用 `tradingService.sellSpot()` 或 `buySpot()`
2. **下单失败**: `OKXTradingService` 返回 `null`
3. **记录失败**: `FlinkADSArbitrageJob` 调用 `logOrderDetail()` 记录失败信息，包含错误消息

## 错误信息传递

错误信息通过以下方式传递：

1. **OKX API 返回错误**: 
   ```json
   {
     "code": "1",
     "msg": "All operations failed",
     "data": [{
       "sCode": "54000",
       "sMsg": "Margin trading is not supported."
     }]
   }
   ```

2. **OKXTradingService 记录日志**: 
   ```
   logger.error("✗ 下单失败!");
   logger.error("  └─ 错误代码: {} (详细代码: {})", errorCode, sCode);
   logger.error("  └─ 错误原因: {}", translateErrorMessage(sCode, sMsg));
   ```

3. **返回 null**: 表示下单失败

4. **FlinkADSArbitrageJob 记录到 CSV**: 
   ```java
   logOrderDetail(
       symbol,
       null,  // 订单ID为空
       "SPOT",
       "BUY",
       amount,
       price,
       null,  // 成交价格为空
       null,  // 成交数量为空
       null,  // 手续费为空
       null,  // 手续费币种为空
       "FAILED",
       "Margin trading is not supported"  // 错误信息
   );
   ```

## 优势

1. **统一管理**: 所有订单信息（成功和失败）都在一个文件中
2. **便于分析**: 可以按时间顺序查看所有订单操作
3. **完整记录**: 包含订单的所有详细信息（币种、数量、价格、状态、错误等）
4. **易于查询**: 可以使用 CSV 工具或数据库导入后查询分析

## 编译验证

```bash
mvn clean compile -DskipTests
# 结果: BUILD SUCCESS ✅
```

## 注意事项

1. 旧的 `logs/trading-errors.csv` 文件不会自动删除，可以手动删除
2. 所有新的错误信息都会记录到 `order_detail.csv` 中
3. `order_detail.csv` 文件每小时自动滚动，格式为 `order_detail_YYYYMMDDHH.csv`

## 相关问题

- 错误 54000: "Margin trading is not supported" - 需要在 OKX 账户中开通杠杆交易功能
- 如果不需要杠杆交易，可以修改代码使用普通现货交易模式（`tdMode: cash`）

## 下一步

1. 检查 OKX 账户是否开通了杠杆交易功能
2. 如果需要杠杆交易，在 OKX 网站或 APP 中开通
3. 如果不需要杠杆交易，修改代码使用普通现货模式
