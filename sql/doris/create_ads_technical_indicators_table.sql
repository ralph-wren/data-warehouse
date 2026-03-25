-- ADS层 - 技术指标交易信号表
-- 用于存储基于技术指标生成的实时交易信号

drop table if exists crypto_dw.ads_technical_indicators;
CREATE TABLE IF NOT EXISTS crypto_dw.ads_technical_indicators
(
    symbol              VARCHAR(32) COMMENT '交易对',
    indicator_type      VARCHAR(32) COMMENT '指标类型: MA/EMA/RSI/MACD/BOLL/KDJ/ATR/CUSTOM',
    indicator_name      VARCHAR(64) COMMENT '指标名称',
    timestamp           BIGINT COMMENT '时间戳(毫秒)',
    indicator_value     DECIMAL(20, 8) COMMENT '指标值',
    exchange            VARCHAR(32) COMMENT '交易所',
    extra_values        JSON COMMENT '额外的值（JSON格式）',
    signal_type         VARCHAR(16) COMMENT '交易信号: BUY/SELL/HOLD',
    signal_reason       VARCHAR(256) COMMENT '信号原因（基于哪个指标规则）',
    signal_strength     DECIMAL(5, 2) COMMENT '信号强度 0-100',
    current_price       DECIMAL(20, 8) COMMENT '当前价格',

    create_time         DATETIME COMMENT '创建时间'
)
DUPLICATE KEY(symbol, indicator_type, indicator_name, timestamp)
COMMENT 'ADS层 - 技术指标交易信号表'
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
    "dynamic_partition.enable" = "true",
    "dynamic_partition.time_unit" = "DAY",
    "dynamic_partition.start" = "-7",
    "dynamic_partition.end" = "3",
    "dynamic_partition.prefix" = "p",
    "dynamic_partition.buckets" = "10"
);

-- 创建索引以提升查询性能
ALTER TABLE crypto_dw.ads_technical_indicators
ADD INDEX idx_symbol (symbol) USING INVERTED COMMENT '交易对索引';

ALTER TABLE crypto_dw.ads_technical_indicators
ADD INDEX idx_indicator_type (indicator_type) USING INVERTED COMMENT '指标类型索引';

ALTER TABLE crypto_dw.ads_technical_indicators
ADD INDEX idx_indicator_name (indicator_name) USING INVERTED COMMENT '指标名称索引';
