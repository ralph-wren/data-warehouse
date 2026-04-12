package com.crypto.dw.factory;

import com.crypto.dw.config.ConfigLoader;
import org.apache.doris.flink.cfg.DorisExecutionOptions;
import org.apache.doris.flink.cfg.DorisOptions;
import org.apache.doris.flink.cfg.DorisReadOptions;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.doris.flink.sink.writer.serializer.SimpleStringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Doris Sink 工厂类
 * 
 * 封装 Doris Sink 的创建逻辑，避免重复代码
 * 统一管理 Doris 连接配置（账号、密码、URL 等）
 * 
 * 功能:
 * - 创建 DataStream API 的 DorisSink
 * - 统一配置管理（从 ConfigLoader 读取）
 * - 支持精准一次性语义（两阶段提交）
 * - 批量写入优化
 * - 支持手动指定库名和表名（更灵活）
 * 
 * 使用示例:
 * <pre>
 * ConfigLoader config = ConfigLoader.getInstance();
 * DorisSinkFactory factory = new DorisSinkFactory(config);
 * 
 * // 方式 1: 使用表类型（从配置文件读取库名和表名）
 * String database = config.getString("doris.database", "crypto_dw");
 * String odsTable = config.getString("doris.tables.ods", "ods_crypto_ticker_rt");
 * DorisSink<String> odsSink = factory.createDorisSink(database, odsTable, "ods");
 * 
 * String dwdTable = config.getString("doris.tables.dwd", "dwd_crypto_ticker_detail");
 * DorisSink<String> dwdSink = factory.createDorisSink(database, dwdTable, "dwd");
 * 
 * // 方式 2: 手动指定库名和表名（更灵活）
 * DorisSink<String> customSink = factory.createDorisSink(
 *     "my_database",           // 数据库名
 *     "my_table",              // 表名
 *     "my-label-prefix"        // Label 前缀
 * );
 * 
 * // 方式 3: 写入到其他数据库
 * DorisSink<String> testSink = factory.createDorisSink(
 *     "test_db",               // 测试数据库
 *     "test_table",            // 测试表
 *     "test"                   // Label 前缀
 * );
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class DorisSinkFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(DorisSinkFactory.class);
    
    private final ConfigLoader config;
    
    public DorisSinkFactory(ConfigLoader config) {
        this.config = config;
    }
    
    /**
     * 创建 Doris Sink (DataStream API) - 手动指定库名和表名
     * 
     * 说明：
     * 1. 统一使用三参数方法，提高灵活性
     * 2. 支持写入不同的数据库和表
     * 3. Label 前缀用于 Stream Load 去重
     * 
     * 使用示例：
     * <pre>
     * // ODS 表
     * String database = config.getString("doris.database", "crypto_dw");
     * String odsTable = config.getString("doris.tables.ods", "ods_crypto_ticker_rt");
     * DorisSink<String> odsSink = factory.createDorisSink(database, odsTable, "ods");
     * 
     * // DWD 表
     * String dwdTable = config.getString("doris.tables.dwd", "dwd_crypto_ticker_detail");
     * DorisSink<String> dwdSink = factory.createDorisSink(database, dwdTable, "dwd");
     * 
     * // 自定义表
     * DorisSink<String> customSink = factory.createDorisSink("test_db", "test_table", "test");
     * </pre>
     * 
     * @param database 数据库名称
     * @param table 表名
     * @param labelPrefix Label 前缀（用于去重）
     * @return 配置好的 DorisSink
     */
    public DorisSink<String> createDorisSink(String database, String table, String labelPrefix) {
        // 读取 Doris 连接配置
        String feHttpUrl = config.getString("doris.fe.http-url");
        String feNodes = feHttpUrl.replace("http://", "").replace("https://", "");
        String tableIdentifier = database + "." + table;
        String username = config.getString("doris.fe.username", "root");
        String password = config.getString("doris.fe.password", "");
        
        logger.info("==========================================");
        logger.info("创建 Doris Sink (DataStream API)");
        logger.info("==========================================");
        logger.info("Doris Sink 配置:");
        logger.info("  FE Nodes: {}", feNodes);
        logger.info("  Database: {}", database);
        logger.info("  Table: {}", table);
        logger.info("  Table Identifier: {}", tableIdentifier);
        logger.info("  Username: {}", username);
        logger.info("  Label Prefix: {}", labelPrefix);
        
        // Doris 连接配置
        DorisOptions.Builder dorisBuilder = DorisOptions.builder()
            .setFenodes(feNodes)  // FE 地址，格式: host:port
            .setTableIdentifier(tableIdentifier)  // 数据库.表名
            .setUsername(username)
            .setPassword(password);
        
        // 关键修复: 如果 BE 使用 Docker 内部 IP，直接指定 BE 地址
        // 这样可以绕过 FE 返回的内部 IP
        String beNodes = config.getString("doris.be.nodes", "");
        if (!beNodes.isEmpty()) {
            dorisBuilder.setBenodes(beNodes);
            logger.info("  BE Nodes: {}", beNodes);
        }
        
        // Stream Load 执行配置
        Properties streamLoadProp = new Properties();
        streamLoadProp.setProperty("format", "json");  // 数据格式
        streamLoadProp.setProperty("read_json_by_line", "true");  // 按行读取 JSON
        streamLoadProp.setProperty("strip_outer_array", "false");  // 不剥离外层数组
        
        // 批量写入配置（从配置文件读取）
        int batchSize = config.getInt("doris.stream-load.batch-size", 1000);  // 批量行数
        int batchIntervalMs = config.getInt("doris.stream-load.batch-interval-ms", 5000);  // 批量间隔（毫秒）
        int maxRetries = config.getInt("doris.stream-load.max-retries", 3);  // 最大重试次数
        
        logger.info("批量写入配置:");
        logger.info("  Batch Size: {} 行", batchSize);
        logger.info("  Batch Interval: {} ms", batchIntervalMs);
        logger.info("  Max Retries: {}", maxRetries);
        
        DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
            .setStreamLoadProp(streamLoadProp)  // Stream Load 属性
            .setMaxRetries(maxRetries)  // 最大重试次数
            // 缓冲区配置
            .setBufferSize(10 * 1024 * 1024)  // 缓冲区大小：10MB
            .setBufferCount(3)  // 缓冲区数量
            // 批量写入配置
            .setBufferFlushMaxRows(batchSize)  // 批量行数
            .setBufferFlushIntervalMs(batchIntervalMs)  // 批量间隔
            // Label 配置（使用时间戳作为前缀，避免重复）
            .setLabelPrefix(labelPrefix + "-" + System.currentTimeMillis())
            .build();
        
        logger.info("==========================================");
        
        // 构建 DorisSink
        return DorisSink.<String>builder()
            .setDorisReadOptions(DorisReadOptions.builder().build())
            .setDorisExecutionOptions(executionOptions)
            .setDorisOptions(dorisBuilder.build())
            .setSerializer(new SimpleStringSerializer())  // JSON 字符串序列化器
            .build();
    }
}
