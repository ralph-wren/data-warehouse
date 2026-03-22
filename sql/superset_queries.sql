-- Superset 常用查询 SQL
-- 用于在 Superset 中分析加密货币数据

-- ============================================
-- 1. 各币种最新价格 (实时行情)
-- ============================================
-- 从明细表中获取每个币种的最新价格
-- 使用窗口函数获取每个币种的最新记录
SELECT 
    inst_id AS 币种,
    last_price AS 最新价格,
    bid_price AS 买一价,
    ask_price AS 卖一价,
    ROUND((ask_price - bid_price) / bid_price * 100, 4) AS 买卖价差率,
    volume_24h AS 24小时成交量,
    FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
    SELECT 
        inst_id,
        last_price,
        bid_price,
        ask_price,
        volume_24h,
        timestamp,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
    FROM crypto_dw.dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY last_price DESC;


-- ============================================
-- 2. 各币种最新价格 (简化版)
-- ============================================
-- 使用窗口函数获取最新价格
SELECT 
    inst_id AS 币种,
    last_price AS 最新价格,
    volume_24h AS 24小时成交量,
    FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
    SELECT 
        inst_id,
        last_price,
        volume_24h,
        timestamp,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
    FROM crypto_dw.dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY last_price DESC;


-- ============================================
-- 3. 价格排行榜 (Top 10)
-- ============================================
-- 使用窗口函数获取最新价格并排序
SELECT 
    inst_id AS 币种,
    last_price AS 最新价格,
    volume_24h AS 24小时成交量,
    ROUND(volume_24h * last_price, 2) AS 成交额USD
FROM (
    SELECT 
        inst_id,
        last_price,
        volume_24h,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
    FROM crypto_dw.dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY last_price DESC
LIMIT 10;


-- ============================================
-- 4. 24小时价格变化统计
-- ============================================
SELECT 
    inst_id AS 币种,
    MAX(last_price) AS 最高价,
    MIN(last_price) AS 最低价,
    AVG(last_price) AS 平均价,
    MAX(last_price) - MIN(last_price) AS 价格波动,
    ROUND((MAX(last_price) - MIN(last_price)) / MIN(last_price) * 100, 2) AS 波动率,
    COUNT(*) AS 数据点数
FROM crypto_dw.dwd_crypto_ticker_detail
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 24 HOUR)) * 1000
GROUP BY inst_id
ORDER BY 波动率 DESC;


-- ============================================
-- 5. 最近1小时价格趋势
-- ============================================
SELECT 
    inst_id AS 币种,
    FROM_UNIXTIME(timestamp / 1000) AS 时间,
    last_price AS 价格,
    volume_24h AS 成交量
FROM crypto_dw.dwd_crypto_ticker_detail
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
ORDER BY inst_id, timestamp;


-- ============================================
-- 6. 买卖价差分析
-- ============================================
-- 使用窗口函数获取最新价格并计算价差
SELECT 
    inst_id AS 币种,
    last_price AS 最新价格,
    bid_price AS 买一价,
    ask_price AS 卖一价,
    ROUND(ask_price - bid_price, 8) AS 价差,
    ROUND((ask_price - bid_price) / bid_price * 100, 4) AS 价差率,
    FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
    SELECT 
        inst_id,
        last_price,
        bid_price,
        ask_price,
        timestamp,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
    FROM crypto_dw.dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY 价差率 DESC;


-- ============================================
-- 7. 成交量排行榜
-- ============================================
-- 使用窗口函数获取最新数据并按成交量排序
SELECT 
    inst_id AS 币种,
    last_price AS 最新价格,
    volume_24h AS 24小时成交量,
    ROUND(volume_24h * last_price, 2) AS 成交额USD,
    FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
    SELECT 
        inst_id,
        last_price,
        volume_24h,
        timestamp,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
    FROM crypto_dw.dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY volume_24h DESC
LIMIT 10;


-- ============================================
-- 8. 分钟级价格统计 (最近1小时)
-- ============================================
SELECT 
    inst_id AS 币种,
    DATE_FORMAT(FROM_UNIXTIME(timestamp / 1000), '%Y-%m-%d %H:%i:00') AS 分钟,
    AVG(last_price) AS 平均价格,
    MAX(last_price) AS 最高价,
    MIN(last_price) AS 最低价,
    COUNT(*) AS 数据点数
FROM crypto_dw.dwd_crypto_ticker_detail
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
GROUP BY inst_id, DATE_FORMAT(FROM_UNIXTIME(timestamp / 1000), '%Y-%m-%d %H:%i:00')
ORDER BY inst_id, 分钟;


-- ============================================
-- 9. 实时数据监控 (最近5分钟)
-- ============================================
SELECT 
    inst_id AS 币种,
    COUNT(*) AS 数据条数,
    MAX(FROM_UNIXTIME(timestamp / 1000)) AS 最新时间,
    MIN(FROM_UNIXTIME(timestamp / 1000)) AS 最早时间,
    TIMESTAMPDIFF(SECOND, MIN(FROM_UNIXTIME(timestamp / 1000)), MAX(FROM_UNIXTIME(timestamp / 1000))) AS 时间跨度秒
FROM crypto_dw.dwd_crypto_ticker_detail
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 5 MINUTE)) * 1000
GROUP BY inst_id
ORDER BY 数据条数 DESC;


-- ============================================
-- 10. 价格变化率 (与1小时前对比)
-- ============================================
-- 使用窗口函数获取最新价格和1小时前价格进行对比
WITH latest AS (
    -- 最新价格
    SELECT 
        inst_id,
        last_price AS current_price,
        timestamp AS current_ts
    FROM (
        SELECT 
            inst_id,
            last_price,
            timestamp,
            ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
        FROM crypto_dw.dwd_crypto_ticker_detail
    ) t
    WHERE rn = 1
),
hour_ago AS (
    -- 1小时前的价格
    SELECT 
        inst_id,
        last_price AS hour_ago_price
    FROM (
        SELECT 
            inst_id,
            last_price,
            timestamp,
            ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY ABS(timestamp - (UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000))) AS rn
        FROM crypto_dw.dwd_crypto_ticker_detail
        WHERE timestamp <= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
    ) t
    WHERE rn = 1
)
SELECT 
    l.inst_id AS 币种,
    l.current_price AS 当前价格,
    h.hour_ago_price AS 1小时前价格,
    ROUND(l.current_price - h.hour_ago_price, 8) AS 价格变化,
    ROUND((l.current_price - h.hour_ago_price) / h.hour_ago_price * 100, 2) AS 涨跌幅,
    FROM_UNIXTIME(l.current_ts / 1000) AS 更新时间
FROM latest l
LEFT JOIN hour_ago h ON l.inst_id = h.inst_id
WHERE h.hour_ago_price IS NOT NULL
ORDER BY 涨跌幅 DESC;


-- ============================================
-- 11. 数据质量检查
-- ============================================
SELECT 
    '总记录数' AS 指标,
    COUNT(*) AS 数值,
    NULL AS 说明
FROM crypto_dw.dwd_crypto_ticker_detail

UNION ALL

SELECT 
    '币种数量' AS 指标,
    COUNT(DISTINCT inst_id) AS 数值,
    NULL AS 说明
FROM crypto_dw.dwd_crypto_ticker_detail

UNION ALL

SELECT 
    '最早时间' AS 指标,
    NULL AS 数值,
    FROM_UNIXTIME(MIN(timestamp) / 1000) AS 说明
FROM crypto_dw.dwd_crypto_ticker_detail

UNION ALL

SELECT 
    '最新时间' AS 指标,
    NULL AS 数值,
    FROM_UNIXTIME(MAX(timestamp) / 1000) AS 说明
FROM crypto_dw.dwd_crypto_ticker_detail

UNION ALL

SELECT 
    '数据时间跨度(小时)' AS 指标,
    TIMESTAMPDIFF(HOUR, FROM_UNIXTIME(MIN(timestamp) / 1000), FROM_UNIXTIME(MAX(timestamp) / 1000)) AS 数值,
    NULL AS 说明
FROM crypto_dw.dwd_crypto_ticker_detail;


-- ============================================
-- 12. 各币种数据统计
-- ============================================
SELECT 
    inst_id AS 币种,
    COUNT(*) AS 数据条数,
    MIN(FROM_UNIXTIME(timestamp / 1000)) AS 最早时间,
    MAX(FROM_UNIXTIME(timestamp / 1000)) AS 最新时间,
    TIMESTAMPDIFF(MINUTE, MIN(FROM_UNIXTIME(timestamp / 1000)), MAX(FROM_UNIXTIME(timestamp / 1000))) AS 时间跨度分钟
FROM crypto_dw.dwd_crypto_ticker_detail
GROUP BY inst_id
ORDER BY 数据条数 DESC;
