# Flink ADS层 - 实时交易信号生成作业命令

## 作业概述

实时交易信号生成系统，使用Flink的7大高级特性：
- CEP（复杂事件处理）
- Broadcast State（广播状态）
- Keyed State（键控状态）
- Side Output（侧输出流）
- Sliding Window（滑动窗口）
- Watermark（水位线）
- Rich Function（富函数）

## 前置准备

### 1. 初始化Redis交易策略

```bash
# 设置交易策略到Redis
chmod +x scripts/init-trading-strategy.sh
./scripts/init-trading-strategy.sh

# 验证策略数量
redis-cli KEYS "trading:strategy:*" | wc -l

# 查看具体策略
redis-cli GET "trading:strategy:BTC-USDT"
```

### 2. 创建Doris表

```bash
# 连接Doris
mysql -h 127.0.0.1 -P 9030 -u root

# 执行建表SQL
source sql/doris/create_ads_trading_signal_table.sql

# 或者直接执行
mysql -h 127.0.0.1 -P 9030 -u root < sql/doris/create_ads_trading_signal_table.sql
```

### 3. 编译项目

```bash
# 清理并编译
mvn clean compile -DskipTests

# 打包
mvn clean package -DskipTests

# 检查JAR文件
ls -lh target/realtime-crypto-datawarehouse-1.0.0.jar
```

## 作业管理

### 启动作业

```bash
# 本地模式启动（开发测试）
mvn exec:java -Dexec.mainClass="com.crypto.dw.flink.FlinkADSTradingSignalJob"

# 使用启动脚本（推荐）
chmod +x run-flink-ads-trading-signal.sh
./run-flink-ads-trading-signal.sh

# YARN模式提交
flink run -t yarn-per-job \
    -d \
    -c com.crypto.dw.flink.FlinkADSTradingSignalJob \
    -p 4 \
    target/realtime-crypto-datawarehouse-1.0.0.jar
```

### 查看作业状态

```bash
# 查看所有运行中的作业
flink list

# 查看作业详情
flink info <job-id>

# 访问Flink Web UI
open http://localhost:8084
```

### 停止作业

```bash
# 取消作业
flink cancel <job-id>

# 带Savepoint取消
flink cancel -s hdfs:///flink/savepoints/ads-trading-signal <job-id>

# 从Savepoint恢复
flink run -s hdfs:///flink/savepoints/ads-trading-signal/savepoint-xxx \
    -c com.crypto.dw.flink.FlinkADSTradingSignalJob \
    target/realtime-crypto-datawarehouse-1.0.0.jar
```

## 数据查询

### 查看交易信号

```sql
-- 查看最新交易信号
SELECT 
    symbol,
    signal_type,
    current_price,
    target_price,
    stop_loss,
    confidence,
    risk_score,
    strategy_type,
    create_time
FROM crypto_ads.ads_trading_signals
ORDER BY timestamp DESC
LIMIT 20;

-- 统计各类信号数量
SELECT 
    signal_type,
    COUNT(*) as signal_count,
    AVG(confidence) as avg_confidence,
    AVG(risk_score) as avg_risk_score
FROM crypto_ads.ads_trading_signals
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY signal_type;

-- 查看高置信度的买入信号
SELECT 
    symbol,
    current_price,
    target_price,
    stop_loss,
    confidence,
    risk_score,
    create_time
FROM crypto_ads.ads_trading_signals
WHERE signal_type = 'BUY'
  AND confidence >= 70
  AND risk_score <= 50
ORDER BY confidence DESC
LIMIT 10;
```

### 查看高风险信号

```sql
-- 查看高风险信号
SELECT 
    symbol,
    signal_type,
    current_price,
    risk_score,
    volatility,
    create_time
FROM crypto_ads.ads_high_risk_signals
ORDER BY risk_score DESC
LIMIT 10;

-- 统计高风险信号分布
SELECT 
    symbol,
    COUNT(*) as high_risk_count,
    AVG(risk_score) as avg_risk_score,
    MAX(risk_score) as max_risk_score
FROM crypto_ads.ads_high_risk_signals
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY symbol
ORDER BY high_risk_count DESC;
```

### 统计分析

```sql
-- 各交易对信号表现
SELECT 
    symbol,
    COUNT(*) as total_signals,
    SUM(CASE WHEN signal_type = 'BUY' THEN 1 ELSE 0 END) as buy_signals,
    SUM(CASE WHEN signal_type = 'SELL' THEN 1 ELSE 0 END) as sell_signals,
    SUM(CASE WHEN signal_type = 'HOLD' THEN 1 ELSE 0 END) as hold_signals,
    AVG(confidence) as avg_confidence,
    AVG(risk_score) as avg_risk_score
FROM crypto_ads.ads_trading_signals
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY symbol
ORDER BY total_signals DESC;

-- 策略类型分析
SELECT 
    strategy_type,
    COUNT(*) as signal_count,
    AVG(confidence) as avg_confidence,
    AVG(risk_score) as avg_risk_score,
    AVG(volatility) as avg_volatility
FROM crypto_ads.ads_trading_signals
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY strategy_type;

-- 时间分布分析
SELECT 
    DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00') as hour,
    COUNT(*) as signal_count,
    AVG(confidence) as avg_confidence
FROM crypto_ads.ads_trading_signals
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 24 HOUR)
GROUP BY DATE_FORMAT(create_time, '%Y-%m-%d %H:00:00')
ORDER BY hour;
```

## Redis策略管理

### 查看策略

```bash
# 查看所有策略
redis-cli KEYS "trading:strategy:*"

# 查看具体策略
redis-cli GET "trading:strategy:BTC-USDT"

# 格式化输出
redis-cli GET "trading:strategy:BTC-USDT" | jq .
```

### 更新策略

```bash
# 更新BTC策略为激进型
redis-cli SET "trading:strategy:BTC-USDT" '{
    "symbol": "BTC-USDT",
    "risk_threshold": "70",
    "volume_threshold": "5000000",
    "price_threshold": "2.0",
    "strategy_type": "AGGRESSIVE",
    "update_time": '$(date +%s)000'
}'

# 更新ETH策略为保守型
redis-cli SET "trading:strategy:ETH-USDT" '{
    "symbol": "ETH-USDT",
    "risk_threshold": "30",
    "volume_threshold": "3000000",
    "price_threshold": "1.0",
    "strategy_type": "CONSERVATIVE",
    "update_time": '$(date +%s)000'
}'
```

### 删除策略

```bash
# 删除单个策略
redis-cli DEL "trading:strategy:DOGE-USDT"

# 删除所有策略
redis-cli KEYS "trading:strategy:*" | xargs redis-cli DEL

# 重新初始化
./scripts/init-trading-strategy.sh
```

## 监控和调试

### 查看日志

```bash
# 查看应用日志
tail -f logs/app/flink-app.log

# 查看错误日志
tail -f logs/error/error.log

# 搜索特定交易对的日志
grep "BTC-USDT" logs/app/flink-app.log

# 搜索CEP模式匹配日志
grep "Breakout" logs/app/flink-app.log
```

### 性能监控

```bash
# 查看Checkpoint状态
curl http://localhost:8084/jobs/<job-id>/checkpoints

# 查看作业指标
curl http://localhost:8084/jobs/<job-id>/metrics

# 查看算子背压
curl http://localhost:8084/jobs/<job-id>/vertices/<vertex-id>/backpressure
```

### 测试脚本

```bash
# 运行测试脚本
chmod +x test/test-trading-signal-job.sh
./test/test-trading-signal-job.sh

# 测试Redis连接
redis-cli -h localhost -p 6379 ping

# 测试Doris连接
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1"

# 测试Kafka Topic
kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic crypto_ticker_spot \
    --max-messages 10
```

## 故障排查

### 常见问题

1. **Redis连接失败**
```bash
# 检查Redis是否运行
redis-cli ping

# 检查Redis配置
cat .env | grep REDIS
```

2. **Doris表不存在**
```bash
# 检查表
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW TABLES FROM crypto_ads"

# 重新创建表
mysql -h 127.0.0.1 -P 9030 -u root < sql/doris/create_ads_trading_signal_table.sql
```

3. **CEP依赖缺失**
```bash
# 检查依赖
jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep "flink-cep"

# 重新编译
mvn clean package -DskipTests
```

4. **数据不写入**
```bash
# 检查Kafka数据
kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic crypto_ticker_spot \
    --max-messages 10

# 检查Flink作业状态
flink list

# 查看作业日志
tail -f logs/app/flink-app.log
```

## 配置调优

### Checkpoint配置

```yaml
# config/application.yml
flink:
  checkpoint:
    interval: 60000      # 1分钟
    timeout: 300000      # 5分钟
    mode: EXACTLY_ONCE
```

### 并行度配置

```bash
# 提交时指定并行度
flink run -p 8 \
    -c com.crypto.dw.flink.FlinkADSTradingSignalJob \
    target/realtime-crypto-datawarehouse-1.0.0.jar
```

### 状态后端配置

```yaml
# config/application.yml
flink:
  state:
    backend: rocksdb
    incremental: true
    checkpoints-dir: hdfs:///flink/checkpoints/ads-trading-signal
```

## 相关文档

- [详细文档](../docs/202603251920-ADS层-实时交易信号生成系统.md)
- [问题解决汇总](../问题解决汇总.md)
- [代码规范](.kiro/steering/code-standards.md)
