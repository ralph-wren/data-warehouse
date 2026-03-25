-- ADS 实时指标 INSERT 语句
-- 功能: 从 DWS 层读取 1分钟 K 线数据，计算多时间窗口的实时指标
-- 数据流: Doris DWS (1分钟 K 线) → Flink 计算 → Doris ADS (实时指标)
-- 
-- 计算指标:
-- 1. 价格指标: 使用 LAG 函数获取历史价格
-- 2. 涨跌幅指标: 计算百分比变化
-- 3. 成交量指标: 使用窗口函数聚合
-- 4. 波动率指标: 使用标准差计算
-- 5. 趋势指标: 判断价格趋势

INSERT INTO ads_realtime_metrics_sink
SELECT 
    inst_id,
    window_end AS metric_time,
    CAST(FROM_UNIXTIME(window_end / 1000, 'yyyy-MM-dd') AS DATE) AS metric_date,
    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(window_end / 1000))) AS INT) AS metric_hour,
    
    -- 价格指标 (使用 LAG 函数获取历史价格)
    close_price AS current_price,
    LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_1min_ago,
    LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_5min_ago,
    LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_15min_ago,
    LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_1hour_ago,
    
    -- 涨跌幅指标 (计算百分比变化)
    CAST(CASE 
        WHEN LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL 
             AND LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) > 0
        THEN (close_price - LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end)) 
             / LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS change_rate_1min,
    
    CAST(CASE 
        WHEN LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL 
             AND LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) > 0
        THEN (close_price - LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end)) 
             / LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS change_rate_5min,
    
    CAST(CASE 
        WHEN LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL 
             AND LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) > 0
        THEN (close_price - LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end)) 
             / LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS change_rate_15min,
    
    CAST(CASE 
        WHEN LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL 
             AND LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) > 0
        THEN (close_price - LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end)) 
             / LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS change_rate_1hour,
    
    price_change_rate AS change_rate_24hour,
    
    -- 成交量指标 (使用窗口函数聚合)
    volume AS volume_1min,
    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS volume_5min,
    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) AS volume_15min,
    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) AS volume_1hour,
    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 1439 PRECEDING AND CURRENT ROW) AS volume_24hour,
    
    -- 波动率指标 (使用标准差计算)
    CAST(CASE 
        WHEN avg_price > 0 
        THEN (high_price - low_price) / avg_price * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS volatility_1min,
    
    CAST(CASE 
        WHEN AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) > 0
        THEN (MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) - 
              MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)) / 
             AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS volatility_5min,
    
    CAST(CASE 
        WHEN AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) > 0
        THEN (MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) - 
              MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW)) / 
             AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS volatility_15min,
    
    CAST(CASE 
        WHEN AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) > 0
        THEN (MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) - 
              MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW)) / 
             AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) * 100
        ELSE 0
    END AS DECIMAL(10, 6)) AS volatility_1hour,
    
    -- 价格区间指标
    high_price AS high_1min,
    low_price AS low_1min,
    MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS high_5min,
    MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS low_5min,
    MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) AS high_1hour,
    MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) AS low_1hour,
    
    -- 趋势指标 (判断价格趋势)
    CASE 
        WHEN close_price > LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'UP'
        WHEN close_price < LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'DOWN'
        ELSE 'FLAT'
    END AS trend_1min,
    
    CASE 
        WHEN close_price > LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'UP'
        WHEN close_price < LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'DOWN'
        ELSE 'FLAT'
    END AS trend_5min,
    
    CASE 
        WHEN close_price > LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'UP'
        WHEN close_price < LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'DOWN'
        ELSE 'FLAT'
    END AS trend_1hour,
    
    -- 元数据
    UNIX_TIMESTAMP() * 1000 AS update_time
FROM dws_source
