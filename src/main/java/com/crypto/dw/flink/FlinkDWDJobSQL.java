package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.TableResult;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink DWD 作业 - Flink SQL 方式
 *
 * 架构修复说明:
 * - 修复前: 直接消费 Kafka Topic (错误)
 * - 修复后: 从 Doris ODS 表读取数据 (正确)
 *
 * 数据流转: Kafka → ODS 作业 → ODS 表 → DWD 作业 → DWD 表
 *
 * 从 ODS 层读取数据,进行清洗和字段补充,写入 DWD 层
 */
@Slf4j
public class FlinkDWDJobSQL {

    private static final Logger logger = LoggerFactory.getLogger(FlinkDWDJobSQL.class);

    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink DWD Job (Flink SQL)");
        logger.info("架构: Doris ODS 表 → DWD 作业 → Doris DWD 表");
        logger.info("==========================================");

        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();

        // 创建 Flink 执行环境(启用 Web UI 和 Metrics)
        Configuration flinkConfig = new Configuration();

        // 启用 Web UI(注意:端口参数必须是 int 类型)
        flinkConfig.setBoolean("web.submit.enable", true);
        flinkConfig.setBoolean("web.cancel.enable", true);
        flinkConfig.setInteger("rest.port", 8082);  // Web UI 端口(避免与 ODS 作业冲突)
        flinkConfig.setString("rest.address", "localhost");  // 监听地址
        flinkConfig.setString("rest.bind-port", "8082-8090");  // 端口范围(字符串类型)

        // 配置 Prometheus Metrics(推送到 Pushgateway)
        MetricsConfig.configurePushgatewayReporter(
                flinkConfig,
                "localhost",  // Pushgateway 主机
                9091,         // Pushgateway 端口
                "flink-dwd-job"  // 作业名称
        );

        // 配置通用 Metrics 选项
        MetricsConfig.configureCommonMetrics(flinkConfig);

        // 创建 Flink 执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);

        // 设置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);

        // 启用 Checkpoint(批量配置优化后应该能正常工作)
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
        env.enableCheckpointing(checkpointInterval);

        logger.info("Flink Environment:");
        logger.info("  Parallelism: " + parallelism);
        logger.info("  Checkpoint Interval: " + checkpointInterval + " ms");
        logger.info("  Web UI: http://localhost:8082");
        logger.info("  Metrics: Pushgateway at localhost:9091");

        // 创建 Table Environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

        // 创建 Doris ODS Source 表(从 Doris ODS 层读取数据)
        String dorisSourceDDL = createDorisSourceDDL(config);
        logger.info("Creating Doris ODS Source Table...");
        tableEnv.executeSql(dorisSourceDDL);

        // 创建 Doris DWD Sink 表
        String dorisSinkDDL = createDorisSinkDDL(config);
        logger.info("Creating Doris DWD Sink Table...");
        tableEnv.executeSql(dorisSinkDDL);

        // 创建 INSERT SQL
        String insertSQL = createInsertSQL();

        logger.info("==========================================");
        logger.info("Starting Flink DWD SQL Job...");
        logger.info("==========================================");

        // 执行 INSERT 语句并等待作业完成
        // 注意:executeSql 对于 INSERT 语句会异步执行,需要调用 await() 等待
        try {
            logger.info("提交 Flink DWD SQL 作业...");
            TableResult tableResult = tableEnv.executeSql(insertSQL);

            logger.info("Flink DWD SQL 作业已提交,Job ID: {}", tableResult.getJobClient().get().getJobID());
            logger.info("✓ Flink DWD SQL 作业已启动");
            logger.info("✓ 作业将持续运行，从 Doris ODS 表读取数据并写入 DWD 表");
            logger.info("✓ 按 Ctrl+C 停止作业");

            // 等待作业完成(流式作业会一直运行)
            tableResult.await();

        } catch (Exception e) {
            logger.error("Flink DWD SQL 作业执行失败", e);
            throw e;
        }
    }

    /**
     * 创建 Doris ODS Source 表 DDL
     *
     * 架构修复说明:
     * - 修复前: DWD 作业直接消费 Kafka Topic (错误)
     * - 修复后: DWD 作业从 Doris ODS 表读取数据 (正确)
     *
     * 数据流转: Kafka → ODS 作业 → ODS 表 → DWD 作业 → DWD 表
     *
     * 注意:字段名匹配 Doris ODS 表字段名(下划线命名)
     */
    private static String createDorisSourceDDL(ConfigLoader config) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String database = config.getString("doris.database");
        String odsTable = config.getString("doris.tables.ods");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");

        logger.info("Doris ODS Source 配置:");
        logger.info("  FE Nodes: " + feNodes);
        logger.info("  Database: " + database);
        logger.info("  Table: " + odsTable);

        return "CREATE TABLE doris_ods_source (\n" +
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
                "    'connector' = 'doris',\n" +
                "    'fenodes' = '" + feNodes + "',\n" +
                "    'table.identifier' = '" + database + "." + odsTable + "',\n" +
                "    'username' = '" + username + "',\n" +
                "    'password' = '" + password + "'\n" +
                ")";
    }

    /**
     * 创建 Doris DWD Sink 表 DDL
     * 注意:必须指定 benodes 参数来覆盖自动发现的 BE 地址
     */
    private static String createDorisSinkDDL(ConfigLoader config) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String beNodes = config.getString("doris.be.nodes", "127.0.0.1:8040");
        String database = config.getString("doris.database");
        String table = config.getString("doris.tables.dwd");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");

        logger.info("Doris DWD Sink 配置:");
        logger.info("  FE Nodes: " + feNodes);
        logger.info("  BE Nodes: " + beNodes);
        logger.info("  Database: " + database);
        logger.info("  Table: " + table);

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
                "    'benodes' = '" + beNodes + "',\n" +
                "    'table.identifier' = '" + database + "." + table + "',\n" +
                "    'username' = '" + username + "',\n" +
                "    'password' = '" + password + "',\n" +
                "    'sink.label-prefix' = 'dwd_" + System.currentTimeMillis() + "',\n" +
                "    'sink.properties.format' = 'json',\n" +
                "    'sink.properties.read_json_by_line' = 'true'\n" +
                ")";
    }

    /**
     * 创建 INSERT INTO SQL(包含数据清洗和字段补充逻辑)
     *
     * 架构修复说明:
     * - 修复前: 从 kafka_ods_source 读取(Kafka JSON 格式,驼峰命名)
     * - 修复后: 从 doris_ods_source 读取(Doris 表格式,下划线命名)
     *
     * 注意:
     * 1. 源表字段名匹配 Doris ODS 表字段名(下划线命名)
     * 2. ODS 表中的字段已经是 DECIMAL 类型,不需要再转换
     * 3. 使用 Flink SQL 标准函数进行数据清洗和计算
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dwd_sink\n" +
                "SELECT \n" +
                "    inst_id,\n" +
                "    `timestamp`,\n" +
                "    CAST(TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000)) AS DATE) as trade_date,\n" +
                "    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000))) AS INT) as trade_hour,\n" +
                "    last_price,\n" +
                "    bid_price,\n" +
                "    ask_price,\n" +
                "    CAST((ask_price - bid_price) AS DECIMAL(20, 8)) as spread,\n" +
                "    CAST(CASE \n" +
                "        WHEN last_price > 0 THEN (ask_price - bid_price) / last_price\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(10, 6)) as spread_rate,\n" +
                "    volume_24h,\n" +
                "    high_24h,\n" +
                "    low_24h,\n" +
                "    open_24h,\n" +
                "    CAST(CASE \n" +
                "        WHEN open_24h > 0 THEN (last_price - open_24h)\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(20, 8)) as price_change_24h,\n" +
                "    CAST(CASE \n" +
                "        WHEN open_24h > 0 THEN (last_price - open_24h) / open_24h\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(10, 6)) as price_change_rate_24h,\n" +
                "    CAST(CASE \n" +
                "        WHEN low_24h > 0 THEN (high_24h - low_24h) / low_24h\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(10, 6)) as amplitude_24h,\n" +
                "    data_source,\n" +
                "    ingest_time,\n" +
                "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
                "FROM doris_ods_source\n" +
                "WHERE last_price > 0 \n" +
                "  AND bid_price > 0 \n" +
                "  AND ask_price > 0";
    }
}
