-- DWD 层数据插入 SQL
-- 功能：从 Kafka 读取原始数据，进行清洗和字段补充，写入 DWD 层
-- 数据流转：Kafka → DWD 作业 → Doris DWD 表
-- 
-- 注意事项：
-- 1. 源表字段名使用驼峰命名（instId, ts, last 等）
-- 2. 需要将 JSON 字段转换为 DECIMAL 类型
-- 3. 使用 Flink SQL 标准函数进行数据清洗和计算
-- 4. 过滤掉价格为 0 或负数的异常数据

INSERT INTO doris_dwd_sink
SELECT 
    -- 基础字段
    instId as inst_id,
    ts as `timestamp`,
    
    -- 时间维度字段
    CAST(TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000)) AS DATE) as trade_date,
    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000))) AS INT) as trade_hour,
    
    -- 价格字段（转换为 DECIMAL 类型）
    CAST(last AS DECIMAL(20, 8)) as last_price,
    CAST(bidPx AS DECIMAL(20, 8)) as bid_price,
    CAST(askPx AS DECIMAL(20, 8)) as ask_price,
    
    -- 价差计算
    CAST((CAST(askPx AS DECIMAL(20, 8)) - CAST(bidPx AS DECIMAL(20, 8))) AS DECIMAL(20, 8)) as spread,
    CAST(CASE 
        WHEN CAST(last AS DECIMAL(20, 8)) > 0 THEN (CAST(askPx AS DECIMAL(20, 8)) - CAST(bidPx AS DECIMAL(20, 8))) / CAST(last AS DECIMAL(20, 8))
        ELSE 0
    END AS DECIMAL(10, 6)) as spread_rate,
    
    -- 24小时统计字段
    CAST(vol24h AS DECIMAL(30, 8)) as volume_24h,
    CAST(high24h AS DECIMAL(20, 8)) as high_24h,
    CAST(low24h AS DECIMAL(20, 8)) as low_24h,
    CAST(open24h AS DECIMAL(20, 8)) as open_24h,
    
    -- 24小时价格变化
    CAST(CASE 
        WHEN CAST(open24h AS DECIMAL(20, 8)) > 0 THEN (CAST(last AS DECIMAL(20, 8)) - CAST(open24h AS DECIMAL(20, 8)))
        ELSE 0
    END AS DECIMAL(20, 8)) as price_change_24h,
    
    -- 24小时涨跌幅
    CAST(CASE 
        WHEN CAST(open24h AS DECIMAL(20, 8)) > 0 THEN (CAST(last AS DECIMAL(20, 8)) - CAST(open24h AS DECIMAL(20, 8))) / CAST(open24h AS DECIMAL(20, 8))
        ELSE 0
    END AS DECIMAL(10, 6)) as price_change_rate_24h,
    
    -- 24小时振幅
    CAST(CASE 
        WHEN CAST(low24h AS DECIMAL(20, 8)) > 0 THEN (CAST(high24h AS DECIMAL(20, 8)) - CAST(low24h AS DECIMAL(20, 8))) / CAST(low24h AS DECIMAL(20, 8))
        ELSE 0
    END AS DECIMAL(10, 6)) as amplitude_24h,
    
    -- 元数据字段
    CAST('OKX' AS STRING) as data_source,
    ts as ingest_time,
    UNIX_TIMESTAMP() * 1000 as process_time
FROM kafka_ticker_source
WHERE CAST(last AS DECIMAL(20, 8)) > 0 
  AND CAST(bidPx AS DECIMAL(20, 8)) > 0 
  AND CAST(askPx AS DECIMAL(20, 8)) > 0
