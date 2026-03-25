package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
import com.crypto.dw.utils.SqlFileLoader;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink ADS 实时指标作业
 * 
 * 从 DWS 层读取 1分钟 K 线数据,计算实时指标并写入 ADS 层
 * 
 * 功能:
 * - 计算多时间窗口的价格变化(1分钟/5分钟/15分钟/1小时)
 * - 计算涨跌幅指标
 * - 计算成交量指标
 * - 计算波动率指标
 * - 判断价格趋势
 * 
 * 数据流:
 * Doris DWS (1分钟 K 线) → Flink 计算 → Doris ADS (实时指标)
 * 
 * 使用方法:
 * <pre>
 * # 本地运行
 * mvn clean compile
 * bash run-flink-ads-realtime-metrics.sh
 * </pre>
 * 
 * @author Gang
 * @date 2026-03-23
 */
public class FlinkADSRealtimeMetricsJob {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkADSRealtimeMetricsJob.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ADS Realtime Metrics Job");
        logger.info("==========================================");
        
        // 从程序参数中读取 APP_ENV（支持 StreamPark Remote 模式）
        for (int i = 0; i < args.length - 1; i++) {
            if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
                String envFromArgs = args[i + 1];
                logger.info("Found APP_ENV in program arguments: " + envFromArgs);
                System.setProperty("APP_ENV", envFromArgs);
                break;
            }
        }
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        logger.info("Configuration loaded successfully");
        
        // 使用工厂类创建 Flink Table Environment (减少重复代码)
        // 注意: 使用端口 8086 避免与其他作业冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-ads-realtime-metrics-job", 8086);
        
        // 使用工厂类创建表 (减少重复代码)
        FlinkTableFactory tableFactory = new FlinkTableFactory(config);
        
        // 创建 Doris DWS Source 表 (1分钟 K 线)
        // 重构说明: 使用显式参数传递，不再通过表类型隐式推断
        logger.info("Creating Doris DWS Source Table...");
        String database = config.getString("doris.database");
        String dwsTable = config.getString("doris.tables.dws-1min");
        String dwsSourceDDL = tableFactory.createDorisSourceTable(
            "dws_source",
            database,
            dwsTable,
            "*",  // 读取所有字段
            TableSchemas.DORIS_DWS_1MIN_SOURCE_SCHEMA_WITH_WATERMARK
        );
        tableEnv.executeSql(dwsSourceDDL);
        
        // 创建 Doris ADS Sink 表 (实时指标)
        // 重构说明: 使用显式参数传递，不再通过表类型隐式推断
        logger.info("Creating Doris ADS Sink Table...");
        String adsTable = config.getString("doris.tables.ads");
        String adsSinkDDL = tableFactory.createDorisSinkTable(
            "ads_realtime_metrics_sink",
            database,
            adsTable,
            "ads_metrics_" + System.currentTimeMillis(),  // Label 前缀
            TableSchemas.DORIS_ADS_REALTIME_METRICS_SINK_SCHEMA
        );
        tableEnv.executeSql(adsSinkDDL);
        
        // 执行 INSERT INTO 语句（从外部 SQL 文件加载）
        // 重构说明: SQL 语句已分离到独立文件，便于维护和版本控制
        logger.info("Loading INSERT SQL from file...");
        String insertSQL = SqlFileLoader.loadSql("sql/flink/ads_realtime_metrics_insert.sql");
        logger.info("Executing INSERT SQL...");
        
        logger.info("==========================================");
        logger.info("Starting Flink ADS Realtime Metrics Job...");
        logger.info("Web UI: http://localhost:8086");
        logger.info("==========================================");
        
        tableEnv.executeSql(insertSQL);
    }
}
