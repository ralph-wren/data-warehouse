package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink DWS 作业 - 1分钟窗口聚合
 * 从 Kafka 读取数据，进行1分钟窗口聚合，生成 K 线数据写入 DWS 层
 */
@Slf4j
public class FlinkDWSJob1MinSQL {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkDWSJob1MinSQL.class);
    
    public static void main(String[] args) throws Exception {
        log.info("==========================================");
        log.info("Flink DWS Job - 1 Minute Window (Flink SQL)");
        log.info("==========================================");
        
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 创建 Flink 执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        
        // 设置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        // 启用 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
        env.enableCheckpointing(checkpointInterval);
        
        log.info("Flink Environment:");
        log.info("  Parallelism: " + parallelism);
        log.info("  Checkpoint Interval: " + checkpointInterval + " ms");
        
        
        // 创建 Kafka Source 表（带事件时间和 Watermark）
        String kafkaSourceDDL = createKafkaSourceDDL(config);
        log.info("Creating Kafka Source Table...");
        tableEnv.executeSql(kafkaSourceDDL);
        
        // 创建 Doris DWS Sink 表
        String dorisSinkDDL = createDorisSinkDDL(config);
        log.info("Creating Doris DWS Sink Table...");
        tableEnv.executeSql(dorisSinkDDL);
        
        // 执行窗口聚合 SQL
        String insertSQL = createInsertSQL();
        log.info("Executing Window Aggregation SQL...");
        log.info(insertSQL);
        
        
        log.info("==========================================");
        log.info("Starting Flink DWS 1Min SQL Job...");
        log.info("==========================================");
        
        
        tableEnv.executeSql(insertSQL);
    }
    
    /**
     * 创建 Kafka Source 表 DDL（带事件时间和 Watermark）
     */
    private static String createKafkaSourceDDL(ConfigLoader config) {
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        String topic = config.getString("kafka.topic.crypto-ticker");
        String groupId = "flink-dws-1min-consumer-sql";
        
        return "CREATE TABLE kafka_ticker_source (\n" +
               "    inst_id STRING,\n" +
               "    `timestamp` BIGINT,\n" +
               "    last_price DECIMAL(20, 8),\n" +
               "    bid_price DECIMAL(20, 8),\n" +
               "    ask_price DECIMAL(20, 8),\n" +
               "    volume_24h DECIMAL(30, 8),\n" +
               "    high_24h DECIMAL(20, 8),\n" +
               "    low_24h DECIMAL(20, 8),\n" +
               "    open_24h DECIMAL(20, 8),\n" +
               "    -- 定义事件时间和 Watermark\n" +
               "    event_time AS TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000)),\n" +
               "    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND\n" +
               ") WITH (\n" +
               "    'connector' = 'kafka',\n" +
               "    'topic' = '" + topic + "',\n" +
               "    'properties.bootstrap.servers' = '" + bootstrapServers + "',\n" +
               "    'properties.group.id' = '" + groupId + "',\n" +
               "    'scan.startup.mode' = 'latest-offset',\n" +
               "    'format' = 'json',\n" +
               "    'json.fail-on-missing-field' = 'false',\n" +
               "    'json.ignore-parse-errors' = 'true'\n" +
               ")";
    }
    
    /**
     * 创建 Doris DWS Sink 表 DDL
     */
    private static String createDorisSinkDDL(ConfigLoader config) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String database = config.getString("doris.database");
        String table = config.getString("doris.tables.dws-1min");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        
        return "CREATE TABLE doris_dws_1min_sink (\n" +
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
               "    process_time BIGINT\n" +
               ") WITH (\n" +
               "    'connector' = 'doris',\n" +
               "    'fenodes' = '" + feNodes + "',\n" +
               "    'table.identifier' = '" + database + "." + table + "',\n" +
               "    'username' = '" + username + "',\n" +
               "    'password' = '" + password + "',\n" +
               "    'sink.batch.size' = '100',\n" +
               "    'sink.batch.interval' = '10000ms',\n" +
               "    'sink.max-retries' = '3',\n" +
               "    'sink.properties.format' = 'json',\n" +
               "    'sink.properties.read_json_by_line' = 'true'\n" +
               ")";
    }
    
    /**
     * 创建窗口聚合 INSERT SQL
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dws_1min_sink\n" +
               "SELECT \n" +
               "    inst_id,\n" +
               "    -- 窗口时间戳（毫秒）\n" +
               "    UNIX_TIMESTAMP(CAST(window_start AS STRING)) * 1000 as window_start,\n" +
               "    UNIX_TIMESTAMP(CAST(window_end AS STRING)) * 1000 as window_end,\n" +
               "    1 as window_size,\n" +
               "    -- K 线数据：开高低收\n" +
               "    FIRST_VALUE(last_price) as open_price,\n" +
               "    MAX(last_price) as high_price,\n" +
               "    MIN(last_price) as low_price,\n" +
               "    LAST_VALUE(last_price) as close_price,\n" +
               "    -- 成交量（取最后一个值）\n" +
               "    LAST_VALUE(volume_24h) as volume,\n" +
               "    -- 平均价\n" +
               "    AVG(last_price) as avg_price,\n" +
               "    -- 涨跌额和涨跌幅\n" +
               "    LAST_VALUE(last_price) - FIRST_VALUE(last_price) as price_change,\n" +
               "    CASE \n" +
               "        WHEN FIRST_VALUE(last_price) > 0 \n" +
               "        THEN (LAST_VALUE(last_price) - FIRST_VALUE(last_price)) / FIRST_VALUE(last_price)\n" +
               "        ELSE 0\n" +
               "    END as price_change_rate,\n" +
               "    -- Tick 数量\n" +
               "    CAST(COUNT(*) AS INT) as tick_count,\n" +
               "    -- 处理时间\n" +
               "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
               "FROM TABLE(\n" +
               "    TUMBLE(TABLE kafka_ticker_source, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)\n" +
               ")\n" +
               "WHERE last_price > 0\n" +
               "GROUP BY inst_id, window_start, window_end";
    }
}
