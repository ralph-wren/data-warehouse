# 空指针异常 - 修复 OrderUpdateParser 的 BigDecimal 转换错误

## 问题描述

Flink 作业运行时出现 `NumberFormatException: null` 错误：

```
java.lang.NumberFormatException: null
at java.math.BigDecimal.<init>(BigDecimal.java:628)
at com.crypto.dw.flink.processor.OrderUpdateParser.map(OrderUpdateParser.java:47)
```

错误发生在 `OrderUpdateParser.map` 方法的第47行，尝试将 null 值转换为 BigDecimal 时出错。

## 根本原因

当订单未成交或部分成交时，OKX WebSocket 返回的订单更新消息中，以下字段可能为空字符串或不存在：
- `fillPx`（成交价格）
- `fillSz`（成交数量）
- `fee`（手续费）

代码直接使用 `new BigDecimal(orderNode.get("fillPx").asText())` 进行转换，当字段值为空字符串时，会抛出 `NumberFormatException`。

## 解决方案

在创建 BigDecimal 对象之前，添加空值检查：

1. 检查字段是否存在（`orderNode.has("fillPx")`）
2. 获取字段值并检查是否为空或 "0"
3. 只有在有效值时才创建 BigDecimal 对象

### 修改代码

```java
// 成交价格和数量（添加空值检查，避免 NumberFormatException）
if (orderNode.has("fillPx")) {
    String fillPxStr = orderNode.get("fillPx").asText();
    if (fillPxStr != null && !fillPxStr.isEmpty() && !fillPxStr.equals("0")) {
        order.fillPrice = new BigDecimal(fillPxStr);
    }
}
if (orderNode.has("fillSz")) {
    String fillSzStr = orderNode.get("fillSz").asText();
    if (fillSzStr != null && !fillSzStr.isEmpty() && !fillSzStr.equals("0")) {
        order.fillSize = new BigDecimal(fillSzStr);
    }
}

// 手续费信息（添加空值检查）
if (orderNode.has("fee")) {
    String feeStr = orderNode.get("fee").asText();
    if (feeStr != null && !feeStr.isEmpty() && !feeStr.equals("0")) {
        order.fee = new BigDecimal(feeStr);
    }
}
```

## 修改文件

- `data-warehouse/src/main/java/com/crypto/dw/flink/processor/OrderUpdateParser.java`

## 验证结果

编译成功，无错误。

## 注意事项

1. 订单状态为 `live`（未成交）或 `partially_filled`（部分成交）时，`fillPx`、`fillSz`、`fee` 可能为空
2. 只有订单状态为 `filled`（完全成交）时，这些字段才会有有效值
3. 空值检查确保了代码的健壮性，避免了运行时异常

## 相关文档

- [202604112230-订单状态-添加订单失败处理逻辑.md](202604112230-订单状态-添加订单失败处理逻辑.md)
- [202604112300-持仓监控-每5秒打印利润率并异步查询订单详情.md](202604112300-持仓监控-每5秒打印利润率并异步查询订单详情.md)
