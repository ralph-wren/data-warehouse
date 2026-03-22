package com.crypto.dw.flink.schema;

/**
 * Flink Table Schema 定义
 * 
 * 集中管理所有表的字段定义,避免重复代码
 * 
 * 使用示例:
 * <pre>
 * String schema = TableSchemas.KAFKA_TICKER_SCHEMA;
 * String ddl = factory.createKafkaSourceTable("kafka_source", schema, true);
 * </pre>
 */
public class TableSchemas {
    
    /**
     * Kafka Ticker Source 表 Schema
     * 用于从 Kafka 读取原始 Ticker 数据
     */
    public static final String KAFKA_TICKER_SCHEMA =
            "    inst_id STRING,\n" +
            "    `timestamp` BIGINT,\n" +
            "    last_price DECIMAL(20, 8),\n" +
            "    bid_price DECIMAL(20, 8),\n" +
            "    ask_price DECIMAL(20, 8),\n" +
            "    bid_size DECIMAL(20, 8),\n" +
            "    ask_size DECIMAL(20, 8),\n" +
            "    volume_24h DECIMAL(30, 8),\n" +
            "    high_24h DECIMAL(20, 8),\n" +
            "    low_24h DECIMAL(20, 8),\n" +
            "    open_24h DECIMAL(20, 8)";
    
    /**
     * Doris ODS Source 表 Schema
     * 用于从 Doris ODS 层读取数据
     */
    public static final String DORIS_ODS_SOURCE_SCHEMA =
            "    inst_id STRING,\n" +
            "    `timestamp` BIGINT,\n" +
            "    last_price DECIMAL(20, 8),\n" +
            "    bid_price DECIMAL(20, 8),\n" +
            "    ask_price DECIMAL(20, 8),\n" +
            "    bid_size DECIMAL(20, 8),\n" +
            "    ask_size DECIMAL(20, 8),\n" +
            "    volume_24h DECIMAL(30, 8),\n" +
            "    high_24h DECIMAL(20, 8),\n" +
            "    low_24h DECIMAL(20, 8),\n" +
            "    open_24h DECIMAL(20, 8),\n" +
            "    data_source STRING,\n" +
            "    ingest_time BIGINT";
    
    /**
     * Doris DWD Source 表 Schema
     * 用于从 Doris DWD 层读取数据
     */
    public static final String DORIS_DWD_SOURCE_SCHEMA =
            "    inst_id STRING,\n" +
            "    `timestamp` BIGINT,\n" +
            "    trade_date DATE,\n" +
            "    trade_hour INT,\n" +
            "    last_price DECIMAL(20, 8),\n" +
            "    bid_price DECIMAL(20, 8),\n" +
            "    ask_price DECIMAL(20, 8),\n" +
            "    spread DECIMAL(20, 8),\n" +
            "    spread_rate DECIMAL(10, 6),\n" +
            "    volume_24h DECIMAL(30, 8),\n" +
            "    high_24h DECIMAL(20, 8),\n" +
            "    low_24h DECIMAL(20, 8),\n" +
            "    open_24h DECIMAL(20, 8),\n" +
            "    price_change_24h DECIMAL(20, 8),\n" +
            "    price_change_rate_24h DECIMAL(10, 6),\n" +
            "    amplitude_24h DECIMAL(10, 6),\n" +
            "    data_source STRING,\n" +
            "    ingest_time BIGINT,\n" +
            "    process_time BIGINT";
    
    /**
     * Doris DWD Source 表 Schema (带 Watermark)
     * 用于从 Doris DWD 层读取数据并进行窗口聚合
     */
    public static final String DORIS_DWD_SOURCE_SCHEMA_WITH_WATERMARK =
            "    inst_id STRING,\n" +
            "    `timestamp` BIGINT,\n" +
            "    trade_date DATE,\n" +
            "    trade_hour INT,\n" +
            "    last_price DECIMAL(20, 8),\n" +
            "    bid_price DECIMAL(20, 8),\n" +
            "    ask_price DECIMAL(20, 8),\n" +
            "    spread DECIMAL(20, 8),\n" +
            "    spread_rate DECIMAL(10, 6),\n" +
            "    volume_24h DECIMAL(30, 8),\n" +
            "    high_24h DECIMAL(20, 8),\n" +
            "    low_24h DECIMAL(20, 8),\n" +
            "    open_24h DECIMAL(20, 8),\n" +
            "    price_change_24h DECIMAL(20, 8),\n" +
            "    price_change_rate_24h DECIMAL(10, 6),\n" +
            "    amplitude_24h DECIMAL(10, 6),\n" +
            "    data_source STRING,\n" +
            "    ingest_time BIGINT,\n" +
            "    process_time BIGINT,\n" +
            "    -- 定义事件时间和 Watermark\n" +
            "    event_time AS TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000)),\n" +
            "    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND";
    
    /**
     * Doris DWD Sink 表 Schema
     * 用于写入 Doris DWD 层
     */
    public static final String DORIS_DWD_SINK_SCHEMA =
            "    inst_id STRING,\n" +
            "    `timestamp` BIGINT,\n" +
            "    trade_date DATE,\n" +
            "    trade_hour INT,\n" +
            "    last_price DECIMAL(20, 8),\n" +
            "    bid_price DECIMAL(20, 8),\n" +
            "    ask_price DECIMAL(20, 8),\n" +
            "    spread DECIMAL(20, 8),\n" +
            "    spread_rate DECIMAL(10, 6),\n" +
            "    volume_24h DECIMAL(30, 8),\n" +
            "    high_24h DECIMAL(20, 8),\n" +
            "    low_24h DECIMAL(20, 8),\n" +
            "    open_24h DECIMAL(20, 8),\n" +
            "    price_change_24h DECIMAL(20, 8),\n" +
            "    price_change_rate_24h DECIMAL(10, 6),\n" +
            "    amplitude_24h DECIMAL(10, 6),\n" +
            "    data_source STRING,\n" +
            "    ingest_time BIGINT,\n" +
            "    process_time BIGINT";
    
    /**
     * Doris DWS 1分钟 Sink 表 Schema
     * 用于写入 Doris DWS 层(1分钟 K 线)
     */
    public static final String DORIS_DWS_1MIN_SINK_SCHEMA =
            "    inst_id STRING,\n" +
            "    window_start BIGINT,\n" +
            "    window_end BIGINT,\n" +
            "    window_size INT,\n" +
            "    open_price DECIMAL(20, 8),\n" +
            "    high_price DECIMAL(20, 8),\n" +
            "    low_price DECIMAL(20, 8),\n" +
            "    close_price DECIMAL(20, 8),\n" +
            "    volume DECIMAL(30, 8),\n" +
            "    avg_price DECIMAL(20, 8),\n" +
            "    price_change DECIMAL(20, 8),\n" +
            "    price_change_rate DECIMAL(10, 6),\n" +
            "    tick_count INT,\n" +
            "    process_time BIGINT";
    
    /**
     * Doris DWS 1分钟 Source 表 Schema (带 Watermark)
     * 用于从 Doris DWS 层读取数据并进行窗口聚合
     */
    public static final String DORIS_DWS_1MIN_SOURCE_SCHEMA_WITH_WATERMARK =
            "    inst_id STRING,\n" +
            "    window_start BIGINT,\n" +
            "    window_end BIGINT,\n" +
            "    window_size INT,\n" +
            "    open_price DECIMAL(20, 8),\n" +
            "    high_price DECIMAL(20, 8),\n" +
            "    low_price DECIMAL(20, 8),\n" +
            "    close_price DECIMAL(20, 8),\n" +
            "    volume DECIMAL(30, 8),\n" +
            "    avg_price DECIMAL(20, 8),\n" +
            "    price_change DECIMAL(20, 8),\n" +
            "    price_change_rate DECIMAL(10, 6),\n" +
            "    tick_count INT,\n" +
            "    process_time BIGINT,\n" +
            "    -- 定义事件时间和 Watermark\n" +
            "    event_time AS TO_TIMESTAMP(FROM_UNIXTIME(window_end / 1000)),\n" +
            "    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND";
    
    /**
     * Doris ADS 实时指标 Sink 表 Schema
     * 用于写入 ADS 层实时指标表
     */
    public static final String DORIS_ADS_REALTIME_METRICS_SINK_SCHEMA =
            "    inst_id STRING,\n" +
            "    metric_time BIGINT,\n" +
            "    metric_date DATE,\n" +
            "    metric_hour INT,\n" +
            "    -- 价格指标\n" +
            "    current_price DECIMAL(20, 8),\n" +
            "    price_1min_ago DECIMAL(20, 8),\n" +
            "    price_5min_ago DECIMAL(20, 8),\n" +
            "    price_15min_ago DECIMAL(20, 8),\n" +
            "    price_1hour_ago DECIMAL(20, 8),\n" +
            "    -- 涨跌幅指标\n" +
            "    change_rate_1min DECIMAL(10, 6),\n" +
            "    change_rate_5min DECIMAL(10, 6),\n" +
            "    change_rate_15min DECIMAL(10, 6),\n" +
            "    change_rate_1hour DECIMAL(10, 6),\n" +
            "    change_rate_24hour DECIMAL(10, 6),\n" +
            "    -- 成交量指标\n" +
            "    volume_1min DECIMAL(30, 8),\n" +
            "    volume_5min DECIMAL(30, 8),\n" +
            "    volume_15min DECIMAL(30, 8),\n" +
            "    volume_1hour DECIMAL(30, 8),\n" +
            "    volume_24hour DECIMAL(30, 8),\n" +
            "    -- 波动率指标\n" +
            "    volatility_1min DECIMAL(10, 6),\n" +
            "    volatility_5min DECIMAL(10, 6),\n" +
            "    volatility_15min DECIMAL(10, 6),\n" +
            "    volatility_1hour DECIMAL(10, 6),\n" +
            "    -- 价格区间指标\n" +
            "    high_1min DECIMAL(20, 8),\n" +
            "    low_1min DECIMAL(20, 8),\n" +
            "    high_5min DECIMAL(20, 8),\n" +
            "    low_5min DECIMAL(20, 8),\n" +
            "    high_1hour DECIMAL(20, 8),\n" +
            "    low_1hour DECIMAL(20, 8),\n" +
            "    -- 趋势指标\n" +
            "    trend_1min STRING,\n" +
            "    trend_5min STRING,\n" +
            "    trend_1hour STRING,\n" +
            "    -- 元数据\n" +
            "    update_time BIGINT";
}
