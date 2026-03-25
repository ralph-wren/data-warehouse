-- ODS 层 INSERT 语句
-- 功能: 从 Kafka 读取原始行情数据，添加数据源和摄入时间后写入 Doris ODS 层
-- 数据流: Kafka → Flink → Doris ODS

INSERT INTO doris_ods_sink
SELECT 
    inst_id,
    `timestamp`,
    last_price,
    bid_price,
    ask_price,
    bid_size,
    ask_size,
    volume_24h,
    high_24h,
    low_24h,
    open_24h,
    'OKX' as data_source,
    UNIX_TIMESTAMP() * 1000 as ingest_time
FROM kafka_source
