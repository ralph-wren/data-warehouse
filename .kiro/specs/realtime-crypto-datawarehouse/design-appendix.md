# 数仓建设规范和 Doris 建表最佳实践

## 数仓建设规范

### 1. 数仓分层规范

#### 1.1 ODS 层（Operational Data Store - 操作数据存储层）

**定位**: 原始数据层，保持数据源的原始性和完整性

**命名规范**:
- 表名格式: `ods_{业务域}_{表名}_{rt/di}`
- rt: 实时表（real-time）
- di: 离线表（daily increment）
- 示例: `ods_crypto_ticker_rt`

**设计原则**:
- 保持与数据源一致的字段结构
- 不做业务逻辑处理，仅做基础清洗
- 添加数据入库时间字段 `ingest_time`
- 添加数据源标识字段 `data_source`
- 使用 DUPLICATE KEY 模型支持高并发写入
- 设置合理的数据保留周期（建议 7-30 天）

**字段规范**:
```sql
-- 必备字段
data_source VARCHAR(20)      -- 数据源标识
ingest_time BIGINT           -- 数据入库时间戳（毫秒）
```

#### 1.2 DWD 层（Data Warehouse Detail - 明细数据层）

**定位**: 清洗后的明细数据层，面向业务过程建模

**命名规范**:
- 表名格式: `dwd_{业务域}_{业务过程}_detail`
- 示例: `dwd_crypto_ticker_detail`

**设计原则**:
- 数据清洗和标准化处理
- 添加业务含义的衍生字段
- 添加数据处理时间字段 `process_time`
- 使用动态分区管理历史数据
- 按日期分区，按业务主键分桶
- 数据保留周期建议 30-90 天

**字段规范**:
```sql
-- 必备字段
process_time BIGINT          -- 数据处理时间戳（毫秒）
trade_date DATE              -- 交易日期（用于分区）
```


#### 1.3 DWS 层（Data Warehouse Summary - 汇总数据层）

**定位**: 轻度聚合层，面向分析主题建模

**命名规范**:
- 表名格式: `dws_{业务域}_{聚合粒度}_{统计周期}`
- 示例: `dws_crypto_ticker_1min`, `dws_crypto_ticker_5min`

**设计原则**:
- 按时间窗口聚合统计
- 计算常用业务指标
- 使用 AGGREGATE KEY 模型优化聚合查询
- 预计算技术指标（MA、EMA 等）
- 数据保留周期建议 90-180 天

**字段规范**:
```sql
-- 必备字段
window_start BIGINT          -- 窗口开始时间戳
window_end BIGINT            -- 窗口结束时间戳
window_size INT              -- 窗口大小（分钟）
process_time BIGINT          -- 处理时间戳
```

#### 1.4 ADS 层（Application Data Store - 应用数据层）

**定位**: 应用层数据，直接支撑业务应用和报表

**命名规范**:
- 表名格式: `ads_{业务主题}_{应用场景}`
- 示例: `ads_crypto_market_overview`

**设计原则**:
- 高度聚合的业务指标
- 面向具体应用场景设计
- 宽表设计，减少 JOIN 操作
- 数据更新频率与业务需求匹配
- 数据保留周期建议 180-365 天

**字段规范**:
```sql
-- 必备字段
timestamp BIGINT             -- 统计时间戳
process_time BIGINT          -- 处理时间戳
```

### 2. 字段命名规范

#### 2.1 命名风格
- 使用小写字母和下划线分隔（snake_case）
- 避免使用保留关键字
- 字段名应具有业务含义，避免缩写

#### 2.2 常用字段命名
```sql
-- 时间相关
timestamp BIGINT             -- 业务时间戳（毫秒）
trade_date DATE              -- 交易日期
trade_hour INT               -- 交易小时
ingest_time BIGINT           -- 入库时间
process_time BIGINT          -- 处理时间

-- 标识相关
inst_id VARCHAR(50)          -- 交易对标识
data_source VARCHAR(20)      -- 数据源标识

-- 价格相关
last_price DECIMAL(20, 8)    -- 最新价格
open_price DECIMAL(20, 8)    -- 开盘价
high_price DECIMAL(20, 8)    -- 最高价
low_price DECIMAL(20, 8)     -- 最低价
close_price DECIMAL(20, 8)   -- 收盘价

-- 数量相关
volume DECIMAL(30, 8)        -- 成交量
amount DECIMAL(30, 8)        -- 成交额

-- 统计相关
count INT                    -- 计数
avg_value DECIMAL(20, 8)     -- 平均值
max_value DECIMAL(20, 8)     -- 最大值
min_value DECIMAL(20, 8)     -- 最小值
```


### 3. 数据类型规范

#### 3.1 数据类型选择原则
- 根据业务场景选择合适的数据类型
- 优先使用定长类型提升性能
- 价格和金额使用 DECIMAL 避免精度损失
- 时间戳统一使用 BIGINT（毫秒）

#### 3.2 常用数据类型映射

| 业务场景 | 推荐类型 | 说明 |
|---------|---------|------|
| 交易对标识 | VARCHAR(50) | 定长字符串 |
| 价格 | DECIMAL(20, 8) | 20位总长度，8位小数 |
| 成交量 | DECIMAL(30, 8) | 支持大数值 |
| 时间戳 | BIGINT | 毫秒级时间戳 |
| 日期 | DATE | 分区字段 |
| 小时 | INT | 0-23 |
| 百分比 | DECIMAL(10, 6) | 6位小数精度 |
| 计数 | INT | 整数计数 |
| 标识符 | VARCHAR | 根据实际长度定义 |

### 4. 数据质量规范

#### 4.1 数据完整性
- 主键字段不允许为 NULL
- 关键业务字段设置 NOT NULL 约束
- 外键关联字段保持一致性

#### 4.2 数据准确性
- 价格字段必须大于 0
- 百分比字段在合理范围内（-100% ~ 1000%）
- 时间戳在合理范围内（当前时间前后一定时间窗口）

#### 4.3 数据一致性
- OHLC 数据满足逻辑关系：high >= max(open, close), low <= min(open, close)
- 聚合数据与明细数据可对账
- 跨层数据保持一致性

#### 4.4 数据及时性
- ODS 层延迟 < 10 秒
- DWD 层延迟 < 30 秒
- DWS 层延迟 < 1 分钟
- ADS 层延迟 < 5 分钟

### 5. 数据生命周期管理

#### 5.1 数据保留策略

| 数据层 | 保留周期 | 分区策略 | 清理方式 |
|-------|---------|---------|---------|
| ODS | 7-30 天 | 按日分区 | 动态分区自动删除 |
| DWD | 30-90 天 | 按日分区 | 动态分区自动删除 |
| DWS | 90-180 天 | 按日分区 | 动态分区自动删除 |
| ADS | 180-365 天 | 按月分区 | 手动归档 |

#### 5.2 动态分区配置示例
```sql
PROPERTIES (
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",        -- 保留7天历史
    "dynamic_partition.end" = "3",           -- 提前创建3天分区
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);
```


## Doris 建表最佳实践

### 1. 数据模型选择

#### 1.1 DUPLICATE KEY 模型

**适用场景**:
- 原始数据存储（ODS 层）
- 明细数据查询（DWD 层）
- 需要保留所有数据记录
- 高并发写入场景

**特点**:
- 允许数据重复
- 不做任何聚合
- 写入性能最高
- 适合日志、明细数据

**示例**:
```sql
CREATE TABLE ods_crypto_ticker_rt (
    inst_id VARCHAR(50),
    timestamp BIGINT,
    last_price DECIMAL(20, 8),
    -- 其他字段...
    data_source VARCHAR(20),
    ingest_time BIGINT
)
DUPLICATE KEY(inst_id, timestamp)
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1"
);
```

#### 1.2 AGGREGATE KEY 模型

**适用场景**:
- 聚合统计场景（DWS 层）
- 需要预聚合的指标
- 减少存储空间
- 加速聚合查询

**特点**:
- 自动聚合相同 Key 的数据
- 支持 SUM、MAX、MIN、REPLACE 等聚合函数
- 写入时自动聚合，查询时直接返回结果

**示例**:
```sql
CREATE TABLE dws_crypto_ticker_1min (
    inst_id VARCHAR(50),
    window_start BIGINT,
    window_end BIGINT,
    window_size INT,
    open_price DECIMAL(20, 8) REPLACE,
    high_price DECIMAL(20, 8) MAX,
    low_price DECIMAL(20, 8) MIN,
    close_price DECIMAL(20, 8) REPLACE,
    volume DECIMAL(30, 8) SUM,
    tick_count INT SUM
)
AGGREGATE KEY(inst_id, window_start, window_end, window_size)
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1"
);
```

#### 1.3 UNIQUE KEY 模型

**适用场景**:
- 需要主键唯一性约束
- 支持更新操作
- 维度表
- 配置表

**特点**:
- 保证主键唯一
- 支持 UPDATE 和 DELETE
- 新数据覆盖旧数据

**示例**:
```sql
CREATE TABLE dim_crypto_symbol (
    inst_id VARCHAR(50),
    base_currency VARCHAR(20),
    quote_currency VARCHAR(20),
    symbol_name VARCHAR(100),
    update_time BIGINT
)
UNIQUE KEY(inst_id)
DISTRIBUTED BY HASH(inst_id) BUCKETS 5
PROPERTIES (
    "replication_num" = "1"
);
```

### 2. 分区策略

#### 2.1 Range 分区（推荐用于时间序列数据）

**适用场景**:
- 按日期分区的时间序列数据
- 需要定期清理历史数据
- 查询通常带有时间范围条件

**优势**:
- 分区裁剪提升查询性能
- 便于数据生命周期管理
- 支持动态分区自动创建和删除

**示例**:
```sql
CREATE TABLE dwd_crypto_ticker_detail (
    inst_id VARCHAR(50),
    timestamp BIGINT,
    trade_date DATE,
    -- 其他字段...
)
DUPLICATE KEY(inst_id, timestamp)
PARTITION BY RANGE(trade_date) ()
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);
```

#### 2.2 List 分区

**适用场景**:
- 按枚举值分区（地区、类型等）
- 分区数量有限且固定

**示例**:
```sql
PARTITION BY LIST(region) (
    PARTITION p_asia VALUES IN ("CN", "JP", "KR"),
    PARTITION p_europe VALUES IN ("UK", "DE", "FR"),
    PARTITION p_america VALUES IN ("US", "CA")
)
```


### 3. 分桶策略

#### 3.1 分桶数量选择

**计算公式**:
```
分桶数 = 数据量(GB) / 单桶大小(GB)
推荐单桶大小: 1-5 GB
```

**推荐配置**:
- 小表（< 10GB）: 5-10 个 Bucket
- 中表（10-100GB）: 10-20 个 Bucket
- 大表（> 100GB）: 20-50 个 Bucket

**注意事项**:
- 分桶数建议为 2 的幂次方或接近
- 分桶数不宜过多（避免小文件问题）
- 分桶数不宜过少（影响并行度）

#### 3.2 分桶键选择

**选择原则**:
- 选择高基数字段（唯一值多）
- 选择查询常用的过滤字段
- 避免数据倾斜

**示例**:
```sql
-- 好的分桶键选择
DISTRIBUTED BY HASH(inst_id) BUCKETS 10        -- inst_id 基数高，分布均匀

-- 不好的分桶键选择
DISTRIBUTED BY HASH(trade_hour) BUCKETS 10     -- trade_hour 只有 24 个值，数据倾斜
```

### 4. 索引优化

#### 4.1 前缀索引（Prefix Index）

**原理**:
- Doris 自动为 Key 列创建前缀索引
- 前缀索引长度默认 36 字节
- 查询条件匹配前缀索引可加速查询

**优化建议**:
- 将高频查询字段放在 Key 的前面
- 将高基数字段放在前面
- 控制 Key 列数量（建议 3-5 个）

**示例**:
```sql
-- 优化前：timestamp 在前，inst_id 在后
DUPLICATE KEY(timestamp, inst_id)

-- 优化后：inst_id 在前，timestamp 在后
-- 因为查询通常先过滤 inst_id，再过滤时间范围
DUPLICATE KEY(inst_id, timestamp)
```

#### 4.2 Bloom Filter 索引

**适用场景**:
- 高基数字段的等值查询
- 字符串类型字段
- 减少数据扫描量

**创建方式**:
```sql
CREATE TABLE ods_crypto_ticker_rt (
    inst_id VARCHAR(50),
    timestamp BIGINT,
    -- 其他字段...
)
DUPLICATE KEY(inst_id, timestamp)
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "bloom_filter_columns" = "inst_id"
);
```

#### 4.3 倒排索引（Inverted Index）

**适用场景**:
- 全文搜索
- 模糊查询
- 多条件组合查询

**创建方式**:
```sql
CREATE TABLE crypto_news (
    id BIGINT,
    title VARCHAR(200),
    content TEXT,
    publish_time BIGINT,
    INDEX idx_title (title) USING INVERTED,
    INDEX idx_content (content) USING INVERTED
)
DUPLICATE KEY(id)
DISTRIBUTED BY HASH(id) BUCKETS 5;
```

### 5. 存储优化

#### 5.1 压缩算法选择

**压缩算法对比**:

| 压缩算法 | 压缩率 | 压缩速度 | 解压速度 | 适用场景 |
|---------|-------|---------|---------|---------|
| LZ4 | 中等 | 快 | 快 | 默认推荐，平衡性能和压缩率 |
| ZSTD | 高 | 中等 | 快 | 存储空间敏感场景 |
| SNAPPY | 低 | 快 | 快 | 性能优先场景 |
| ZLIB | 高 | 慢 | 中等 | 归档数据 |

**配置示例**:
```sql
PROPERTIES (
    "compression" = "LZ4"
);
```

#### 5.2 存储介质选择

**配置示例**:
```sql
-- 热数据使用 SSD
PROPERTIES (
    "storage_medium" = "SSD",
    "storage_cooldown_time" = "2024-12-31 23:59:59"
);

-- 冷数据使用 HDD
PROPERTIES (
    "storage_medium" = "HDD"
);
```


### 6. 性能优化最佳实践

#### 6.1 批量导入优化

**Stream Load 配置**:
```bash
curl --location-trusted -u root: \
    -H "label:label_$(date +%s)" \
    -H "column_separator:," \
    -H "max_filter_ratio:0.1" \
    -H "timeout:600" \
    -T data.csv \
    http://localhost:8030/api/crypto_dw/ods_crypto_ticker_rt/_stream_load
```

**Flink Doris Connector 优化**:
```java
DorisExecutionOptions options = DorisExecutionOptions.builder()
    .setBatchSize(1000)              // 批量大小
    .setBatchIntervalMs(5000)        // 批量间隔
    .setMaxRetries(3)                // 重试次数
    .setStreamLoadProp(properties)   // Stream Load 参数
    .build();
```

#### 6.2 查询优化

**使用分区裁剪**:
```sql
-- 好的查询：使用分区字段过滤
SELECT * FROM dwd_crypto_ticker_detail
WHERE trade_date >= '2024-01-01'
    AND trade_date <= '2024-01-31'
    AND inst_id = 'BTC-USDT';

-- 不好的查询：不使用分区字段
SELECT * FROM dwd_crypto_ticker_detail
WHERE timestamp >= 1704067200000
    AND inst_id = 'BTC-USDT';
```

**使用 Bucket 裁剪**:
```sql
-- 好的查询：使用分桶键过滤
SELECT * FROM dwd_crypto_ticker_detail
WHERE inst_id = 'BTC-USDT';

-- 不好的查询：不使用分桶键
SELECT * FROM dwd_crypto_ticker_detail
WHERE last_price > 50000;
```

**避免 SELECT ***:
```sql
-- 好的查询：只查询需要的字段
SELECT inst_id, timestamp, last_price
FROM dwd_crypto_ticker_detail
WHERE inst_id = 'BTC-USDT'
LIMIT 100;

-- 不好的查询：查询所有字段
SELECT *
FROM dwd_crypto_ticker_detail
WHERE inst_id = 'BTC-USDT'
LIMIT 100;
```

#### 6.3 物化视图优化

**创建物化视图**:
```sql
-- 创建聚合物化视图
CREATE MATERIALIZED VIEW mv_ticker_hourly_agg AS
SELECT 
    inst_id,
    DATE_TRUNC(FROM_UNIXTIME(timestamp/1000), 'hour') as hour_time,
    MAX(last_price) as high_price,
    MIN(last_price) as low_price,
    COUNT(*) as tick_count
FROM ods_crypto_ticker_rt
GROUP BY inst_id, hour_time;

-- 查询自动使用物化视图
SELECT 
    inst_id,
    MAX(last_price) as high_price
FROM ods_crypto_ticker_rt
WHERE inst_id = 'BTC-USDT'
GROUP BY inst_id;
```

### 7. 监控和维护

#### 7.1 表统计信息

**查看表大小**:
```sql
SHOW DATA FROM crypto_dw;
```

**查看分区信息**:
```sql
SHOW PARTITIONS FROM dwd_crypto_ticker_detail;
```

**查看 Tablet 分布**:
```sql
SHOW TABLETS FROM dwd_crypto_ticker_detail;
```

#### 7.2 性能监控

**查看查询统计**:
```sql
SHOW QUERY STATS;
```

**查看慢查询**:
```sql
SHOW QUERY PROFILE;
```

#### 7.3 数据维护

**手动触发 Compaction**:
```sql
-- Base Compaction
ALTER TABLE dwd_crypto_ticker_detail COMPACT;

-- Cumulative Compaction
ALTER TABLE dwd_crypto_ticker_detail CUMULATIVE COMPACT;
```

**删除过期分区**:
```sql
ALTER TABLE dwd_crypto_ticker_detail 
DROP PARTITION p20231201;
```

### 8. 常见问题和解决方案

#### 8.1 数据倾斜

**问题表现**:
- 某些 Tablet 数据量远大于其他 Tablet
- 查询性能不稳定

**解决方案**:
- 选择高基数字段作为分桶键
- 增加分桶数量
- 使用复合分桶键

```sql
-- 使用复合分桶键
DISTRIBUTED BY HASH(inst_id, DATE_TRUNC(timestamp, 'day')) BUCKETS 20
```

#### 8.2 小文件问题

**问题表现**:
- Tablet 数量过多
- Compaction 压力大
- 查询性能下降

**解决方案**:
- 减少分桶数量
- 增加批量导入大小
- 定期执行 Compaction

#### 8.3 写入性能问题

**问题表现**:
- 写入延迟高
- 写入失败率高

**解决方案**:
- 使用 DUPLICATE KEY 模型
- 增加批量大小
- 减少索引数量
- 调整副本数为 1（开发环境）

#### 8.4 查询性能问题

**问题表现**:
- 查询响应慢
- 扫描数据量大

**解决方案**:
- 使用分区裁剪和 Bucket 裁剪
- 创建合适的索引
- 使用物化视图
- 优化 SQL 语句

### 9. 建表检查清单

在创建表之前，请检查以下项目：

- [ ] 选择合适的数据模型（DUPLICATE/AGGREGATE/UNIQUE）
- [ ] 设计合理的分区策略（Range/List）
- [ ] 选择合适的分桶键和分桶数量
- [ ] Key 列顺序符合查询模式
- [ ] 字段类型和长度合理
- [ ] 设置合适的副本数
- [ ] 配置动态分区（如需要）
- [ ] 创建必要的索引（Bloom Filter/Inverted Index）
- [ ] 选择合适的压缩算法
- [ ] 设置数据保留策略
- [ ] 添加必要的注释

### 10. 完整建表示例

```sql
-- ODS 层表
CREATE TABLE IF NOT EXISTS ods_crypto_ticker_rt (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    timestamp BIGINT COMMENT '业务时间戳（毫秒）',
    last_price DECIMAL(20, 8) COMMENT '最新成交价',
    bid_price DECIMAL(20, 8) COMMENT '买一价',
    ask_price DECIMAL(20, 8) COMMENT '卖一价',
    bid_size DECIMAL(20, 8) COMMENT '买一量',
    ask_size DECIMAL(20, 8) COMMENT '卖一量',
    volume_24h DECIMAL(30, 8) COMMENT '24小时成交量',
    high_24h DECIMAL(20, 8) COMMENT '24小时最高价',
    low_24h DECIMAL(20, 8) COMMENT '24小时最低价',
    data_source VARCHAR(20) COMMENT '数据源标识',
    ingest_time BIGINT COMMENT '数据入库时间戳'
)
DUPLICATE KEY(inst_id, timestamp)
COMMENT 'ODS层-加密货币实时行情原始数据表'
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "SSD",
    "compression" = "LZ4",
    "bloom_filter_columns" = "inst_id"
);

-- DWD 层表
CREATE TABLE IF NOT EXISTS dwd_crypto_ticker_detail (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    timestamp BIGINT COMMENT '业务时间戳（毫秒）',
    trade_date DATE COMMENT '交易日期',
    trade_hour INT COMMENT '交易小时',
    last_price DECIMAL(20, 8) COMMENT '最新成交价',
    bid_price DECIMAL(20, 8) COMMENT '买一价',
    ask_price DECIMAL(20, 8) COMMENT '卖一价',
    spread DECIMAL(20, 8) COMMENT '买卖价差',
    spread_rate DECIMAL(10, 6) COMMENT '价差率',
    volume_24h DECIMAL(30, 8) COMMENT '24小时成交量',
    high_24h DECIMAL(20, 8) COMMENT '24小时最高价',
    low_24h DECIMAL(20, 8) COMMENT '24小时最低价',
    price_change_24h DECIMAL(20, 8) COMMENT '24小时涨跌额',
    price_change_rate_24h DECIMAL(10, 6) COMMENT '24小时涨跌幅',
    amplitude_24h DECIMAL(10, 6) COMMENT '24小时振幅',
    data_source VARCHAR(20) COMMENT '数据源标识',
    ingest_time BIGINT COMMENT '数据入库时间',
    process_time BIGINT COMMENT '数据处理时间'
)
DUPLICATE KEY(inst_id, timestamp)
COMMENT 'DWD层-加密货币行情明细数据表'
PARTITION BY RANGE(trade_date) ()
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "SSD",
    "compression" = "LZ4",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10",
    "bloom_filter_columns" = "inst_id"
);
```
