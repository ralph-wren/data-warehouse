-- DWS 1分钟窗口聚合 SQL
-- 功能：从 Kafka 读取原始数据，进行1分钟窗口聚合，生成 K 线数据写入 DWS 层
-- 数据流转：Kafka → DWS 作业 → Doris DWS 表
-- 
-- 注意事项：
-- 1. 源表字段名使用驼峰命名（instId, ts, last 等）
-- 2. 需要将 JSON 字段转换为 DECIMAL 类型
-- 3. 1分钟滚动窗口聚合，生成 K 线数据
-- 4. 过滤掉价格为 0 或负数的异常数据

INSERT INTO doris_dws_1min_sink
SELECT 
    -- 交易对
    instId as inst_id,
    
    -- 窗口时间戳（毫秒）
    UNIX_TIMESTAMP(CAST(window_start AS STRING)) * 1000 as window_start,
    UNIX_TIMESTAMP(CAST(window_end AS STRING)) * 1000 as window_end,
    1 as window_size,
    
    -- K 线数据：开高低收
    FIRST_VALUE(CAST(last AS DECIMAL(20, 8))) as open_price,
    MAX(CAST(last AS DECIMAL(20, 8))) as high_price,
    MIN(CAST(last AS DECIMAL(20, 8))) as low_price,
    LAST_VALUE(CAST(last AS DECIMAL(20, 8))) as close_price,
    
    -- 成交量（取最后一个值）
    LAST_VALUE(CAST(vol24h AS DECIMAL(30, 8))) as volume,
    
    -- 平均价
    AVG(CAST(last AS DECIMAL(20, 8))) as avg_price,
    
    -- 涨跌额和涨跌幅
    LAST_VALUE(CAST(last AS DECIMAL(20, 8))) - FIRST_VALUE(CAST(last AS DECIMAL(20, 8))) as price_change,
    CASE 
        WHEN FIRST_VALUE(CAST(last AS DECIMAL(20, 8))) > 0 
        THEN (LAST_VALUE(CAST(last AS DECIMAL(20, 8))) - FIRST_VALUE(CAST(last AS DECIMAL(20, 8)))) / FIRST_VALUE(CAST(last AS DECIMAL(20, 8)))
        ELSE 0
    END as price_change_rate,
    
    -- Tick 数量
    CAST(COUNT(*) AS INT) as tick_count,
    
    -- 处理时间
    UNIX_TIMESTAMP() * 1000 as process_time
FROM TABLE(
    TUMBLE(TABLE kafka_ticker_source, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)
)
WHERE CAST(last AS DECIMAL(20, 8)) > 0
GROUP BY instId, window_start, window_end
