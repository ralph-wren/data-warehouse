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
        // 重构说明: 使用显式参数传递，不再通过表类型隐式推断
        logger.info("Creating Doris DWD Sink Table...");
        String database = config.getString("doris.database");
        String dwdTable = config.getString("doris.tables.dwd");
        String dorisSinkDDL = tableFactory.createDorisSinkTable(
            "doris_dwd_sink",
            database,
            dwdTable,
            "dwd_" + System.currentTimeMillis(),  // Label 前缀
            TableSchemas.DORIS_DWD_SINK_SCHEMA
        );
        tableEnv.executeSql(dorisSinkDDL);

        // 从 SQL 文件加载 INSERT 语句
        // 优化说明：SQL 与代码分离，便于维护和修改
        logger.info("加载 DWD INSERT SQL...");
        String insertSQL = SqlFileLoader.loadSql("sql/flink/dwd_insert.sql");

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
}
