package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
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
        logger.info("Creating Doris DWS Source Table...");
        String dwsSourceDDL = tableFactory.createDorisSourceTable(
            "dws_source",
            "dws_crypto_ticker_1min",
            TableSchemas.DORIS_DWS_1MIN_SOURCE_SCHEMA_WITH_WATERMARK
        );
        tableEnv.executeSql(dwsSourceDDL);
        
        // 创建 Doris ADS Sink 表 (实时指标)
        logger.info("Creating Doris ADS Sink Table...");
        String adsSinkDDL = tableFactory.createDorisSinkTable(
            "ads_realtime_metrics_sink",
            "ads_crypto_ticker_realtime_metrics",
            TableSchemas.DORIS_ADS_REALTIME_METRICS_SINK_SCHEMA
        );
        tableEnv.executeSql(adsSinkDDL);
        
        // 执行 INSERT INTO 语句 (计算实时指标)
        String insertSQL = createInsertSQL();
        logger.info("Executing INSERT SQL...");
        
        logger.info("==========================================");
        logger.info("Starting Flink ADS Realtime Metrics Job...");
        logger.info("Web UI: http://localhost:8086");
        logger.info("==========================================");
        
        tableEnv.executeSql(insertSQL);
    }
    
    /**
     * 创建 INSERT INTO SQL
     * 
     * 从 DWS 层读取 1分钟 K 线数据,计算实时指标
     * 
     * 计算逻辑:
     * 1. 使用 LAG 函数获取历史价格
     * 2. 计算涨跌幅 = (当前价格 - 历史价格) / 历史价格 * 100
     * 3. 使用窗口函数计算成交量和波动率
     * 4. 使用 CASE WHEN 判断趋势
     */
    private static String createInsertSQL() {
        return "INSERT INTO ads_realtime_metrics_sink\n" +
               "SELECT \n" +
               "    inst_id,\n" +
               "    window_end AS metric_time,\n" +
               "    CAST(FROM_UNIXTIME(window_end / 1000, 'yyyy-MM-dd') AS DATE) AS metric_date,\n" +
               "    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(window_end / 1000))) AS INT) AS metric_hour,\n" +
               "    \n" +
               "    -- 价格指标 (使用 LAG 函数获取历史价格)\n" +
               "    close_price AS current_price,\n" +
               "    LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_1min_ago,\n" +
               "    LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_5min_ago,\n" +
               "    LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_15min_ago,\n" +
               "    LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) AS price_1hour_ago,\n" +
               "    \n" +
               "    -- 涨跌幅指标 (计算百分比变化)\n" +
               "    CAST(CASE \n" +
               "        WHEN LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL \n" +
               "             AND LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) > 0\n" +
               "        THEN (close_price - LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end)) \n" +
               "             / LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS change_rate_1min,\n" +
               "    \n" +
               "    CAST(CASE \n" +
               "        WHEN LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL \n" +
               "             AND LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) > 0\n" +
               "        THEN (close_price - LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end)) \n" +
               "             / LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS change_rate_5min,\n" +
               "    \n" +
               "    CAST(CASE \n" +
               "        WHEN LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL \n" +
               "             AND LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) > 0\n" +
               "        THEN (close_price - LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end)) \n" +
               "             / LAG(close_price, 15) OVER (PARTITION BY inst_id ORDER BY window_end) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS change_rate_15min,\n" +
               "    \n" +
               "    CAST(CASE \n" +
               "        WHEN LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) IS NOT NULL \n" +
               "             AND LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) > 0\n" +
               "        THEN (close_price - LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end)) \n" +
               "             / LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS change_rate_1hour,\n" +
               "    \n" +
               "    price_change_rate AS change_rate_24hour,\n" +
               "    \n" +
               "    -- 成交量指标 (使用窗口函数聚合)\n" +
               "    volume AS volume_1min,\n" +
               "    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS volume_5min,\n" +
               "    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) AS volume_15min,\n" +
               "    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) AS volume_1hour,\n" +
               "    SUM(volume) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 1439 PRECEDING AND CURRENT ROW) AS volume_24hour,\n" +
               "    \n" +
               "    -- 波动率指标 (使用标准差计算)\n" +
               "    CAST(CASE \n" +
               "        WHEN avg_price > 0 \n" +
               "        THEN (high_price - low_price) / avg_price * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS volatility_1min,\n" +
               "    \n" +
               "    CAST(CASE \n" +
               "        WHEN AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) > 0\n" +
               "        THEN (MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) - \n" +
               "              MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW)) / \n" +
               "             AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS volatility_5min,\n" +
               "    \n" +
               "    CAST(CASE \n" +
               "        WHEN AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) > 0\n" +
               "        THEN (MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) - \n" +
               "              MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW)) / \n" +
               "             AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 14 PRECEDING AND CURRENT ROW) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS volatility_15min,\n" +
               "    \n" +
               "    CAST(CASE \n" +
               "        WHEN AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) > 0\n" +
               "        THEN (MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) - \n" +
               "              MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW)) / \n" +
               "             AVG(avg_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) * 100\n" +
               "        ELSE 0\n" +
               "    END AS DECIMAL(10, 6)) AS volatility_1hour,\n" +
               "    \n" +
               "    -- 价格区间指标\n" +
               "    high_price AS high_1min,\n" +
               "    low_price AS low_1min,\n" +
               "    MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS high_5min,\n" +
               "    MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 4 PRECEDING AND CURRENT ROW) AS low_5min,\n" +
               "    MAX(high_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) AS high_1hour,\n" +
               "    MIN(low_price) OVER (PARTITION BY inst_id ORDER BY window_end ROWS BETWEEN 59 PRECEDING AND CURRENT ROW) AS low_1hour,\n" +
               "    \n" +
               "    -- 趋势指标 (判断价格趋势)\n" +
               "    CASE \n" +
               "        WHEN close_price > LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'UP'\n" +
               "        WHEN close_price < LAG(close_price, 1) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'DOWN'\n" +
               "        ELSE 'FLAT'\n" +
               "    END AS trend_1min,\n" +
               "    \n" +
               "    CASE \n" +
               "        WHEN close_price > LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'UP'\n" +
               "        WHEN close_price < LAG(close_price, 5) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'DOWN'\n" +
               "        ELSE 'FLAT'\n" +
               "    END AS trend_5min,\n" +
               "    \n" +
               "    CASE \n" +
               "        WHEN close_price > LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'UP'\n" +
               "        WHEN close_price < LAG(close_price, 60) OVER (PARTITION BY inst_id ORDER BY window_end) THEN 'DOWN'\n" +
               "        ELSE 'FLAT'\n" +
               "    END AS trend_1hour,\n" +
               "    \n" +
               "    -- 元数据\n" +
               "    UNIX_TIMESTAMP() * 1000 AS update_time\n" +
               "FROM dws_source";
    }
}
