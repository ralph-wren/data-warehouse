-- 创建 Doris 数据仓库表
-- 数据库: crypto_dw

USE crypto_dw;

-- ========================================
-- ODS 层：原始数据存储层
-- ========================================

-- ODS 层 - 加密货币实时行情原始数据表
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
    open_24h DECIMAL(20, 8) COMMENT '24小时开盘价',
    data_source VARCHAR(20) DEFAULT 'OKX' COMMENT '数据源标识',
    ingest_time BIGINT COMMENT '数据入库时间戳（毫秒）'
)
DUPLICATE KEY(inst_id, timestamp)
COMMENT 'ODS层-加密货币实时行情原始数据表'
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4",
    "bloom_filter_columns" = "inst_id"
);

-- ========================================
-- DWD 层：明细数据层
-- ========================================

-- DWD 层 - 加密货币行情明细数据表
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
    open_24h DECIMAL(20, 8) COMMENT '24小时开盘价',
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

-- ========================================
-- DWS 层：汇总数据层
-- ========================================

-- DWS 层 - 1分钟 K 线数据表
CREATE TABLE IF NOT EXISTS dws_crypto_ticker_1min (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    window_start BIGINT COMMENT '窗口开始时间戳',
    window_end BIGINT COMMENT '窗口结束时间戳',
    window_size INT DEFAULT 1 COMMENT '窗口大小（分钟）',
    open_price DECIMAL(20, 8) REPLACE COMMENT '开盘价',
    high_price DECIMAL(20, 8) MAX COMMENT '最高价',
    low_price DECIMAL(20, 8) MIN COMMENT '最低价',
    close_price DECIMAL(20, 8) REPLACE COMMENT '收盘价',
    volume DECIMAL(30, 8) SUM COMMENT '成交量',
    avg_price DECIMAL(20, 8) REPLACE COMMENT '平均价',
    price_change DECIMAL(20, 8) REPLACE COMMENT '涨跌额',
    price_change_rate DECIMAL(10, 6) REPLACE COMMENT '涨跌幅',
    tick_count INT SUM COMMENT 'Tick 数量',
    process_time BIGINT REPLACE COMMENT '处理时间戳'
)
AGGREGATE KEY(inst_id, window_start, window_end, window_size)
COMMENT 'DWS层-1分钟K线数据表'
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4"
);

-- 查看创建的表
SHOW TABLES;
