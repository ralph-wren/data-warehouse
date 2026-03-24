-- ADS 层：跨市场套利机会表
-- 用途：存储现货和期货之间的价差套利机会
-- 更新频率：实时（Flink 流式写入）

CREATE TABLE IF NOT EXISTS crypto_dw.ads_arbitrage_opportunities (
    symbol VARCHAR(20) COMMENT '交易对符号（如 BTC-USDT）',
    spot_price DECIMAL(20, 8) COMMENT '现货价格',
    futures_price DECIMAL(20, 8) COMMENT '期货价格',
    spread DECIMAL(20, 8) COMMENT '价差（期货价格 - 现货价格）',
    spread_rate DECIMAL(10, 6) COMMENT '价差率（%）',
    arbitrage_direction VARCHAR(50) COMMENT '套利方向（做多期货/做空现货 或 做空期货/做多现货）',
    profit_estimate DECIMAL(20, 8) COMMENT '预估利润（扣除手续费）',
    timestamp BIGINT COMMENT '时间戳（毫秒）'
)
DUPLICATE KEY(symbol, timestamp)
COMMENT 'ADS 层 - 跨市场套利机会表'
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2",
    "compression" = "LZ4"
);
