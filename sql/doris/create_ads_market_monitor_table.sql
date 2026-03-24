-- ADS 层：实时市场监控指标表
-- 功能：存储 5 分钟窗口的市场统计指标
-- 更新时间：2026-03-24

CREATE TABLE IF NOT EXISTS crypto_dw.ads_market_monitor_5min (
    -- 维度字段
    symbol VARCHAR(20) COMMENT '交易对符号（如 BTC、ETH）',
    window_start BIGINT COMMENT '窗口开始时间（毫秒时间戳）',
    window_end BIGINT COMMENT '窗口结束时间（毫秒时间戳）',
    
    -- 数据统计
    data_count BIGINT COMMENT '窗口内数据量',
    
    -- 价格指标
    avg_price DECIMAL(20, 8) COMMENT '平均价格',
    max_price DECIMAL(20, 8) COMMENT '最高价格',
    min_price DECIMAL(20, 8) COMMENT '最低价格',
    price_volatility DECIMAL(20, 8) COMMENT '价格波动率（方差）',
    price_change_rate DECIMAL(10, 6) COMMENT '价格变化率（%）',
    
    -- 交易量指标
    avg_volume DECIMAL(20, 8) COMMENT '平均交易量',
    max_volume DECIMAL(20, 8) COMMENT '最大交易量',
    
    -- 趋势和异常
    trend VARCHAR(10) COMMENT '价格趋势（上涨/下跌/震荡）',
    has_anomaly BOOLEAN COMMENT '是否有异常',
    anomaly_count INT COMMENT '异常数量'
)
DUPLICATE KEY(symbol, window_start)
COMMENT 'ADS层 - 实时市场监控指标（5分钟窗口）'
PARTITION BY RANGE(window_start) ()
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10",
    "dynamic_partition.create_history_partition" = "true"
);

-- 创建索引，加速查询
-- 按交易对查询
ALTER TABLE crypto_dw.ads_market_monitor_5min 
ADD INDEX idx_symbol (symbol) USING INVERTED COMMENT '交易对索引';

-- 按趋势查询
ALTER TABLE crypto_dw.ads_market_monitor_5min 
ADD INDEX idx_trend (trend) USING INVERTED COMMENT '趋势索引';

-- 查询示例
-- 1. 查询最近的市场指标
-- SELECT * FROM crypto_dw.ads_market_monitor_5min 
-- WHERE symbol = 'BTC' 
-- ORDER BY window_start DESC 
-- LIMIT 10;

-- 2. 查询有异常的交易对
-- SELECT * FROM crypto_dw.ads_market_monitor_5min 
-- WHERE has_anomaly = true 
-- ORDER BY window_start DESC 
-- LIMIT 20;

-- 3. 查询价格波动最大的交易对
-- SELECT symbol, AVG(price_volatility) as avg_volatility
-- FROM crypto_dw.ads_market_monitor_5min 
-- WHERE window_start >= UNIX_TIMESTAMP(NOW() - INTERVAL 1 HOUR) * 1000
-- GROUP BY symbol 
-- ORDER BY avg_volatility DESC 
-- LIMIT 10;
