package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink DWD 作业 - Flink SQL 方式
 * 从 ODS 层读取数据，进行清洗和字段补充，写入 DWD 层
 */
public class FlinkDWDJobSQL {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkDWDJobSQL.class);
    
    public static void main(String[] args) throws Exception {
        System.out.println("==========================================");
        System.out.println("Flink DWD Job (Flink SQL)");
        System.out.println("==========================================");
        System.out.println();
        
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
        
        System.out.println("Flink Environment:");
        System.out.println("  Parallelism: " + parallelism);
        System.out.println("  Checkpoint Interval: " + checkpointInterval + " ms");
        System.out.println();
        
        // 创建 Kafka Source 表（从 ODS Topic 读取）
        String kafkaSourceDDL = createKafkaSourceDDL(config);
        System.out.println("Creating Kafka Source Table...");
        tableEnv.executeSql(kafkaSourceDDL);
        
        // 创建 Doris DWD Sink 表
        String dorisSinkDDL = createDorisSinkDDL(config);
        System.out.println("Creating Doris DWD Sink Table...");
        tableEnv.executeSql(dorisSinkDDL);
        
        // 执行 INSERT INTO 语句（包含数据清洗和字段补充逻辑）
        String insertSQL = createInsertSQL();
        System.out.println("Executing INSERT SQL...");
        System.out.println(insertSQL);
        System.out.println();
        
        System.out.println("==========================================");
        System.out.println("Starting Flink DWD SQL Job...");
        System.out.println("==========================================");
        System.out.println();
        
        tableEnv.executeSql(insertSQL);
    }
    
    /**
     * 创建 Kafka Source 表 DDL
     */
    private static String createKafkaSourceDDL(ConfigLoader config) {
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        String topic = config.getString("kafka.topic.crypto-ticker");
        String groupId = "flink-dwd-consumer-sql";
        
        return "CREATE TABLE kafka_ods_source (\n" +
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
               "    proc_time AS PROCTIME()\n" +
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
     * 创建 Doris DWD Sink 表 DDL
     */
    private static String createDorisSinkDDL(ConfigLoader config) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String database = config.getString("doris.database");
        String table = config.getString("doris.tables.dwd");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        
        return "CREATE TABLE doris_dwd_sink (\n" +
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
               "    process_time BIGINT\n" +
               ") WITH (\n" +
               "    'connector' = 'doris',\n" +
               "    'fenodes' = '" + feNodes + "',\n" +
               "    'table.identifier' = '" + database + "." + table + "',\n" +
               "    'username' = '" + username + "',\n" +
               "    'password' = '" + password + "',\n" +
               "    'sink.batch.size' = '1000',\n" +
               "    'sink.batch.interval' = '5000ms',\n" +
               "    'sink.max-retries' = '3',\n" +
               "    'sink.properties.format' = 'json',\n" +
               "    'sink.properties.read_json_by_line' = 'true'\n" +
               ")";
    }
    
    /**
     * 创建 INSERT INTO SQL（包含数据清洗和字段补充逻辑）
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dwd_sink\n" +
               "SELECT \n" +
               "    inst_id,\n" +
               "    `timestamp`,\n" +
               "    -- 交易日期和小时\n" +
               "    TO_DATE(FROM_UNIXTIME(`timestamp` / 1000)) as trade_date,\n" +
               "    HOUR(FROM_UNIXTIME(`timestamp` / 1000)) as trade_hour,\n" +
               "    -- 价格字段\n" +
               "    last_price,\n" +
               "    bid_price,\n" +
               "    ask_price,\n" +
               "    -- 计算买卖价差\n" +
               "    (ask_price - bid_price) as spread,\n" +
               "    -- 计算价差率\n" +
               "    CASE \n" +
               "        WHEN last_price > 0 THEN (ask_price - bid_price) / last_price\n" +
               "        ELSE 0\n" +
               "    END as spread_rate,\n" +
               "    -- 成交量和价格\n" +
               "    volume_24h,\n" +
               "    high_24h,\n" +
               "    low_24h,\n" +
               "    open_24h,\n" +
               "    -- 计算24小时涨跌额\n" +
               "    CASE \n" +
               "        WHEN open_24h > 0 THEN (last_price - open_24h)\n" +
               "        ELSE 0\n" +
               "    END as price_change_24h,\n" +
               "    -- 计算24小时涨跌幅\n" +
               "    CASE \n" +
               "        WHEN open_24h > 0 THEN (last_price - open_24h) / open_24h\n" +
               "        ELSE 0\n" +
               "    END as price_change_rate_24h,\n" +
               "    -- 计算24小时振幅\n" +
               "    CASE \n" +
               "        WHEN low_24h > 0 THEN (high_24h - low_24h) / low_24h\n" +
               "        ELSE 0\n" +
               "    END as amplitude_24h,\n" +
               "    -- 数据源和时间戳\n" +
               "    'OKX' as data_source,\n" +
               "    `timestamp` as ingest_time,\n" +
               "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
               "FROM kafka_ods_source\n" +
               "WHERE last_price > 0 AND bid_price > 0 AND ask_price > 0";
    }
}
