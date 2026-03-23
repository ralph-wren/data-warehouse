package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
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
 * 架构说明:
 * - 正确架构: 直接从 Kafka 消费数据 ✅
 * - 数据流转: Kafka → DWD 作业 → Doris DWD 表
 *
 * 从 Kafka 读取原始数据,进行清洗和字段补充,写入 DWD 层
 * 
 * 注意: 所有层级（ODS/DWD/DWS）都应该从 Kafka 读取，实时流式处理
 */
@Slf4j
public class FlinkDWDJobSQL {

    private static final Logger logger = LoggerFactory.getLogger(FlinkDWDJobSQL.class);

    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink DWD Job (Flink SQL)");
        logger.info("架构: Kafka → DWD 作业 → Doris DWD 表");
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

        // 启用 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
        env.enableCheckpointing(checkpointInterval);

        logger.info("Flink Environment:");
        logger.info("  Parallelism: " + parallelism);
        logger.info("  Checkpoint Interval: " + checkpointInterval + " ms");
        logger.info("  Web UI: http://localhost:8082");
        logger.info("  Metrics: Pushgateway at localhost:9091");

        // 创建 Table Environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        
        // 设置时区为中国时区(东八区) - 重要:确保日期计算正确
        tableEnv.getConfig().setLocalTimeZone(java.time.ZoneId.of("Asia/Shanghai"));

        // 使用工厂类创建表
        FlinkTableFactory tableFactory = new FlinkTableFactory(config);

        // 创建 Kafka Source 表（从 Kafka 读取原始数据）✅
        logger.info("Creating Kafka Source Table...");
        String kafkaSourceDDL = tableFactory.createKafkaSourceTable(
            "kafka_ticker_source",
            TableSchemas.KAFKA_TICKER_SOURCE_SCHEMA,
            true,  // 带 Watermark
            "flink-dwd-consumer"  // Consumer Group ID（独立的消费组）
        );
        tableEnv.executeSql(kafkaSourceDDL);

        // 创建 Doris DWD Sink 表
        logger.info("Creating Doris DWD Sink Table...");
        String dorisSinkDDL = tableFactory.createDorisSinkTable(
            "doris_dwd_sink",
            "dwd",
            TableSchemas.DORIS_DWD_SINK_SCHEMA
        );
        tableEnv.executeSql(dorisSinkDDL);

        // 创建 INSERT SQL
        String insertSQL = createInsertSQL();

        logger.info("==========================================");
        logger.info("Starting Flink DWD SQL Job...");
        logger.info("==========================================");

        // 执行 INSERT 语句并等待作业完成
        try {
            logger.info("提交 Flink DWD SQL 作业...");
            TableResult tableResult = tableEnv.executeSql(insertSQL);

            logger.info("Flink DWD SQL 作业已提交,Job ID: {}", tableResult.getJobClient().get().getJobID());
            logger.info("✓ Flink DWD SQL 作业已启动");
            logger.info("✓ 作业将持续运行，从 Kafka 读取数据并写入 DWD 表");
            logger.info("✓ 按 Ctrl+C 停止作业");

            // 等待作业完成(流式作业会一直运行)
            tableResult.await();

        } catch (Exception e) {
            logger.error("Flink DWD SQL 作业执行失败", e);
            throw e;
        }
    }

    /**
     * 创建 INSERT INTO SQL(包含数据清洗和字段补充逻辑)
     *
     * 架构说明:
     * - 从 kafka_ticker_source 读取（Kafka 原始数据，JSON 格式，驼峰命名）✅
     *
     * 注意:
     * 1. 源表字段名使用驼峰命名（instId, ts, last 等）
     * 2. 需要将 JSON 字段转换为 DECIMAL 类型
     * 3. 使用 Flink SQL 标准函数进行数据清洗和计算
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dwd_sink\n" +
                "SELECT \n" +
                "    instId as inst_id,\n" +
                "    ts as `timestamp`,\n" +
                "    CAST(TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000)) AS DATE) as trade_date,\n" +
                "    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000))) AS INT) as trade_hour,\n" +
                "    CAST(last AS DECIMAL(20, 8)) as last_price,\n" +
                "    CAST(bidPx AS DECIMAL(20, 8)) as bid_price,\n" +
                "    CAST(askPx AS DECIMAL(20, 8)) as ask_price,\n" +
                "    CAST((CAST(askPx AS DECIMAL(20, 8)) - CAST(bidPx AS DECIMAL(20, 8))) AS DECIMAL(20, 8)) as spread,\n" +
                "    CAST(CASE \n" +
                "        WHEN CAST(last AS DECIMAL(20, 8)) > 0 THEN (CAST(askPx AS DECIMAL(20, 8)) - CAST(bidPx AS DECIMAL(20, 8))) / CAST(last AS DECIMAL(20, 8))\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(10, 6)) as spread_rate,\n" +
                "    CAST(vol24h AS DECIMAL(30, 8)) as volume_24h,\n" +
                "    CAST(high24h AS DECIMAL(20, 8)) as high_24h,\n" +
                "    CAST(low24h AS DECIMAL(20, 8)) as low_24h,\n" +
                "    CAST(open24h AS DECIMAL(20, 8)) as open_24h,\n" +
                "    CAST(CASE \n" +
                "        WHEN CAST(open24h AS DECIMAL(20, 8)) > 0 THEN (CAST(last AS DECIMAL(20, 8)) - CAST(open24h AS DECIMAL(20, 8)))\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(20, 8)) as price_change_24h,\n" +
                "    CAST(CASE \n" +
                "        WHEN CAST(open24h AS DECIMAL(20, 8)) > 0 THEN (CAST(last AS DECIMAL(20, 8)) - CAST(open24h AS DECIMAL(20, 8))) / CAST(open24h AS DECIMAL(20, 8))\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(10, 6)) as price_change_rate_24h,\n" +
                "    CAST(CASE \n" +
                "        WHEN CAST(low24h AS DECIMAL(20, 8)) > 0 THEN (CAST(high24h AS DECIMAL(20, 8)) - CAST(low24h AS DECIMAL(20, 8))) / CAST(low24h AS DECIMAL(20, 8))\n" +
                "        ELSE 0\n" +
                "    END AS DECIMAL(10, 6)) as amplitude_24h,\n" +
                "    CAST('OKX' AS STRING) as data_source,\n" +
                "    ts as ingest_time,\n" +
                "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
                "FROM kafka_ticker_source\n" +
                "WHERE CAST(last AS DECIMAL(20, 8)) > 0 \n" +
                "  AND CAST(bidPx AS DECIMAL(20, 8)) > 0 \n" +
                "  AND CAST(askPx AS DECIMAL(20, 8)) > 0";
    }
}
