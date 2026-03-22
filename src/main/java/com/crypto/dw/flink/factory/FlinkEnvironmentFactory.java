package com.crypto.dw.flink.factory;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink Environment 工厂类
 * 
 * 封装 Flink 执行环境的创建逻辑,避免重复代码
 * 支持的功能:
 * - DataStream API 环境
 * - Table API 环境
 * - Web UI 配置
 * - Prometheus Metrics 配置
 * - Checkpoint 配置
 * - 并行度配置
 * 
 * 使用示例:
 * <pre>
 * ConfigLoader config = ConfigLoader.getInstance();
 * FlinkEnvironmentFactory factory = new FlinkEnvironmentFactory(config);
 * 
 * // 创建 DataStream 环境
 * StreamExecutionEnvironment env = factory.createStreamEnvironment("flink-ods-job", 8081);
 * 
 * // 创建 Table 环境
 * StreamTableEnvironment tableEnv = factory.createTableEnvironment("flink-dwd-job", 8082);
 * </pre>
 */
public class FlinkEnvironmentFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkEnvironmentFactory.class);
    
    private final ConfigLoader config;
    
    public FlinkEnvironmentFactory(ConfigLoader config) {
        this.config = config;
    }
    
    /**
     * 创建 StreamExecutionEnvironment (DataStream API)
     * 
     * @param jobName 作业名称(用于 Metrics 标识)
     * @param webUIPort Web UI 端口(避免端口冲突)
     * @return 配置好的 StreamExecutionEnvironment
     */
    public StreamExecutionEnvironment createStreamEnvironment(String jobName, int webUIPort) {
        logger.info("==========================================");
        logger.info("创建 Flink Stream Environment");
        logger.info("作业名称: {}", jobName);
        logger.info("==========================================");
        
        // 创建 Flink 配置
        Configuration flinkConfig = createFlinkConfiguration(jobName, webUIPort);
        
        // 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        
        // 配置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        // 配置 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 30000);
        env.enableCheckpointing(checkpointInterval);
        
        // 打印环境信息
        logEnvironmentInfo(parallelism, checkpointInterval, webUIPort);
        
        return env;
    }
    
    /**
     * 创建 StreamTableEnvironment (Table API / SQL)
     * 
     * @param jobName 作业名称(用于 Metrics 标识)
     * @param webUIPort Web UI 端口(避免端口冲突)
     * @return 配置好的 StreamTableEnvironment
     */
    public StreamTableEnvironment createTableEnvironment(String jobName, int webUIPort) {
        logger.info("==========================================");
        logger.info("创建 Flink Table Environment");
        logger.info("作业名称: {}", jobName);
        logger.info("==========================================");
        
        // 创建 Flink 配置
        Configuration flinkConfig = createFlinkConfiguration(jobName, webUIPort);
        
        // 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        
        // 配置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        // 配置 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
        env.enableCheckpointing(checkpointInterval);
        
        // 创建 Table Environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        
        // 设置时区为中国时区(东八区) - 重要:确保日期计算正确
        tableEnv.getConfig().setLocalTimeZone(java.time.ZoneId.of("Asia/Shanghai"));
        
        // 打印环境信息
        logEnvironmentInfo(parallelism, checkpointInterval, webUIPort);
        
        return tableEnv;
    }
    
    /**
     * 创建 Flink Configuration
     * 
     * 配置内容:
     * - Web UI (端口、地址)
     * - Prometheus Metrics (Pushgateway)
     * - 通用 Metrics 选项
     * 
     * @param jobName 作业名称
     * @param webUIPort Web UI 端口
     * @return Flink Configuration
     */
    private Configuration createFlinkConfiguration(String jobName, int webUIPort) {
        Configuration flinkConfig = new Configuration();
        
        // 配置 Web UI
        configureWebUI(flinkConfig, webUIPort);
        
        // 配置 Prometheus Metrics
        configureMetrics(flinkConfig, jobName);
        
        return flinkConfig;
    }
    
    /**
     * 配置 Web UI
     * 
     * @param flinkConfig Flink 配置
     * @param webUIPort Web UI 端口
     */
    private void configureWebUI(Configuration flinkConfig, int webUIPort) {
        // 启用 Web UI
        flinkConfig.setBoolean("web.submit.enable", 
            config.getBoolean("flink.web.submit.enable", true));
        flinkConfig.setBoolean("web.cancel.enable", 
            config.getBoolean("flink.web.cancel.enable", true));
        
        // 设置端口
        flinkConfig.setInteger("rest.port", webUIPort);
        flinkConfig.setString("rest.address", 
            config.getString("flink.web.address", "0.0.0.0"));
        
        // 端口范围(字符串类型)
        flinkConfig.setString("rest.bind-port", webUIPort + "-" + (webUIPort + 10));
        
        // 启用 Flamegraph
        flinkConfig.setBoolean("rest.flamegraph.enabled", true);
        
        logger.info("Web UI 配置:");
        logger.info("  端口: {}", webUIPort);
        logger.info("  地址: http://localhost:{}", webUIPort);
    }
    
    /**
     * 配置 Prometheus Metrics
     * 
     * @param flinkConfig Flink 配置
     * @param jobName 作业名称
     */
    private void configureMetrics(Configuration flinkConfig, String jobName) {
        // 从配置文件读取 Pushgateway 地址,支持本地和 Docker 环境
        String pushgatewayHost = config.getString("application.metrics.pushgateway.host", "localhost");
        int pushgatewayPort = config.getInt("application.metrics.pushgateway.port", 9091);
        
        // 配置 Pushgateway Reporter
        MetricsConfig.configurePushgatewayReporter(
            flinkConfig,
            pushgatewayHost,
            pushgatewayPort,
            jobName
        );
        
        // 配置通用 Metrics 选项
        MetricsConfig.configureCommonMetrics(flinkConfig);
        
        logger.info("Metrics 配置:");
        logger.info("  Pushgateway: {}:{}", pushgatewayHost, pushgatewayPort);
        logger.info("  作业名称: {}", jobName);
    }
    
    /**
     * 打印环境信息
     * 
     * @param parallelism 并行度
     * @param checkpointInterval Checkpoint 间隔
     * @param webUIPort Web UI 端口
     */
    private void logEnvironmentInfo(int parallelism, long checkpointInterval, int webUIPort) {
        logger.info("==========================================");
        logger.info("Flink Environment 配置:");
        logger.info("  并行度: {}", parallelism);
        logger.info("  Checkpoint 间隔: {} ms", checkpointInterval);
        logger.info("  Web UI: http://localhost:{}", webUIPort);
        logger.info("  时区: Asia/Shanghai (东八区)");
        logger.info("==========================================");
    }
}
