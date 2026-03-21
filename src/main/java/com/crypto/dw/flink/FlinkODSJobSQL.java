package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink ODS 作业 - Flink SQL 方式
 * 从 Kafka 消费数据并写入 Doris ODS 层
 */
@Slf4j
public class FlinkODSJobSQL {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkODSJobSQL.class);
    
    public static void main(String[] args) throws Exception {
        log.info("==========================================");
        log.info("Flink ODS Job (Flink SQL)");
        log.info("==========================================");
        
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        Configuration flinkConfig = new Configuration();

        // 启用 Web UI（注意：端口参数必须是 int 类型）
        flinkConfig.setBoolean("web.submit.enable", config.getBoolean("flink.web.submit.enable", true));
        flinkConfig.setBoolean("web.cancel.enable", config.getBoolean("flink.web.cancel.enable", true));
        flinkConfig.setInteger("rest.port", config.getInt("flink.web.port", 8082));  // Web UI 端口
        flinkConfig.setString("rest.address", config.getString("flink.web.address", "0.0.0.0"));  // 监听地址
        flinkConfig.setString("rest.bind-port", "8081-8090");  // 端口范围（字符串类型）
        flinkConfig.setBoolean("rest.flamegraph.enabled",true);
        String pushgatewayHost = config.getString("application.metrics.pushgateway.host", "localhost");
        int pushgatewayPort = config.getInt("application.metrics.pushgateway.port", 9091);
        MetricsConfig.configurePushgatewayReporter(
                flinkConfig,
                pushgatewayHost,  // 从配置文件读取
                pushgatewayPort,  // 从配置文件读取
                "flink-ods-job"  // 作业名称
        );

        // 配置通用 Metrics 选项
        MetricsConfig.configureCommonMetrics(flinkConfig);
        // 创建 Flink 执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
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
        
        
        // 创建 Kafka Source 表
        String kafkaSourceDDL = createKafkaSourceDDL(config);
        log.info("Creating Kafka Source Table...");
        log.info(kafkaSourceDDL);
        
        tableEnv.executeSql(kafkaSourceDDL);
        
        // 创建 Doris Sink 表
        String dorisSinkDDL = createDorisSinkDDL(config);
        log.info("Creating Doris Sink Table...");
        log.info(dorisSinkDDL);
        
        tableEnv.executeSql(dorisSinkDDL);
        
        // 执行 INSERT INTO 语句
        String insertSQL = createInsertSQL();
        log.info("Executing INSERT SQL...");
        log.info(insertSQL);
        
        
        log.info("==========================================");
        log.info("Starting Flink SQL Job...");
        log.info("==========================================");
        
        
        tableEnv.executeSql(insertSQL);
    }
    
    /**
     * 创建 Kafka Source 表 DDL
     */
    private static String createKafkaSourceDDL(ConfigLoader config) {
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        String topic = config.getString("kafka.topic.crypto-ticker");
        String groupId = "flink-ods-consumer-sql";
        
        return "CREATE TABLE kafka_source (\n" +
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
     * 创建 Doris Sink 表 DDL
     */
    private static String createDorisSinkDDL(ConfigLoader config) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String database = config.getString("doris.database","crypto_dw");
        String table = config.getString("doris.tables.ods","ods_crypto_ticker_rt");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        int batchSize = config.getInt("doris.stream-load.batch-size", 1000);
        long batchInterval = config.getLong("doris.stream-load.batch-interval-ms", 5000);
        int maxRetries = config.getInt("doris.stream-load.max-retries", 3);
        
        // 获取 BE 节点地址 (用于解决 Docker 网络问题)
        // 如果不配置,Connector 会从 FE 获取 BE 地址,可能是 Docker 内部 IP
        String beNodes = config.getString("doris.be.nodes", "");
        
        // 生成唯一的 Label 前缀,避免 Label 冲突
        // 使用时间戳确保每次启动作业时 Label 都不同
        String labelPrefix = "flink_ods_" + System.currentTimeMillis();
        
        // 构建 WITH 子句
        StringBuilder withClause = new StringBuilder();
        withClause.append("    'connector' = 'doris',\n");
        withClause.append("    'fenodes' = '").append(feNodes).append("',\n");
        
        // 如果配置了 BE 地址,则添加 benodes 参数
        if (!beNodes.isEmpty()) {
            withClause.append("    'benodes' = '").append(beNodes).append("',\n");
            log.info("使用配置的 BE 地址: " + beNodes);
        } else {
            log.warn("未配置 BE 地址,将从 FE 获取 (可能导致 Docker 网络问题)");
        }
        
        withClause.append("    'table.identifier' = '").append(database).append(".").append(table).append("',\n");
        withClause.append("    'username' = '").append(username).append("',\n");
        withClause.append("    'password' = '").append(password).append("',\n");
        withClause.append("    'sink.label-prefix' = '").append(labelPrefix).append("',\n");  // 添加 Label 前缀
        withClause.append("    'sink.buffer-flush.max-rows' = '").append(batchSize).append("',\n");
        withClause.append("    'sink.buffer-flush.interval' = '").append(batchInterval).append("ms',\n");
        withClause.append("    'sink.max-retries' = '").append(maxRetries).append("',\n");
        withClause.append("    'sink.properties.format' = 'json',\n");
        withClause.append("    'sink.properties.read_json_by_line' = 'true'\n");
        
        log.info("Doris Sink Label 前缀: " + labelPrefix);
        
        return "CREATE TABLE doris_ods_sink (\n" +
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
               "    ingest_time BIGINT\n" +
               ") WITH (\n" +
               withClause.toString() +
               ")";
    }
    
    /**
     * 创建 INSERT INTO SQL
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_ods_sink\n" +
               "SELECT \n" +
               "    inst_id,\n" +
               "    `timestamp`,\n" +
               "    last_price,\n" +
               "    bid_price,\n" +
               "    ask_price,\n" +
               "    bid_size,\n" +
               "    ask_size,\n" +
               "    volume_24h,\n" +
               "    high_24h,\n" +
               "    low_24h,\n" +
               "    open_24h,\n" +
               "    'OKX' as data_source,\n" +
               "    UNIX_TIMESTAMP() * 1000 as ingest_time\n" +
               "FROM kafka_source";
    }
}
