# Flink ADS层 - 实时技术指标计算作业命令

## 作业概述

基于TA4J技术分析库的实时技术指标计算系统，支持：
- 100+种内置技术指标（MA、EMA、RSI、MACD、布林带、KDJ、ATR等）
- 动态配置管理（通过Redis）
- 自定义指标扩展
- 多周期支持

## 前置准备

### 1. 初始化Redis指标配置

```bash
# 设置指标配置到Redis
chmod +x scripts/init-technical-indicators.sh
./scripts/init-technical-indicators.sh

# 验证配置数量
redis-cli KEYS "indicator:config:*" | wc -l

# 查看具体配置
redis-cli GET "indicator:config:ma_20" | jq .
```

### 2. 创建Doris表

```bash
# 连接Doris
mysql -h 127.0.0.1 -P 9030 -u root

# 执行建表SQL
source sql/doris/create_ads_technical_indicators_table.sql

# 或者直接执行
mysql -h 127.0.0.1 -P 9030 -u root < sql/doris/create_ads_technical_indicators_table.sql
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
mvn exec:java -Dexec.mainClass="com.crypto.dw.flink.FlinkADSTechnicalIndicatorsJob"

# 使用启动脚本（推荐）
chmod +x run-flink-ads-technical-indicators.sh
./run-flink-ads-technical-indicators.sh

# YARN模式提交
flink run -t yarn-per-job \
    -d \
    -c com.crypto.dw.flink.FlinkADSTechnicalIndicatorsJob \
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
open http://localhost:8085
```

### 停止作业

```bash
# 取消作业
flink cancel <job-id>

# 带Savepoint取消
flink cancel -s hdfs:///flink/savepoints/ads-technical-indicators <job-id>
```

## 指标配置管理

### 查看指标配置

```bash
# 查看所有指标配置
redis-cli KEYS "indicator:config:*"

# 查看具体配置
redis-cli GET "indicator:config:ma_20"

# 格式化输出
redis-cli GET "indicator:config:ma_20" | jq .

# 统计配置数量
redis-cli KEYS "indicator:config:*" | wc -l
```

### 添加内置指标

```bash
# 添加MA30指标
redis-cli SET "indicator:config:ma_30" '{
    "config_id": "ma_30",
    "symbol": "*",
    "indicator_type": "MA",
    "indicator_name": "MA30",
    "params": {"period": "30"},
    "enabled": true,
    "update_time": '$(date +%s)000'
}'

# 添加RSI指标
redis-cli SET "indicator:config:rsi_14" '{
    "config_id": "rsi_14",
    "symbol": "*",
    "indicator_type": "RSI",
    "indicator_name": "RSI14",
    "params": {"period": "14"},
    "enabled": true,
    "update_time": '$(date +%s)000'
}'

# 添加MACD指标
redis-cli SET "indicator:config:macd_default" '{
    "config_id": "macd_default",
    "symbol": "*",
    "indicator_type": "MACD",
    "indicator_name": "MACD",
    "params": {
        "short_period": "12",
        "long_period": "26",
        "signal_period": "9"
    },
    "enabled": true,
    "update_time": '$(date +%s)000'
}'

# 添加布林带指标
redis-cli SET "indicator:config:boll_20" '{
    "config_id": "boll_20",
    "symbol": "*",
    "indicator_type": "BOLL",
    "indicator_name": "BOLL20",
    "params": {
        "period": "20",
        "k": "2.0"
    },
    "enabled": true,
    "update_time": '$(date +%s)000'
}'
```

### 添加自定义指标

```bash
# 添加VWMA（成交量加权移动平均线）
redis-cli SET "indicator:config:vwma_20" '{
    "config_id": "vwma_20",
    "symbol": "*",
    "indicator_type": "CUSTOM",
    "indicator_name": "VWMA20",
    "params": {"period": "20"},
    "custom_class": "com.crypto.dw.indicators.CustomVolumeWeightedMA",
    "enabled": true,
    "update_time": '$(date +%s)000'
}'

# 添加价格通道指标
redis-cli SET "indicator:config:price_channel_20" '{
    "config_id": "price_channel_20",
    "symbol": "*",
    "indicator_type": "CUSTOM",
    "indicator_name": "PriceChannel20",
    "params": {"period": "20"},
    "custom_class": "com.crypto.dw.indicators.CustomPriceChannelIndicator",
    "enabled": true,
    "update_time": '$(date +%s)000'
}'
```

### 为特定交易对配置指标

```bash
# 为BTC-USDT配置特殊的MA50
redis-cli SET "indicator:config:btc_ma_50" '{
    "config_id": "btc_ma_50",
    "symbol": "BTC-USDT",
    "indicator_type": "MA",
    "indicator_name": "BTC_MA50",
    "params": {"period": "50"},
    "enabled": true,
    "update_time": '$(date +%s)000'
}'

# 为ETH-USDT配置RSI
redis-cli SET "indicator:config:eth_rsi_14" '{
    "config_id": "eth_rsi_14",
    "symbol": "ETH-USDT",
    "indicator_type": "RSI",
    "indicator_name": "ETH_RSI14",
    "params": {"period": "14"},
    "enabled": true,
    "update_time": '$(date +%s)000'
}'
```

### 禁用指标

```bash
# 禁用RSI6指标
redis-cli SET "indicator:config:rsi_6" '{
    "config_id": "rsi_6",
    "symbol": "*",
    "indicator_type": "RSI",
    "indicator_name": "RSI6",
    "params": {"period": "6"},
    "enabled": false,
    "update_time": '$(date +%s)000'
}'
```

### 删除指标

```bash
# 删除单个指标
redis-cli DEL "indicator:config:ma_30"

# 删除所有指标
redis-cli KEYS "indicator:config:*" | xargs redis-cli DEL

# 重新初始化
./scripts/init-technical-indicators.sh
```

## 数据查询

### 查看最新指标值

```sql
-- 查看所有指标的最新值
SELECT 
    symbol,
    indicator_type,
    indicator_name,
    indicator_value,
    extra_values,
    create_time
FROM crypto_ads.ads_technical_indicators
ORDER BY timestamp DESC
LIMIT 20;

-- 查看特定交易对的指标
SELECT 
    indicator_name,
    indicator_value,
    extra_values,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE symbol = 'BTC-USDT'
ORDER BY timestamp DESC
LIMIT 10;

-- 查看特定类型的指标
SELECT 
    symbol,
    indicator_name,
    indicator_value,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'MA'
ORDER BY timestamp DESC
LIMIT 10;
```

### 查看MACD指标

```sql
-- 查看MACD指标（包含DIF、DEA、MACD柱）
SELECT 
    symbol,
    indicator_value as macd_bar,
    JSON_EXTRACT(extra_values, '$.dif') as dif,
    JSON_EXTRACT(extra_values, '$.dea') as dea,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'MACD'
  AND symbol = 'BTC-USDT'
ORDER BY timestamp DESC
LIMIT 10;

-- 查找MACD金叉（DIF上穿DEA）
SELECT 
    symbol,
    JSON_EXTRACT(extra_values, '$.dif') as dif,
    JSON_EXTRACT(extra_values, '$.dea') as dea,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'MACD'
  AND JSON_EXTRACT(extra_values, '$.dif') > JSON_EXTRACT(extra_values, '$.dea')
ORDER BY timestamp DESC
LIMIT 10;
```

### 查看布林带指标

```sql
-- 查看布林带（包含上轨、中轨、下轨）
SELECT 
    symbol,
    JSON_EXTRACT(extra_values, '$.upper') as upper_band,
    indicator_value as middle_band,
    JSON_EXTRACT(extra_values, '$.lower') as lower_band,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'BOLL'
  AND symbol = 'ETH-USDT'
ORDER BY timestamp DESC
LIMIT 10;

-- 查找价格触及上轨的情况（需要关联ticker数据）
-- 这里简化为查看布林带宽度
SELECT 
    symbol,
    JSON_EXTRACT(extra_values, '$.upper') - JSON_EXTRACT(extra_values, '$.lower') as band_width,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'BOLL'
ORDER BY timestamp DESC
LIMIT 10;
```

### 查看RSI指标

```sql
-- 查看RSI指标
SELECT 
    symbol,
    indicator_name,
    indicator_value as rsi,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'RSI'
ORDER BY timestamp DESC
LIMIT 10;

-- 查找超买情况（RSI > 70）
SELECT 
    symbol,
    indicator_name,
    indicator_value as rsi,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'RSI'
  AND indicator_value > 70
ORDER BY timestamp DESC
LIMIT 10;

-- 查找超卖情况（RSI < 30）
SELECT 
    symbol,
    indicator_name,
    indicator_value as rsi,
    create_time
FROM crypto_ads.ads_technical_indicators
WHERE indicator_type = 'RSI'
  AND indicator_value < 30
ORDER BY timestamp DESC
LIMIT 10;
```

### 统计分析

```sql
-- 统计各类指标数量
SELECT 
    indicator_type,
    COUNT(DISTINCT indicator_name) as indicator_count,
    COUNT(*) as total_records
FROM crypto_ads.ads_technical_indicators
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY indicator_type;

-- 查看各交易对的指标覆盖情况
SELECT 
    symbol,
    COUNT(DISTINCT indicator_name) as indicator_count,
    COUNT(DISTINCT indicator_type) as type_count
FROM crypto_ads.ads_technical_indicators
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY symbol
ORDER BY indicator_count DESC;

-- 查看指标更新频率
SELECT 
    indicator_name,
    COUNT(*) as update_count,
    MIN(create_time) as first_update,
    MAX(create_time) as last_update
FROM crypto_ads.ads_technical_indicators
WHERE create_time >= DATE_SUB(NOW(), INTERVAL 1 HOUR)
GROUP BY indicator_name
ORDER BY update_count DESC;
```

## 监控和调试

### 查看日志

```bash
# 查看应用日志
tail -f logs/app/flink-app.log

# 查看错误日志
tail -f logs/error/error.log

# 搜索指标相关日志
grep "indicator" logs/app/flink-app.log

# 搜索TA4J相关日志
grep "ta4j" logs/app/flink-app.log
```

### 性能监控

```bash
# 查看Checkpoint状态
curl http://localhost:8085/jobs/<job-id>/checkpoints

# 查看作业指标
curl http://localhost:8085/jobs/<job-id>/metrics

# 查看算子背压
curl http://localhost:8085/jobs/<job-id>/vertices/<vertex-id>/backpressure
```

### 测试脚本

```bash
# 运行测试脚本
chmod +x test/test-technical-indicators-job.sh
./test/test-technical-indicators-job.sh

# 测试Redis连接
redis-cli -h localhost -p 6379 ping

# 测试Doris连接
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1"
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
mysql -h 127.0.0.1 -P 9030 -u root < sql/doris/create_ads_technical_indicators_table.sql
```

3. **TA4J依赖缺失**
```bash
# 检查依赖
jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep "ta4j"

# 重新编译
mvn clean package -DskipTests
```

4. **自定义指标加载失败**
```bash
# 检查类是否存在
jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep "CustomVolumeWeightedMA"

# 查看错误日志
grep "CustomIndicator" logs/error/error.log
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
    -c com.crypto.dw.flink.FlinkADSTechnicalIndicatorsJob \
    target/realtime-crypto-datawarehouse-1.0.0.jar
```

### 状态后端配置

```yaml
# config/application.yml
flink:
  state:
    backend: rocksdb
    incremental: true
    checkpoints-dir: hdfs:///flink/checkpoints/ads-technical-indicators
```

## 相关文档

- [详细文档](../docs/202603251930-ADS层-实时技术指标计算系统.md)
- [交易信号系统](./flink-ads-trading-signal-commands.md)
- [问题解决汇总](../问题解决汇总.md)
