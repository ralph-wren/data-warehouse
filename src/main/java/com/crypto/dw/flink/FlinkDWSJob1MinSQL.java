package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
import com.crypto.dw.utils.SqlFileLoader;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink DWS 作业 - 1分钟窗口聚合
 * 
 * 架构说明:
 * - 正确架构: 直接从 Kafka 读取数据 ✅
 * - 数据流转: Kafka → DWS 作业 → Doris DWS 表
 * 
 * 从 Kafka 读取原始数据，进行1分钟窗口聚合，生成 K 线数据写入 DWS 层
 * 
 * 注意: 所有层级（ODS/DWD/DWS）都应该从 Kafka 读取，实时流式处理
 */
@Slf4j
public class FlinkDWSJob1MinSQL {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkDWSJob1MinSQL.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink DWS Job - 1 Minute Window (Flink SQL)");
        logger.info("架构: Kafka → DWS 作业 → Doris DWS 表");
        logger.info("==========================================");
        
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 使用工厂类创建 Flink Table Environment(减少重复代码)
        // 从配置文件读取 Web UI 端口，避免硬编码
        // 优化说明：端口配置化，提高灵活性，避免端口冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        int webPort = config.getInt("flink.web.port.dws-1min", 8084);
        logger.info("Web UI 端口: {}", webPort);
        
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dws-1min-job", webPort);
        
        // 使用工厂类创建表(减少重复代码)
        FlinkTableFactory tableFactory = new FlinkTableFactory(config);
        
        // 创建 Kafka Source 表（从 Kafka 读取原始数据，带 Watermark）✅
        logger.info("Creating Kafka Source Table...");
        String kafkaSourceDDL = tableFactory.createKafkaSourceTable(
            "kafka_ticker_source",
            TableSchemas.KAFKA_TICKER_SOURCE_SCHEMA,
            true,  // 带 Watermark
            "flink-dws-1min-consumer"  // Consumer Group ID（独立的消费组）
        );
        tableEnv.executeSql(kafkaSourceDDL);
        
        // 创建 Doris DWS Sink 表
        // 重构说明: 使用显式参数传递，不再通过表类型隐式推断
        logger.info("Creating Doris DWS Sink Table...");
        String database = config.getString("doris.database");
        String dwsTable = config.getString("doris.tables.dws-1min");
        String dorisSinkDDL = tableFactory.createDorisSinkTable(
            "doris_dws_1min_sink",
            database,
            dwsTable,
            "dws_1min_" + System.currentTimeMillis(),  // Label 前缀
            TableSchemas.DORIS_DWS_1MIN_SINK_SCHEMA
        );
        tableEnv.executeSql(dorisSinkDDL);
        
        // 从 SQL 文件加载窗口聚合 SQL
        // 优化说明：SQL 与代码分离，便于维护和修改
        logger.info("加载 DWS 1Min INSERT SQL...");
        String insertSQL = SqlFileLoader.loadSql("sql/flink/dws_1min_insert.sql");
        logger.info("Executing Window Aggregation SQL...");
        
        
        logger.info("==========================================");
        logger.info("Starting Flink DWS 1Min SQL Job...");
        logger.info("==========================================");
        
        
        tableEnv.executeSql(insertSQL);
    }
}
