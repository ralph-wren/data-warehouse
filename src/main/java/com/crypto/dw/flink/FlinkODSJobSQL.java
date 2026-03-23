package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
import lombok.extern.slf4j.Slf4j;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink ODS 作业 - Flink SQL 方式
 * 
 * 从 Kafka 消费数据并写入 Doris ODS 层
 * 
 * 重构说明:
 * - 使用 FlinkEnvironmentFactory 创建 Table Environment (减少约 40 行代码)
 * - 使用 FlinkTableFactory 创建 Kafka Source 和 Doris Sink 表 (减少约 80 行代码)
 * - 使用 TableSchemas 定义字段 (统一管理)
 */
@Slf4j
public class FlinkODSJobSQL {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkODSJobSQL.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ODS Job (Flink SQL)");
        logger.info("==========================================");
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 使用工厂类创建 Flink Table Environment (减少重复代码)
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-ods-job", 8081);
        
        // 使用工厂类创建表 (减少重复代码)
        FlinkTableFactory tableFactory = new FlinkTableFactory(config);
        
        // 创建 Kafka Source 表
        logger.info("Creating Kafka Source Table...");
        String kafkaSourceDDL = tableFactory.createKafkaSourceTable(
            "kafka_source",
            TableSchemas.KAFKA_TICKER_SCHEMA,
            false,  // 不需要 Watermark (ODS 层不做窗口聚合)
            "flink-kafka-source-somsumer"
        );
        tableEnv.executeSql(kafkaSourceDDL);
        
        // 创建 Doris ODS Sink 表
        logger.info("Creating Doris ODS Sink Table...");
        String dorisSinkDDL = tableFactory.createDorisSinkTable(
            "doris_ods_sink",
            "ods",
            TableSchemas.DORIS_ODS_SOURCE_SCHEMA  // ODS Sink 字段与 ODS Source 相同
        );
        tableEnv.executeSql(dorisSinkDDL);
        
        // 执行 INSERT INTO 语句
        String insertSQL = createInsertSQL();
        logger.info("Executing INSERT SQL...");
        
        logger.info("==========================================");
        logger.info("Starting Flink ODS SQL Job...");
        logger.info("==========================================");
        
        tableEnv.executeSql(insertSQL);
    }
    
    // 注意: 原来的 createKafkaSourceDDL 和 createDorisSinkDDL 方法已被工厂类替代
    // Environment 工厂类位置: com.crypto.dw.flink.factory.FlinkEnvironmentFactory
    // Table 工厂类位置: com.crypto.dw.flink.factory.FlinkTableFactory
    // Schema 定义位置: com.crypto.dw.flink.schema.TableSchemas
    
    /**
     * 创建 INSERT INTO SQL
     * 
     * 从 Kafka 读取数据,添加 data_source 和 ingest_time 字段后写入 Doris ODS 层
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
