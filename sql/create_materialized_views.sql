-- 创建 Doris 物化视图
-- 数据库: crypto_dw
-- 物化视图可以加速查询,预先计算好聚合结果

-- 注意: Doris 物化视图说明
-- 1. Doris 的物化视图是基于表创建的,不是独立的视图
-- 2. 物化视图会自动维护,当基表数据变化时会自动更新
-- 3. 查询时 Doris 会自动选择合适的物化视图来加速查询
-- 4. 物化视图占用额外存储空间,但可以大幅提升查询性能

-- ========================================
-- 方案说明
-- ========================================
-- 由于 Doris 的物化视图语法限制,我们采用以下方案:
-- 1. 使用 Flink 作业直接生成多时间粒度的 K 线数据
-- 2. 在 DWS 层创建多个表: dws_crypto_ticker_5min, dws_crypto_ticker_15min, dws_crypto_ticker_1hour
-- 3. 使用 Doris 的 Rollup 功能优化查询性能

-- ========================================
-- 创建 DWS 层多时间粒度表
-- ========================================

USE crypto_dw;

-- DWS 层 - 5分钟 K 线数据表
CREATE TABLE IF NOT EXISTS dws_crypto_ticker_5min (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    window_start BIGINT COMMENT '窗口开始时间戳',
    window_end BIGINT COMMENT '窗口结束时间戳',
    window_size INT DEFAULT 5 COMMENT '窗口大小（分钟）',
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
COMMENT 'DWS层-5分钟K线数据表'
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4"
);

-- DWS 层 - 15分钟 K 线数据表
CREATE TABLE IF NOT EXISTS dws_crypto_ticker_15min (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    window_start BIGINT COMMENT '窗口开始时间戳',
    window_end BIGINT COMMENT '窗口结束时间戳',
    window_size INT DEFAULT 15 COMMENT '窗口大小（分钟）',
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
COMMENT 'DWS层-15分钟K线数据表'
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4"
);

-- DWS 层 - 1小时 K 线数据表
CREATE TABLE IF NOT EXISTS dws_crypto_ticker_1hour (
    inst_id VARCHAR(50) COMMENT '交易对标识',
    window_start BIGINT COMMENT '窗口开始时间戳',
    window_end BIGINT COMMENT '窗口结束时间戳',
    window_size INT DEFAULT 60 COMMENT '窗口大小（分钟）',
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
COMMENT 'DWS层-1小时K线数据表'
DISTRIBUTED BY HASH(inst_id) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_medium" = "HDD",
    "compression" = "LZ4"
);

-- 查看创建的表
SHOW TABLES LIKE 'dws_crypto_ticker_%';

-- ========================================
-- 使用说明
-- ========================================

-- 1. 物化视图会自动更新,当基表数据变化时会自动刷新
-- 2. 查询时 Doris 会自动选择合适的物化视图来加速查询
-- 3. 物化视图占用额外存储空间,但可以大幅提升查询性能

-- 示例查询 1: 查询 5 分钟 K 线 (自动使用物化视图)
-- SELECT * FROM dws_crypto_ticker_1min 
-- WHERE inst_id = 'BTC-USDT' 
-- AND window_start >= UNIX_TIMESTAMP('2026-03-22 00:00:00') * 1000;

-- 示例查询 2: 查询最新价格 (自动使用物化视图)
-- SELECT * FROM mv_ads_crypto_latest_price 
-- WHERE inst_id = 'BTC-USDT';

-- 示例查询 3: 查询日统计 (自动使用物化视图)
-- SELECT * FROM mv_ads_crypto_daily_stats 
-- WHERE inst_id = 'BTC-USDT' 
-- AND trade_date = '2026-03-22';
