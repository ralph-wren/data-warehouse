# TradingDecisionProcessor 性能优化 - 异步 CSV 和内存缓存重构

## 时间
2026-04-11 21:00

## 问题背景
Flink 消费 Kafka 积压严重,经过分析发现主要瓶颈在 `TradingDecisionProcessor`:
1. **CSV 文件写入是同步阻塞操作**:每次写入都调用 `flush()`,立即刷新到磁盘
2. **Redis 操作频繁**:每次处理套利机会都查询 Redis 获取杠杆支持信息
3. **黑名单过滤逻辑复杂**:增加了数据流处理的复杂度

## 解决方案

### 1. 异步 CSV 写入
使用 `AsyncCsvWriter` 替代同步写入:
- 使用 `BlockingQueue` 缓冲日志
- 独立线程池异步写入
- 自动按小时滚动日志文件
- 优雅关闭,确保所有日志都被写入

**性能提升**:
- 不阻塞 Flink 算子
- 批量写入,减少磁盘 I/O
- 大缓冲区(64KB),提升写入效率

### 2. 内存缓存杠杆支持信息
使用 `MarginSupportCache` 替代频繁查询 Redis:
- 启动时从 Redis 加载所有杠杆支持信息到内存
- 提供快速查询接口,查询时间 O(1)
- 线程安全,支持并发访问

**性能提升**:
- 减少 Redis 访问,降低网络延迟
- 内存查询速度远快于网络查询

### 3. 移除黑名单过滤
简化数据流处理逻辑:
- 移除失败策略黑名单逻辑
- 简化 `FlinkADSArbitrageJob` 主类
- 减少状态管理开销

## 代码修改

### 1. TradingDecisionProcessor 重构

#### 添加依赖
```java
import com.crypto.dw.flink.async.AsyncCsvWriter;
import com.crypto.dw.trading.MarginSupportCache;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
```

#### 添加成员变量
```java
private transient MarginSupportCache marginCache;  // 杠杆支持信息缓存
private transient AsyncCsvWriter orderDetailWriter;  // 异步订单明细日志
private transient AsyncCsvWriter positionWriter;     // 异步持仓日志
```

#### 初始化异步写入器
```java
@Override
public void open(Configuration parameters) {
    // 初始化杠杆支持信息缓存
    marginCache = new MarginSupportCache(config);
    
    // 初始化异步 CSV 写入器
    String logDir = config.getString("arbitrage.trading.log-dir", "./logs/trading");
    
    // 订单明细日志
    String[] orderHeaders = {
        "timestamp", "symbol", "action", "direction", "order_type",
        "order_id", "price", "amount", "status", "message"
    };
    orderDetailWriter = new AsyncCsvWriter(logDir, "order_detail", orderHeaders, 10000);
    
    // 持仓日志
    String[] positionHeaders = {
        "timestamp", "symbol", "action", "direction", "amount",
        "entry_spot_price", "entry_swap_price", "exit_spot_price", "exit_swap_price",
        "profit", "profit_rate", "hold_time_seconds", "unrealized_profit"
    };
    positionWriter = new AsyncCsvWriter(logDir, "position", positionHeaders, 10000);
    
    // ... 其他初始化代码
}
```

#### 添加关闭方法
```java
@Override
public void close() throws Exception {
    // 关闭异步 CSV 写入器
    if (orderDetailWriter != null) {
        orderDetailWriter.close();
    }
    if (positionWriter != null) {
        positionWriter.close();
    }
    
    // 关闭杠杆支持信息缓存
    if (marginCache != null) {
        marginCache.close();
    }
    
    logger.info("TradingDecisionProcessor 已关闭");
}
```

#### 开仓时写入异步日志
```java
// 异步写入订单明细日志
String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));

// 现货订单日志
String spotLog = String.format("%s,%s,OPEN,%s,SPOT,%s,%s,%s,PENDING,订单已提交",
    timestamp, opp.symbol, direction, spotOrderId, 
    opp.spotPrice, tradeAmount);
orderDetailWriter.writeAsync(spotLog);

// 合约订单日志
String swapLog = String.format("%s,%s,OPEN,%s,SWAP,%s,%s,%s,PENDING,订单已提交",
    timestamp, opp.symbol, direction, swapOrderId, 
    opp.futuresPrice, tradeAmount);
orderDetailWriter.writeAsync(swapLog);
```

#### 平仓时写入异步日志
```java
// 异步写入持仓日志
String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
String posLog = String.format("%s,%s,CLOSE,%s,%s,%s,%s,%s,%s,%s,%s,%d,N/A",
    timestamp, pending.symbol, position.getDirection(), position.getAmount(),
    position.getEntrySpotPrice(), position.getEntrySwapPrice(),
    pending.spotFillPrice, pending.swapFillPrice,
    profit, profitRate, holdTimeSeconds);
positionWriter.writeAsync(posLog);
```

### 2. FlinkADSArbitrageJob 主类替换

#### 修改导入
```java
import com.crypto.dw.processor.TradingDecisionProcessor;  // 修正包路径
```

#### 移除黑名单过滤
- 移除 `FailedStrategyFilter` 相关代码
- 简化数据流处理逻辑

## 文件变更

### 新建文件
- `data-warehouse/src/main/java/com/crypto/dw/flink/async/AsyncCsvWriter.java` - 异步 CSV 写入器
- `data-warehouse/src/main/java/com/crypto/dw/trading/MarginSupportCache.java` - 杠杆支持信息缓存

### 修改文件
- `data-warehouse/src/main/java/com/crypto/dw/processor/TradingDecisionProcessor.java` - 重构为使用异步写入和内存缓存
- `data-warehouse/src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob.java` - 用精简版替换原有版本

### 备份文件
- `data-warehouse/src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob_backup.java` - 原版本备份
- `data-warehouse/src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob_new.java` - 新版本(已合并到主文件)

## 性能预期

### 吞吐量提升
- **CSV 写入**: 从同步阻塞改为异步,预计提升 10-20 倍
- **Redis 查询**: 从网络查询改为内存查询,预计提升 100 倍以上
- **整体吞吐量**: 预计提升 5-10 倍

### 延迟降低
- **算子处理延迟**: 从 100ms+ 降低到 10ms 以内
- **Kafka 消费延迟**: 从积压数千条降低到实时消费

## 测试建议

### 1. 功能测试
```bash
# 启动 Flink 作业
bash run-flink-ads-arbitrage.sh

# 观察日志
tail -f logs/trading/order_detail_*.csv
tail -f logs/trading/position_*.csv
```

### 2. 性能测试
```bash
# 监控 Kafka 消费延迟
kafka-consumer-groups.sh --bootstrap-server localhost:9092 \
  --group ads-arbitrage --describe

# 监控 Flink 吞吐量
# 访问 Flink Web UI: http://localhost:8086
```

### 3. 压力测试
- 增加 Kafka 生产速率
- 观察 Flink 消费是否能跟上
- 检查 CSV 日志是否完整

## 注意事项

1. **日志目录**: 确保 `./logs/trading` 目录有写权限
2. **队列容量**: `AsyncCsvWriter` 队列容量设置为 10000,如果日志量很大可能需要调整
3. **优雅关闭**: 确保 Flink 作业正常关闭,否则可能丢失部分日志
4. **Redis 数据**: 确保 Redis 中有杠杆支持信息,否则缓存为空

## 后续优化

### 1. WebSocket 下单(待实现)
- 将 OKX API 下单改为 WebSocket 推送
- 推送下单信号到 Kafka Topic
- 由另一个 Flink 作业处理下单

### 2. 监控告警
- 添加 CSV 写入队列满的告警
- 添加 Redis 缓存为空的告警
- 添加 Flink 消费延迟告警

### 3. 日志压缩
- 定期压缩历史 CSV 日志
- 清理过期日志文件

## 总结

本次重构主要解决了 Flink 消费 Kafka 积压的性能瓶颈:
1. ✅ 异步 CSV 写入 - 不阻塞主线程
2. ✅ 内存缓存杠杆支持信息 - 减少 Redis 查询
3. ✅ 移除黑名单过滤 - 简化数据流
4. ✅ 拆分内部类 - 提升可维护性

预计性能提升 5-10 倍,Kafka 消费延迟从积压数千条降低到实时消费。
