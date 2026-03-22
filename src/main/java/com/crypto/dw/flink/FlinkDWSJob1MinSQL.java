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
 * 架构修复说明:
 * - 修复前: 直接从 Kafka 读取数据 (错误)
 * - 修复后: 从 Doris DWD 表读取数据 (正确)
 * 
 * 数据流转: Kafka → ODS 作业 → ODS 表 → DWD 作业 → DWD 表 → DWS 作业 → DWS 表
 * 
 * 从 DWD 层读取数据，进行1分钟窗口聚合，生成 K 线数据写入 DWS 层
 */
@Slf4j
public class FlinkDWSJob1MinSQL {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkDWSJob1MinSQL.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink DWS Job - 1 Minute Window (Flink SQL)");
        logger.info("架构: Doris DWD 表 → DWS 作业 → Doris DWS 表");
        logger.info("==========================================");
        
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 使用工厂类创建 Flink Table Environment(减少重复代码)
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dws-1min-job", 8083);
        
        // 使用工厂类创建表(减少重复代码)
        FlinkTableFactory tableFactory = new FlinkTableFactory(config);
        
        // 创建 Doris DWD Source 表（从 DWD 层读取数据,带 Watermark）
        logger.info("Creating Doris DWD Source Table...");
        String dwdSourceDDL = tableFactory.createDorisSourceTable(
            "doris_dwd_source",
            "dwd",
            TableSchemas.DORIS_DWD_SOURCE_SCHEMA_WITH_WATERMARK
        );
        tableEnv.executeSql(dwdSourceDDL);
        
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
    
    // 注意: 原来的 createDWDSourceDDL 和 createDorisSinkDDL 方法已被工厂类替代
    // Environment 工厂类位置: com.crypto.dw.flink.factory.FlinkEnvironmentFactory
    // Table 工厂类位置: com.crypto.dw.flink.factory.FlinkTableFactory
    // Schema 定义位置: com.crypto.dw.flink.schema.TableSchemas
    
    /**
     * 创建窗口聚合 INSERT SQL
     * 
     * 架构修复说明:
     * - 修复前: 从 kafka_ticker_source 读取 (Kafka,错误)
     * - 修复后: 从 doris_dwd_source 读取 (Doris DWD 表,正确)
     * 
     * 注意: 
     * 1. 源表字段名匹配 Doris DWD 表字段名(下划线命名)
     * 2. 使用 DWD 表中已清洗的数据进行聚合
     * 3. 1分钟滚动窗口聚合,生成 K 线数据
     */
    private static String createInsertSQL() {
        return "INSERT INTO doris_dws_1min_sink\n" +
               "SELECT \n" +
               "    inst_id,\n" +
               "    -- 窗口时间戳（毫秒）\n" +
               "    UNIX_TIMESTAMP(CAST(window_start AS STRING)) * 1000 as window_start,\n" +
               "    UNIX_TIMESTAMP(CAST(window_end AS STRING)) * 1000 as window_end,\n" +
               "    1 as window_size,\n" +
               "    -- K 线数据：开高低收\n" +
               "    FIRST_VALUE(last_price) as open_price,\n" +
               "    MAX(last_price) as high_price,\n" +
               "    MIN(last_price) as low_price,\n" +
               "    LAST_VALUE(last_price) as close_price,\n" +
               "    -- 成交量（取最后一个值）\n" +
               "    LAST_VALUE(volume_24h) as volume,\n" +
               "    -- 平均价\n" +
               "    AVG(last_price) as avg_price,\n" +
               "    -- 涨跌额和涨跌幅\n" +
               "    LAST_VALUE(last_price) - FIRST_VALUE(last_price) as price_change,\n" +
               "    CASE \n" +
               "        WHEN FIRST_VALUE(last_price) > 0 \n" +
               "        THEN (LAST_VALUE(last_price) - FIRST_VALUE(last_price)) / FIRST_VALUE(last_price)\n" +
               "        ELSE 0\n" +
               "    END as price_change_rate,\n" +
               "    -- Tick 数量\n" +
               "    CAST(COUNT(*) AS INT) as tick_count,\n" +
               "    -- 处理时间\n" +
               "    UNIX_TIMESTAMP() * 1000 as process_time\n" +
               "FROM TABLE(\n" +
               "    TUMBLE(TABLE doris_dwd_source, DESCRIPTOR(event_time), INTERVAL '1' MINUTE)\n" +
               ")\n" +
               "WHERE last_price > 0\n" +
               "GROUP BY inst_id, window_start, window_end";
    }
}
