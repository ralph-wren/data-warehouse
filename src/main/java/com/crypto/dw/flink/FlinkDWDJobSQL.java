package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import org.apache.flink.configuration.Configuration;
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
        
        // 创建 Flink 执行环境（启用 Web UI 和 Metrics）
        Configuration flinkConfig = new Configuration();
        
        // 启用 Web UI（注意：端口参数必须是 int 类型）
        flinkConfig.setBoolean("web.submit.enable", true);
        flinkConfig.setBoolean("web.cancel.enable", true);
        flinkConfig.setInteger("rest.port", 8082);  // Web UI 端口（避免与 ODS 作业冲突）
        flinkConfig.setString("rest.address", "localhost");  // 监听地址
        flinkConfig.setString("rest.bind-port", "8082-8090");  // 端口范围（字符串类型）
        
        // 配置 Prometheus Metrics（推送到 Pushgateway）
        MetricsConfig.configurePushgatewayReporter(
            flinkConfig,
            "localhost",  // Pushgateway 主机
            9091,         // Pushgateway 端口
            "flink-dwd-job"  // 作业名称
        );
        
        // 配置通用 Metrics 选项
        MetricsConfig.configureCommonMetrics(flinkConfig);

        StreamExecutionEnvironment env = StreamExecutionEnvironment.createLocalEnvironmentWithWebUI(flinkConfig);
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        
        // 设置时区为中国时区（东八区）- 重要：确保日期计算正确
        tableEnv.getConfig().setLocalTimeZone(java.time.ZoneId.of("Asia/Shanghai"));
        
        // 设置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        // 启用 Checkpoint（批量配置优化后应该能正常工作）
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
        env.enableCheckpointing(checkpointInterval);
        
        System.out.println("Flink Environment:");
        System.out.println("  Parallelism: " + parallelism);
        System.out.println("  Checkpoint Interval: " + checkpointInterval + " ms");
        System.out.println("  Web UI: http://localhost:8082");
        System.out.println("  Metrics: Pushgateway at localhost:9091");
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
        
        // 执行 INSERT 语句并等待作业完成
        // 注意：executeSql 对于 INSERT 语句会异步执行，需要调用 await() 等待
        try {
            logger.info("提交 Flink DWD SQL 作业...");
            var tableResult = tableEnv.executeSql(insertSQL);
            
            logger.info("Flink DWD SQL 作业已提交，Job ID: {}", tableResult.getJobClient().get().getJobID());
            System.out.println("✓ Flink DWD SQL 作业已启动");
            System.out.println("✓ 作业将持续运行，处理 Kafka 数据并写入 Doris");
            System.out.println("✓ 按 Ctrl+C 停止作业");
            System.out.println();
            
            // 等待作业完成（流式作业会一直运行）
            tableResult.await();
            
        } catch (Exception e) {
            logger.error("Flink DWD SQL 作业执行失败", e);
            System.err.println("✗ 作业执行失败: " + e.getMessage());
            e.printStackTrace();
            throw e;
        }
    }
    
    /**
     * 创建 Kafka Source 表 DDL
     * 注意：字段名必须与 Kafka 消息中的 JSON 字段名匹配
     */
    private static String createKafkaSourceDDL(ConfigLoader config) {
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        String topic = config.getString("kafka.topic.crypto-ticker");
        String groupId = "flink-dwd-consumer-sql";
        
        return "CREATE TABLE kafka_ods_source (\n" +
               "    instId STRING,\n" +  // 匹配 JSON 字段名
               "    ts BIGINT,\n" +  // 匹配 JSON 字段名 (timestamp)
               "    `last` STRING,\n" +  // 匹配 JSON 字段名，使用反引号因为 last 是关键字
               "    bidPx STRING,\n" +  // 匹配 JSON 字段名
               "    askPx STRING,\n" +  // 匹配 JSON 字段名
               "    bidSz STRING,\n" +  // 匹配 JSON 字段名
               "    askSz STRING,\n" +  // 匹配 JSON 字段名
               "    volCcy24h STRING,\n" +  // 匹配 JSON 字段名
               "    high24h STRING,\n" +  // 匹配 JSON 字段名
               "    low24h STRING,\n" +  // 匹配 JSON 字段名
               "    open24h STRING,\n" +  // 匹配 JSON 字段名
               "    proc_time AS PROCTIME()\n" +
               ") WITH (\n" +
               "    'connector' = 'kafka',\n" +
               "    'topic' = '" + topic + "',\n" +
               "    'properties.bootstrap.servers' = '" + bootstrapServers + "',\n" +
               "    'properties.group.id' = '" + groupId + "',\n" +
               "    'scan.startup.mode' = 'earliest-offset',\n" +  // 从最早的数据开始消费
               "    'format' = 'json',\n" +
               "    'json.fail-on-missing-field' = 'false',\n" +
               "    'json.ignore-parse-errors' = 'true'\n" +
               ")";
    }
    
    /**
     * 创建 Doris DWD Sink 表 DDL
     * 注意：必须指定 benodes 参数来覆盖自动发现的 BE 地址
     */
    private static String createDorisSinkDDL(ConfigLoader config) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String beNodes = config.getString("doris.be.nodes", "127.0.0.1:8040");  // 获取 BE 节点配置
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
               "    'benodes' = '" + beNodes + "',\n" +  // 关键：指定 BE 节点地址
               "    'table.identifier' = '" + database + "." + table + "',\n" +
               "    'username' = '" + username + "',\n" +
               "    'password' = '" + password + "',\n" +
               "    -- 批量写入配置（使用正确的参数名称）\n" +
               "    'sink.buffer-size' = '10485760',\n" +  // 缓冲区大小：10MB (10 * 1024 * 1024)
               "    'sink.buffer-flush.max-rows' = '1000',\n" +  // 批量行数：1000 行
               "    'sink.buffer-flush.interval' = '5s',\n" +  // 批量间隔：5 秒
               "    'sink.max-retries' = '5',\n" +  // 增加重试次数
               "    'sink.properties.format' = 'json',\n" +
               "    'sink.properties.read_json_by_line' = 'true',\n" +
               "    -- 数据质量配置（放宽限制，避免分区不匹配导致整批失败）\n" +
               "    'sink.properties.strict_mode' = 'false',\n" +  // 关闭严格模式
               "    'sink.properties.max_filter_ratio' = '0.5'\n" +  // 允许 50% 的数据过滤（应对分区问题）
               ")";
    }
    
    /**
     * 创建 INSERT INTO SQL（包含数据清洗和字段补充逻辑）
     * 注意: 
     * 1. 源表字段名匹配 Kafka JSON 格式（驼峰命名）
     * 2. 需要将 STRING 类型转换为 DECIMAL 类型
     * 3. 使用 Flink SQL 标准函数
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dwd_sink\n" +
               "SELECT \n" +
               "    instId as inst_id,\n" +  // 字段名转换
               "    ts as `timestamp`,\n" +  // 字段名转换
               "    -- 交易日期和小时 (使用 TO_TIMESTAMP 将毫秒时间戳转换为 TIMESTAMP)\n" +
               "    CAST(TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000)) AS DATE) as trade_date,\n" +
               "    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000))) AS INT) as trade_hour,\n" +
               "    -- 价格字段（将 STRING 转换为 DECIMAL）\n" +
               "    CAST(`last` AS DECIMAL(20, 8)) as last_price,\n" +
               "    CAST(bidPx AS DECIMAL(20, 8)) as bid_price,\n" +
               "    CAST(askPx AS DECIMAL(20, 8)) as ask_price,\n" +
               "    -- 计算买卖价差（转换为 DECIMAL(20, 8)）\n" +
               "    CAST((CAST(askPx AS DECIMAL(20, 8)) - CAST(bidPx AS DECIMAL(20, 8))) AS DECIMAL(20, 8)) as spread,\n" +
               "    -- 计算价差率（转换为 DECIMAL(10, 6)）\n" +
               "    CAST(CASE \n" +
               "        WHEN CAST(`last` AS DECIMAL(20, 8)) > 0 THEN \n" +
               "            (CAST(askPx AS DECIMAL(20, 8)) - CAST(bidPx AS DECIMAL(20, 8))) / CAST(`last` AS DECIMAL(20, 8))\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) as spread_rate,\n" +
               "    -- 成交量和价格（将 STRING 转换为 DECIMAL）\n" +
               "    CAST(volCcy24h AS DECIMAL(30, 8)) as volume_24h,\n" +
               "    CAST(high24h AS DECIMAL(20, 8)) as high_24h,\n" +
               "    CAST(low24h AS DECIMAL(20, 8)) as low_24h,\n" +
               "    CAST(open24h AS DECIMAL(20, 8)) as open_24h,\n" +
               "    -- 计算24小时涨跌额（转换为 DECIMAL(20, 8)）\n" +
               "    CAST(CASE \n" +
               "        WHEN CAST(open24h AS DECIMAL(20, 8)) > 0 THEN \n" +
               "            (CAST(`last` AS DECIMAL(20, 8)) - CAST(open24h AS DECIMAL(20, 8)))\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(20, 8)) as price_change_24h,\n" +
               "    -- 计算24小时涨跌幅（转换为 DECIMAL(10, 6)）\n" +
               "    CAST(CASE \n" +
               "        WHEN CAST(open24h AS DECIMAL(20, 8)) > 0 THEN \n" +
               "            (CAST(`last` AS DECIMAL(20, 8)) - CAST(open24h AS DECIMAL(20, 8))) / CAST(open24h AS DECIMAL(20, 8))\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) as price_change_rate_24h,\n" +
               "    -- 计算24小时振幅（转换为 DECIMAL(10, 6)）\n" +
               "    CAST(CASE \n" +
               "        WHEN CAST(low24h AS DECIMAL(20, 8)) > 0 THEN \n" +
               "            (CAST(high24h AS DECIMAL(20, 8)) - CAST(low24h AS DECIMAL(20, 8))) / CAST(low24h AS DECIMAL(20, 8))\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) as amplitude_24h,\n" +
               "    -- 数据源和时间戳\n" +
               "    CAST('OKX' AS STRING) as data_source,\n" +
               "    ts as ingest_time,\n" +
               "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
               "FROM kafka_ods_source\n" +
               "WHERE CAST(`last` AS DECIMAL(20, 8)) > 0 \n" +
               "  AND CAST(bidPx AS DECIMAL(20, 8)) > 0 \n" +
               "  AND CAST(askPx AS DECIMAL(20, 8)) > 0";
    }
}
