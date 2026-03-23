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
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dws-1min-job", 8083);
        
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
        logger.info("Creating Doris DWS Sink Table...");
        String dorisSinkDDL = tableFactory.createDorisSinkTable(
            "doris_dws_1min_sink",
            "dws-1min",
            TableSchemas.DORIS_DWS_1MIN_SINK_SCHEMA
        );
        tableEnv.executeSql(dorisSinkDDL);
        
        // 执行窗口聚合 SQL
        String insertSQL = createInsertSQL();
        logger.info("Executing Window Aggregation SQL...");
        
        
        logger.info("==========================================");
        logger.info("Starting Flink DWS 1Min SQL Job...");
        logger.info("==========================================");
        
        
        tableEnv.executeSql(insertSQL);
    }
    
    /**
     * 创建窗口聚合 INSERT SQL
     * 
     * 架构说明:
     * - 从 kafka_ticker_source 读取（Kafka 原始数据，JSON 格式，驼峰命名）✅
     * 
     * 注意: 
     * 1. 源表字段名使用驼峰命名（instId, ts, last 等）
     * 2. 需要将 JSON 字段转换为 DECIMAL 类型
     * 3. 1分钟滚动窗口聚合,生成 K 线数据
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dws_1min_sink\n" +
               "SELECT \n" +
               "    instId as inst_id,\n" +
               "    -- 窗口时间戳（毫秒）\n" +
               "    UNIX_TIMESTAMP(CAST(window_start AS STRING)) * 1000 as window_start,\n" +
               "    UNIX_TIMESTAMP(CAST(window_end AS STRING)) * 1000 as window_end,\n" +
               "    1 as window_size,\n" +
               "    -- K 线数据：开高低收\n" +
               "    FIRST_VALUE(CAST(last AS DECIMAL(20, 8))) as open_price,\n" +
               "    MAX(CAST(last AS DECIMAL(20, 8))) as high_price,\n" +
               "    MIN(CAST(last AS DECIMAL(20, 8))) as low_price,\n" +
               "    LAST_VALUE(CAST(last AS DECIMAL(20, 8))) as close_price,\n" +
               "    -- 成交量（取最后一个值）\n" +
               "    LAST_VALUE(CAST(vol24h AS DECIMAL(30, 8))) as volume,\n" +
               "    -- 平均价\n" +
               "    AVG(CAST(last AS DECIMAL(20, 8))) as avg_price,\n" +
               "    -- 涨跌额和涨跌幅\n" +
               "    LAST_VALUE(CAST(last AS DECIMAL(20, 8))) - FIRST_VALUE(CAST(last AS DECIMAL(20, 8))) as price_change,\n" +
               "    CASE \n" +
               "        WHEN FIRST_VALUE(CAST(last AS DECIMAL(20, 8))) > 0 \n" +
               "        THEN (LAST_VALUE(CAST(last AS DECIMAL(20, 8))) - FIRST_VALUE(CAST(last AS DECIMAL(20, 8)))) / FIRST_VALUE(CAST(last AS DECIMAL(20, 8)))\n" +
               "        ELSE 0\n" +
               "    END as price_change_rate,\n" +
               "    -- Tick 数量\n" +
               "    CAST(COUNT(*) AS INT) as tick_count,\n" +
               "    -- 处理时间\n" +
               "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
               "FROM TABLE(\n" +
               "    TUMBLE(TABLE kafka_ticker_source, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)\n" +
               ")\n" +
               "WHERE CAST(last AS DECIMAL(20, 8)) > 0\n" +
               "GROUP BY instId, window_start, window_end";
    }
}
