# Doris 数据仓库表结构说明

## 概述

本项目的 Doris 数据仓库采用经典的三层架构设计:
- **ODS 层** (Operational Data Store): 原始数据存储层
- **DWD 层** (Data Warehouse Detail): 明细数据层
- **DWS 层** (Data Warehouse Summary): 汇总数据层

数据流向: **ODS → DWD → DWS**

## 三张表详细说明

### 1. ODS 层 - ods_crypto_ticker_rt (原始数据表)

**作用**: 存储从 OKX 交易所实时采集的原始行情数据,不做任何加工处理

**数据来源**: 
- OKX WebSocket API 实时推送
- Kafka Topic: `crypto-ticker`
- Flink ODS 作业直接写入

**表结构**:
```sql
CREATE TABLE ods_crypto_ticker_rt (
    inst_id VARCHAR(50)           -- 交易对标识 (如 BTC-USDT)
    timestamp BIGINT              -- 业务时间戳（毫秒）
    last_price DECIMAL(20, 8)     -- 最新成交价
    bid_price DECIMAL(20, 8)      -- 买一价
    ask_price DECIMAL(20, 8)      -- 卖一价
    bid_size DECIMAL(20, 8)       -- 买一量
    ask_size DECIMAL(20, 8)       -- 卖一量
    volume_24h DECIMAL(30, 8)     -- 24小时成交量
    high_24h DECIMAL(20, 8)       -- 24小时最高价
    low_24h DECIMAL(20, 8)        -- 24小时最低价
    open_24h DECIMAL(20, 8)       -- 24小时开盘价
    data_source VARCHAR(20)       -- 数据源标识 (默认 'OKX')
    ingest_time BIGINT            -- 数据入库时间戳（毫秒）
)
DUPLICATE KEY(inst_id, timestamp)
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
```

**关键特性**:
- **表类型**: DUPLICATE KEY (允许重复数据,适合原始数据存储)
- **分桶策略**: 按 `inst_id` 哈希分桶,10 个桶
- **数据保留**: 原始数据,不做任何清洗和转换
- **查询优化**: 在 `inst_id` 字段上创建 Bloom Filter 索引

**数据特点**:
- 数据量大,实时写入频繁
- 数据完全保留原始格式
- 适合数据回溯和问题排查
- 不适合直接用于业务分析

**示例数据**:
```
inst_id      | timestamp       | last_price | bid_price | ask_price | volume_24h
-------------|-----------------|------------|-----------|-----------|------------
BTC-USDT     | 1711065600000   | 68500.50   | 68500.00  | 68501.00  | 1234567.89
ETH-USDT     | 1711065600000   | 3450.25    | 3450.00   | 3450.50   | 987654.32
```

---

### 2. DWD 层 - dwd_crypto_ticker_detail (明细数据表)

**作用**: 存储经过清洗、转换、计算后的明细数据,添加了业务维度和指标

**数据来源**: 
- 从 ODS 层读取原始数据
- Flink DWD 作业进行数据清洗和转换
- 添加计算字段和业务维度

**表结构**:
```sql
CREATE TABLE dwd_crypto_ticker_detail (
    -- 基础维度
    inst_id VARCHAR(50)              -- 交易对标识
    timestamp BIGINT                 -- 业务时间戳（毫秒）
    trade_date DATE                  -- 交易日期 (新增维度)
    trade_hour INT                   -- 交易小时 (新增维度)
    
    -- 价格字段
    last_price DECIMAL(20, 8)        -- 最新成交价
    bid_price DECIMAL(20, 8)         -- 买一价
    ask_price DECIMAL(20, 8)         -- 卖一价
    
    -- 计算指标 (新增)
    spread DECIMAL(20, 8)            -- 买卖价差 = ask_price - bid_price
    spread_rate DECIMAL(10, 6)       -- 价差率 = spread / bid_price
    
    -- 24小时统计
    volume_24h DECIMAL(30, 8)        -- 24小时成交量
    high_24h DECIMAL(20, 8)          -- 24小时最高价
    low_24h DECIMAL(20, 8)           -- 24小时最低价
    open_24h DECIMAL(20, 8)          -- 24小时开盘价
    
    -- 涨跌指标 (新增)
    price_change_24h DECIMAL(20, 8)      -- 24小时涨跌额 = last_price - open_24h
    price_change_rate_24h DECIMAL(10, 6) -- 24小时涨跌幅 = price_change_24h / open_24h
    amplitude_24h DECIMAL(10, 6)         -- 24小时振幅 = (high_24h - low_24h) / open_24h
    
    -- 元数据
    data_source VARCHAR(20)          -- 数据源标识
    ingest_time BIGINT               -- 数据入库时间
    process_time BIGINT              -- 数据处理时间 (新增)
)
DUPLICATE KEY(inst_id, timestamp)
PARTITION BY RANGE(trade_date)      -- 按日期分区
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
```

**关键特性**:
- **表类型**: DUPLICATE KEY (保留明细数据)
- **分区策略**: 按 `trade_date` 日期分区,动态分区管理
  - 保留最近 7 天历史数据
  - 预创建未来 3 天分区
  - 自动删除过期分区
- **分桶策略**: 按 `inst_id` 哈希分桶,10 个桶
- **数据增强**: 添加了时间维度、计算指标、业务字段

**数据转换逻辑**:
```sql
-- Flink SQL 转换示例
SELECT 
    inst_id,
    timestamp,
    -- 新增时间维度
    DATE(FROM_UNIXTIME(timestamp / 1000)) as trade_date,
    HOUR(FROM_UNIXTIME(timestamp / 1000)) as trade_hour,
    
    -- 原始价格字段
    last_price,
    bid_price,
    ask_price,
    
    -- 计算买卖价差
    (ask_price - bid_price) as spread,
    CASE 
        WHEN bid_price > 0 THEN (ask_price - bid_price) / bid_price 
        ELSE 0 
    END as spread_rate,
    
    -- 24小时统计
    volume_24h,
    high_24h,
    low_24h,
    open_24h,
    
    -- 计算涨跌指标
    (last_price - open_24h) as price_change_24h,
    CASE 
        WHEN open_24h > 0 THEN (last_price - open_24h) / open_24h 
        ELSE 0 
    END as price_change_rate_24h,
    CASE 
        WHEN open_24h > 0 THEN (high_24h - low_24h) / open_24h 
        ELSE 0 
    END as amplitude_24h,
    
    data_source,
    ingest_time,
    UNIX_TIMESTAMP() * 1000 as process_time
FROM ods_crypto_ticker_rt
```

**数据特点**:
- 数据经过清洗和转换
- 添加了业务维度(日期、小时)
- 添加了计算指标(价差、涨跌幅、振幅)
- 适合业务分析和报表查询
- 支持按日期分区查询,性能优化

**示例数据**:
```
inst_id   | timestamp     | trade_date | trade_hour | last_price | spread | spread_rate | price_change_rate_24h
----------|---------------|------------|------------|------------|--------|-------------|----------------------
BTC-USDT  | 1711065600000 | 2026-03-22 | 12         | 68500.50   | 1.00   | 0.000015    | 0.0235
ETH-USDT  | 1711065600000 | 2026-03-22 | 12         | 3450.25    | 0.50   | 0.000145    | 0.0189
```

---

### 3. DWS 层 - dws_crypto_ticker_1min (1分钟K线汇总表)

**作用**: 存储按 1 分钟窗口聚合的 K 线数据,用于趋势分析和图表展示

**数据来源**: 
- 从 DWD 层读取明细数据
- Flink 窗口聚合计算
- 按 1 分钟滚动窗口聚合

**表结构**:
```sql
CREATE TABLE dws_crypto_ticker_1min (
    -- 维度字段
    inst_id VARCHAR(50)              -- 交易对标识
    window_start BIGINT              -- 窗口开始时间戳
    window_end BIGINT                -- 窗口结束时间戳
    window_size INT                  -- 窗口大小（分钟,默认 1）
    
    -- K线四价 (OHLC)
    open_price DECIMAL(20, 8)        -- 开盘价 (窗口内第一个价格)
    high_price DECIMAL(20, 8)        -- 最高价 (窗口内最大价格)
    low_price DECIMAL(20, 8)         -- 最低价 (窗口内最小价格)
    close_price DECIMAL(20, 8)       -- 收盘价 (窗口内最后一个价格)
    
    -- 统计指标
    volume DECIMAL(30, 8)            -- 成交量 (窗口内累计)
    avg_price DECIMAL(20, 8)         -- 平均价 (窗口内均价)
    price_change DECIMAL(20, 8)      -- 涨跌额 = close_price - open_price
    price_change_rate DECIMAL(10, 6) -- 涨跌幅 = price_change / open_price
    tick_count INT                   -- Tick 数量 (窗口内数据条数)
    
    -- 元数据
    process_time BIGINT              -- 处理时间戳
)
AGGREGATE KEY(inst_id, window_start, window_end, window_size)
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
```

**关键特性**:
- **表类型**: AGGREGATE KEY (聚合模型)
  - `open_price`: REPLACE (保留最新值)
  - `high_price`: MAX (取最大值)
  - `low_price`: MIN (取最小值)
  - `close_price`: REPLACE (保留最新值)
  - `volume`: SUM (累加)
  - `tick_count`: SUM (累加)
- **分桶策略**: 按 `inst_id` 哈希分桶,10 个桶
- **聚合逻辑**: 自动按 KEY 字段聚合,VALUE 字段按聚合函数计算

**数据聚合逻辑**:
```sql
-- Flink SQL 窗口聚合示例
SELECT 
    inst_id,
    UNIX_TIMESTAMP(TUMBLE_START(ts, INTERVAL '1' MINUTE)) * 1000 as window_start,
    UNIX_TIMESTAMP(TUMBLE_END(ts, INTERVAL '1' MINUTE)) * 1000 as window_end,
    1 as window_size,
    
    -- K线四价
    FIRST_VALUE(last_price) as open_price,   -- 第一个价格
    MAX(last_price) as high_price,           -- 最高价
    MIN(last_price) as low_price,            -- 最低价
    LAST_VALUE(last_price) as close_price,   -- 最后一个价格
    
    -- 统计指标
    SUM(volume_24h) as volume,               -- 累计成交量
    AVG(last_price) as avg_price,            -- 平均价
    LAST_VALUE(last_price) - FIRST_VALUE(last_price) as price_change,
    CASE 
        WHEN FIRST_VALUE(last_price) > 0 
        THEN (LAST_VALUE(last_price) - FIRST_VALUE(last_price)) / FIRST_VALUE(last_price)
        ELSE 0 
    END as price_change_rate,
    COUNT(*) as tick_count,
    
    UNIX_TIMESTAMP() * 1000 as process_time
FROM dwd_crypto_ticker_detail
GROUP BY 
    inst_id,
    TUMBLE(ts, INTERVAL '1' MINUTE)
```

**数据特点**:
- 数据量大幅减少(每分钟一条记录)
- 适合绘制 K 线图和趋势分析
- 支持技术指标计算(MA、MACD、RSI 等)
- 查询性能高,适合实时监控

**示例数据**:
```
inst_id   | window_start  | window_end    | open_price | high_price | low_price | close_price | volume    | tick_count
----------|---------------|---------------|------------|------------|-----------|-------------|-----------|------------
BTC-USDT  | 1711065600000 | 1711065660000 | 68500.00   | 68550.00   | 68480.00  | 68520.00    | 12345.67  | 60
ETH-USDT  | 1711065600000 | 1711065660000 | 3450.00    | 3455.00    | 3448.00   | 3452.00     | 9876.54   | 60
```

---

## 数据流转关系

```
┌─────────────────────────────────────────────────────────────────┐
│                        数据流转架构                              │
└─────────────────────────────────────────────────────────────────┘

1. 数据采集
   ┌──────────────┐
   │ OKX WebSocket│  实时推送行情数据
   └──────┬───────┘
          │
          ▼
   ┌──────────────┐
   │    Kafka     │  消息队列缓冲
   │crypto-ticker │
   └──────┬───────┘
          │
          ▼

2. ODS 层 (原始数据)
   ┌──────────────────────────────────┐
   │  Flink ODS Job (DataStream API)  │  直接写入,不做转换
   └──────────────┬───────────────────┘
                  │
                  ▼
   ┌──────────────────────────────────┐
   │  ods_crypto_ticker_rt            │  原始数据表
   │  - 15 个交易对                    │
   │  - 实时写入                       │
   │  - 数据量: ~1000 条/秒            │
   └──────────────┬───────────────────┘
                  │
                  ▼

3. DWD 层 (明细数据)
   ┌──────────────────────────────────┐
   │  Flink DWD Job (Table API)       │  数据清洗和转换
   │  - 添加时间维度                   │
   │  - 计算业务指标                   │
   │  - 数据质量检查                   │
   └──────────────┬───────────────────┘
                  │
                  ▼
   ┌──────────────────────────────────┐
   │  dwd_crypto_ticker_detail        │  明细数据表
   │  - 19 个字段                      │
   │  - 按日期分区                     │
   │  - 数据量: ~1000 条/秒            │
   │  - 保留 7 天数据                  │
   └──────────────┬───────────────────┘
                  │
                  ▼

4. DWS 层 (汇总数据)
   ┌──────────────────────────────────┐
   │  Flink DWS Job (Window)          │  窗口聚合计算
   │  - 1分钟滚动窗口                  │
   │  - 计算 K 线四价                  │
   │  - 统计指标                       │
   └──────────────┬───────────────────┘
                  │
                  ▼
   ┌──────────────────────────────────┐
   │  dws_crypto_ticker_1min          │  1分钟K线表
   │  - 14 个字段                      │
   │  - 数据量: ~15 条/分钟            │
   │  - 适合趋势分析                   │
   └──────────────────────────────────┘
```

---

## 数据量对比

| 层级 | 表名 | 数据频率 | 每秒写入 | 每天数据量 | 数据保留 |
|-----|------|---------|---------|-----------|---------|
| ODS | ods_crypto_ticker_rt | 实时 | ~1000 条 | ~8640 万条 | 永久 |
| DWD | dwd_crypto_ticker_detail | 实时 | ~1000 条 | ~8640 万条 | 7 天 |
| DWS | dws_crypto_ticker_1min | 1分钟 | ~0.25 条 | ~2.16 万条 | 永久 |

**数据压缩比**: DWS 层数据量约为 ODS 层的 **0.025%**

---

## 查询场景

### ODS 层查询场景

**适合**:
- 数据回溯和问题排查
- 原始数据导出
- 数据质量检查

**示例查询**:
```sql
-- 查询某个交易对的原始数据
SELECT * FROM ods_crypto_ticker_rt
WHERE inst_id = 'BTC-USDT'
  AND timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
ORDER BY timestamp DESC
LIMIT 100;
```

### DWD 层查询场景

**适合**:
- 业务分析和报表
- 实时监控仪表板
- 数据可视化

**示例查询**:
```sql
-- 查询各币种最新价格和涨跌幅
SELECT 
  inst_id AS 币种,
  last_price AS 最新价格,
  price_change_rate_24h AS 涨跌幅,
  volume_24h AS 成交量,
  FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
  SELECT 
    *,
    ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
  FROM dwd_crypto_ticker_detail
  WHERE trade_date = CURDATE()
) t
WHERE rn = 1
ORDER BY price_change_rate_24h DESC;
```

### DWS 层查询场景

**适合**:
- K 线图绘制
- 趋势分析
- 技术指标计算

**示例查询**:
```sql
-- 查询 BTC 最近 1 小时的 K 线数据
SELECT 
  FROM_UNIXTIME(window_start / 1000) AS 时间,
  open_price AS 开盘价,
  high_price AS 最高价,
  low_price AS 最低价,
  close_price AS 收盘价,
  volume AS 成交量
FROM dws_crypto_ticker_1min
WHERE inst_id = 'BTC-USDT'
  AND window_start >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
ORDER BY window_start;
```

---

## 表设计要点

### 1. 表类型选择

| 表类型 | 适用场景 | 优势 | 劣势 |
|-------|---------|------|------|
| DUPLICATE KEY | ODS、DWD 层 | 保留所有数据,支持明细查询 | 数据量大,存储成本高 |
| AGGREGATE KEY | DWS 层 | 自动聚合,数据量小 | 不支持明细查询 |
| UNIQUE KEY | 维度表 | 自动去重,保证唯一性 | 更新性能较低 |

### 2. 分区策略

**DWD 层动态分区配置**:
```sql
"dynamic_partition.enable" = "true",      -- 启用动态分区
"dynamic_partition.time_unit" = "DAY",    -- 按天分区
"dynamic_partition.start" = "-7",         -- 保留最近 7 天
"dynamic_partition.end" = "3",            -- 预创建未来 3 天
"dynamic_partition.prefix" = "p",         -- 分区前缀
"dynamic_partition.buckets" = "10"        -- 每个分区 10 个桶
```

**优势**:
- 自动创建和删除分区
- 查询性能优化(分区裁剪)
- 存储成本控制

### 3. 分桶策略

**按 inst_id 哈希分桶**:
```sql
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
```

**优势**:
- 数据均匀分布
- 并行查询性能好
- 支持 Colocate Join

### 4. 索引优化

**Bloom Filter 索引**:
```sql
"bloom_filter_columns" = "inst_id"
```

**优势**:
- 加速等值查询
- 减少数据扫描量
- 适合高基数列

---

## 总结

| 层级 | 表名 | 核心作用 | 数据特点 | 适用场景 |
|-----|------|---------|---------|---------|
| ODS | ods_crypto_ticker_rt | 原始数据存储 | 完整保留,不做转换 | 数据回溯、问题排查 |
| DWD | dwd_crypto_ticker_detail | 明细数据分析 | 清洗转换,添加维度和指标 | 业务分析、实时监控 |
| DWS | dws_crypto_ticker_1min | K线汇总统计 | 窗口聚合,数据量小 | 趋势分析、图表展示 |

**数据流转**: OKX → Kafka → ODS → DWD → DWS → BI 工具

**推荐使用**:
- 日常监控和分析: 使用 **DWD 层**
- K 线图和趋势: 使用 **DWS 层**
- 问题排查: 使用 **ODS 层**
