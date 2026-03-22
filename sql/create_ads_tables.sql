-- 创建 ADS 层表 - 应用数据服务层
-- 数据库: crypto_dw

USE crypto_dw;

-- ========================================
-- ADS 层：应用数据服务层
-- 存储计算好的业务指标,直接供前端/BI展示使用
-- ========================================

-- ADS 层 - 交易对实时指标表
-- 功能: 存储每个交易对的实时关键指标
CREATE TABLE IF NOT EXISTS ads_crypto_ticker_realtime_metrics (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    metric_time BIGINT COMMENT '指标计算时间戳（毫秒）',
    metric_date DATE COMMENT '指标日期',
    metric_hour INT COMMENT '指标小时',
    
    -- 价格指标
    current_price DECIMAL(20, 8) COMMENT '当前价格',
    price_1min_ago DECIMAL(20, 8) COMMENT '1分钟前价格',
    price_5min_ago DECIMAL(20, 8) COMMENT '5分钟前价格',
    price_15min_ago DECIMAL(20, 8) COMMENT '15分钟前价格',
    price_1hour_ago DECIMAL(20, 8) COMMENT '1小时前价格',
    
    -- 涨跌幅指标
    change_rate_1min DECIMAL(10, 6) COMMENT '1分钟涨跌幅',
    change_rate_5min DECIMAL(10, 6) COMMENT '5分钟涨跌幅',
    change_rate_15min DECIMAL(10, 6) COMMENT '15分钟涨跌幅',
    change_rate_1hour DECIMAL(10, 6) COMMENT '1小时涨跌幅',
    change_rate_24hour DECIMAL(10, 6) COMMENT '24小时涨跌幅',
    
    -- 成交量指标
    volume_1min DECIMAL(30, 8) COMMENT '1分钟成交量',
    volume_5min DECIMAL(30, 8) COMMENT '5分钟成交量',
    volume_15min DECIMAL(30, 8) COMMENT '15分钟成交量',
    volume_1hour DECIMAL(30, 8) COMMENT '1小时成交量',
    volume_24hour DECIMAL(30, 8) COMMENT '24小时成交量',
    
    -- 波动率指标
    volatility_1min DECIMAL(10, 6) COMMENT '1分钟波动率',
    volatility_5min DECIMAL(10, 6) COMMENT '5分钟波动率',
    volatility_15min DECIMAL(10, 6) COMMENT '15分钟波动率',
    volatility_1hour DECIMAL(10, 6) COMMENT '1小时波动率',
    
    -- 价格区间指标
    high_1min DECIMAL(20, 8) COMMENT '1分钟最高价',
    low_1min DECIMAL(20, 8) COMMENT '1分钟最低价',
    high_5min DECIMAL(20, 8) COMMENT '5分钟最高价',
    low_5min DECIMAL(20, 8) COMMENT '5分钟最低价',
    high_1hour DECIMAL(20, 8) COMMENT '1小时最高价',
    low_1hour DECIMAL(20, 8) COMMENT '1小时最低价',
    
    -- 趋势指标
    trend_1min VARCHAR(10) COMMENT '1分钟趋势(UP/DOWN/FLAT)',
    trend_5min VARCHAR(10) COMMENT '5分钟趋势(UP/DOWN/FLAT)',
    trend_1hour VARCHAR(10) COMMENT '1小时趋势(UP/DOWN/FLAT)',
    
    -- 元数据
    update_time BIGINT COMMENT '更新时间戳'
)
DUPLICATE KEY(inst_id, metric_time)
COMMENT 'ADS层-交易对实时指标表'
PARTITION BY RANGE(metric_date) ()
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10",
    "bloom_filter_columns" = "inst_id"
);

-- ADS 层 - 交易对排行榜表
-- 功能: 存储各类排行榜数据(涨幅榜、跌幅榜、成交量榜等)
CREATE TABLE IF NOT EXISTS ads_crypto_ticker_ranking (
    ranking_type VARCHAR(50) COMMENT '排行榜类型(GAIN/LOSS/VOLUME/VOLATILITY)',
    time_window VARCHAR(20) COMMENT '时间窗口(1MIN/5MIN/15MIN/1HOUR/24HOUR)',
    ranking_time BIGINT COMMENT '排行榜生成时间戳',
    ranking_date DATE COMMENT '排行榜日期',
    
    inst_id VARCHAR(50) COMMENT '交易对标识',
    rank_no INT COMMENT '排名',
    current_price DECIMAL(20, 8) COMMENT '当前价格',
    metric_value DECIMAL(20, 8) COMMENT '指标值(涨跌幅/成交量/波动率)',
    volume DECIMAL(30, 8) COMMENT '成交量',
    
    update_time BIGINT COMMENT '更新时间戳'
)
DUPLICATE KEY(ranking_type, time_window, ranking_time, ranking_date)
COMMENT 'ADS层-交易对排行榜表'
PARTITION BY RANGE(ranking_date) ()
DISTRIBUTED BY HASH(ranking_type, time_window) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);

-- ADS 层 - 市场概览指标表
-- 功能: 存储整个市场的汇总指标
CREATE TABLE IF NOT EXISTS ads_crypto_market_overview (
    metric_time BIGINT COMMENT '指标计算时间戳',
    metric_date DATE COMMENT '指标日期',
    metric_hour INT COMMENT '指标小时',
    
    -- 市场规模指标
    total_symbols INT COMMENT '交易对总数',
    active_symbols INT COMMENT '活跃交易对数',
    total_volume_24h DECIMAL(38, 8) COMMENT '24小时总成交量',
    
    -- 市场情绪指标
    rising_count INT COMMENT '上涨交易对数量',
    falling_count INT COMMENT '下跌交易对数量',
    flat_count INT COMMENT '平盘交易对数量',
    rising_ratio DECIMAL(10, 6) COMMENT '上涨比例',
    
    -- 市场波动指标
    avg_volatility DECIMAL(10, 6) COMMENT '平均波动率',
    max_volatility DECIMAL(10, 6) COMMENT '最大波动率',
    min_volatility DECIMAL(10, 6) COMMENT '最小波动率',
    
    -- 价格变动指标
    avg_change_rate DECIMAL(10, 6) COMMENT '平均涨跌幅',
    max_gain_rate DECIMAL(10, 6) COMMENT '最大涨幅',
    max_loss_rate DECIMAL(10, 6) COMMENT '最大跌幅',
    
    -- 元数据
    update_time BIGINT COMMENT '更新时间戳'
)
DUPLICATE KEY(metric_time)
COMMENT 'ADS层-市场概览指标表'
PARTITION BY RANGE(metric_date) ()
DISTRIBUTED BY HASH(metric_time) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);

-- 查看创建的 ADS 层表
SHOW TABLES LIKE 'ads_%';
