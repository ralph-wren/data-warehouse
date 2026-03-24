package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.exception.FlinkJobExceptionHandler;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
import com.crypto.dw.utils.SqlFileLoader;
import lombok.extern.slf4j.Slf4j;
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

        // 使用统一的 Environment 工厂，确保状态后端、Checkpoint、重启策略等配置完全一致
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        
        // 从配置文件读取 Web UI 端口，避免硬编码
        // 优化说明：端口配置化，提高灵活性，避免端口冲突
        int webPort = config.getInt("flink.web.port.dwd", 8082);
        logger.info("Web UI 端口: {}", webPort);
        
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dwd-job", webPort);

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
            // 使用统一的异常处理器
            // 优化说明：统一异常处理，便于日志分析和问题定位
            FlinkJobExceptionHandler.handleException("Flink DWD SQL Job", e);
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
