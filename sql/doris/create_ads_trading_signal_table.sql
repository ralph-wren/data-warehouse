-- ADS层 - 实时交易信号表
-- 用于存储基于多维度分析生成的交易信号

CREATE TABLE IF NOT EXISTS crypto_ads.ads_trading_signals
(
    signal_id           VARCHAR(64) COMMENT '信号ID',
    symbol              VARCHAR(32) COMMENT '交易对',
    exchange            VARCHAR(32) COMMENT '交易所',
    signal_type         VARCHAR(16) COMMENT '信号类型: BUY/SELL/HOLD',
    current_price       DECIMAL(20, 8) COMMENT '当前价格',
    target_price        DECIMAL(20, 8) COMMENT '目标价格',
    stop_loss           DECIMAL(20, 8) COMMENT '止损价格',
    confidence          DECIMAL(5, 2) COMMENT '信号置信度 0-100',
    risk_score          DECIMAL(5, 2) COMMENT '风险评分 0-100',
    strategy_type       VARCHAR(32) COMMENT '策略类型: AGGRESSIVE/MODERATE/CONSERVATIVE',
    volume_24h          DECIMAL(30, 8) COMMENT '24小时成交量',
    volatility          DECIMAL(20, 8) COMMENT '波动率',
    signal_count        INT COMMENT '窗口内信号数量',
    timestamp           BIGINT COMMENT '时间戳(毫秒)',
    create_time         DATETIME COMMENT '创建时间'
)
DUPLICATE KEY(signal_id, symbol, timestamp)
COMMENT 'ADS层 - 实时交易信号表'
PARTITION BY RANGE(timestamp)
(
    PARTITION p20260325 VALUES LESS THAN ("1742860800000"),  -- 2026-03-26 00:00:00
    PARTITION p20260326 VALUES LESS THAN ("1742947200000"),  -- 2026-03-27 00:00:00
    PARTITION p20260327 VALUES LESS THAN ("1743033600000")   -- 2026-03-28 00:00:00
)
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2",
    "compression" = "LZ4",
    "enable_persistent_index" = "true",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);

-- ADS层 - 高风险交易信号表
-- 用于存储风险评分超过阈值的高风险信号，需要特别关注

CREATE TABLE IF NOT EXISTS crypto_ads.ads_high_risk_signals
(
    signal_id           VARCHAR(64) COMMENT '信号ID',
    symbol              VARCHAR(32) COMMENT '交易对',
    exchange            VARCHAR(32) COMMENT '交易所',
    signal_type         VARCHAR(16) COMMENT '信号类型: BUY/SELL/HOLD',
    current_price       DECIMAL(20, 8) COMMENT '当前价格',
    target_price        DECIMAL(20, 8) COMMENT '目标价格',
    stop_loss           DECIMAL(20, 8) COMMENT '止损价格',
    confidence          DECIMAL(5, 2) COMMENT '信号置信度 0-100',
    risk_score          DECIMAL(5, 2) COMMENT '风险评分 0-100',
    strategy_type       VARCHAR(32) COMMENT '策略类型',
    volume_24h          DECIMAL(30, 8) COMMENT '24小时成交量',
    volatility          DECIMAL(20, 8) COMMENT '波动率',
    signal_count        INT COMMENT '窗口内信号数量',
    timestamp           BIGINT COMMENT '时间戳(毫秒)',
    create_time         DATETIME COMMENT '创建时间'
)
DUPLICATE KEY(signal_id, symbol, timestamp)
COMMENT 'ADS层 - 高风险交易信号表'
PARTITION BY RANGE(timestamp)
(
    PARTITION p20260325 VALUES LESS THAN ("1742860800000"),
    PARTITION p20260326 VALUES LESS THAN ("1742947200000"),
    PARTITION p20260327 VALUES LESS THAN ("1743033600000")
)
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2",
    "compression" = "LZ4",
    "enable_persistent_index" = "true",
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);

-- 创建索引以提升查询性能
ALTER TABLE crypto_ads.ads_trading_signals 
ADD INDEX idx_symbol (symbol) USING INVERTED COMMENT '交易对索引';

ALTER TABLE crypto_ads.ads_trading_signals 
ADD INDEX idx_signal_type (signal_type) USING INVERTED COMMENT '信号类型索引';

ALTER TABLE crypto_ads.ads_high_risk_signals 
ADD INDEX idx_symbol (symbol) USING INVERTED COMMENT '交易对索引';

ALTER TABLE crypto_ads.ads_high_risk_signals 
ADD INDEX idx_risk_score (risk_score) USING INVERTED COMMENT '风险评分索引';
