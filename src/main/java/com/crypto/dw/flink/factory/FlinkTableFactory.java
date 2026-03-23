package com.crypto.dw.flink.factory;

import com.crypto.dw.config.ConfigLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink Table DDL 工厂类
 * 
 * 封装各个组件的连接配置,避免重复代码
 * 支持的组件:
 * - Kafka Source
 * - Doris Source
 * - Doris Sink
 * 
 * 使用示例:
 * <pre>
 * ConfigLoader config = ConfigLoader.getInstance();
 * FlinkTableFactory factory = new FlinkTableFactory(config);
 * 
 * // 创建 Kafka Source 表
 * String kafkaSourceDDL = factory.createKafkaSourceTable(
 *     "kafka_source",
 *     KafkaSourceSchema.TICKER_SCHEMA,
 *     true  // 带 Watermark
 * );
 * 
 * // 创建 Doris Source 表
 * String dorisSourceDDL = factory.createDorisSourceTable(
 *     "doris_ods_source",
 *     "ods",
 *     DorisSourceSchema.ODS_SCHEMA
 * );
 * 
 * // 创建 Doris Sink 表
 * String dorisSinkDDL = factory.createDorisSinkTable(
 *     "doris_dwd_sink",
 *     "dwd",
 *     DorisSinkSchema.DWD_SCHEMA
 * );
 * </pre>
 */
public class FlinkTableFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkTableFactory.class);
    
    private final ConfigLoader config;
    
    public FlinkTableFactory(ConfigLoader config) {
        this.config = config;
    }
    
    /**
     * 创建 Kafka Source 表 DDL
     * 
     * @param tableName 表名
     * @param schema 字段定义(包含字段名、类型、注释)
     * @param withWatermark 是否添加 Watermark(用于窗口聚合)
     * @return Kafka Source 表 DDL
     */
    public String createKafkaSourceTable(String tableName, String schema, boolean withWatermark,String groupId) {
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        String topic = config.getString("kafka.topic.crypto-ticker");
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest-offset");
        
        logger.info("创建 Kafka Source 表: {}", tableName);
        logger.info("  Bootstrap Servers: {}", bootstrapServers);
        logger.info("  Topic: {}", topic);
        logger.info("  Group ID: {}", groupId);
        logger.info("  Startup Mode: {}", startupMode);
        
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
        ddl.append(schema);
        
        // 添加 Watermark(如果需要)
        if (withWatermark) {
            ddl.append(",\n");
            ddl.append("    -- 定义事件时间和 Watermark\n");
            ddl.append("    event_time AS TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000)),\n");
            ddl.append("    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND\n");
        } else {
            ddl.append("\n");
        }
        
        ddl.append(") WITH (\n");
        ddl.append("    'connector' = 'kafka',\n");
        ddl.append("    'topic' = '").append(topic).append("',\n");
        ddl.append("    'properties.bootstrap.servers' = '").append(bootstrapServers).append("',\n");
        ddl.append("    'properties.group.id' = '").append(groupId).append("',\n");
        ddl.append("    'scan.startup.mode' = '").append(startupMode).append("',\n");
        ddl.append("    'format' = 'json',\n");
        ddl.append("    'json.fail-on-missing-field' = 'false',\n");
        ddl.append("    'json.ignore-parse-errors' = 'true'\n");
        ddl.append(")");
        
        return ddl.toString();
    }
    
    /**
     * 创建 Doris Source 表 DDL (使用 ArrowFlightSQL 高性能读取)
     * 
     * ArrowFlightSQL 读取方式说明:
     * - Doris 2.1+ 支持,3.0+ 推荐使用
     * - 性能提升 2-10 倍 (相比 Thrift)
     * - 内存占用更少,支持更大数据量
     * - 异步反序列化,提高吞吐量
     * - 不支持 CDC 增量读取,只能读取快照数据
     * 
     * @param tableName Flink 表名
     * @param tableType Doris 表类型(ods/dwd/dws-1min)
     * @param schema 字段定义
     * @return Doris Source 表 DDL
     */
    public String createDorisSourceTable(String tableName, String tableType, String schema) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String database = config.getString("doris.database");
        String dorisTable = getDorisTableName(tableType);
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        
        // 读取字段配置
        String readFields = config.getString("doris.source.read-fields", "*");
        
        logger.info("创建 Doris Source 表 (ArrowFlightSQL): {}", tableName);
        logger.info("  FE Nodes: {}", feNodes);
        logger.info("  Database: {}", database);
        logger.info("  Table: {}", dorisTable);
        logger.info("  Read Fields: {}", readFields);
        logger.info("  Read Mode: ArrowFlightSQL (高性能模式)");
        
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
        ddl.append(schema);
        ddl.append("\n) WITH (\n");
        ddl.append("    'connector' = 'doris',\n");
        ddl.append("    'fenodes' = '").append(feNodes).append("',\n");
        ddl.append("    'table.identifier' = '").append(database).append(".").append(dorisTable).append("',\n");
        ddl.append("    'username' = '").append(username).append("',\n");
        ddl.append("    'password' = '").append(password).append("',\n");
        // ⭐ ArrowFlightSQL 配置 - 高性能读取模式
        ddl.append("    'doris.deserialize.arrow.async' = 'true',\n");  // 异步反序列化
        ddl.append("    'doris.deserialize.queue.size' = '64',\n");     // 反序列化队列大小
        ddl.append("    'doris.request.query.timeout.s' = '3600',\n");  // 查询超时时间(1小时)
        ddl.append("    'doris.read.field' = '").append(readFields).append("'\n");  // 指定读取字段
        ddl.append(")");
        
        return ddl.toString();
    }
    
    /**
     * 创建 Doris Sink 表 DDL
     * 
     * @param tableName Flink 表名
     * @param tableType Doris 表类型(ods/dwd/dws-1min)
     * @param schema 字段定义
     * @return Doris Sink 表 DDL
     */
    public String createDorisSinkTable(String tableName, String tableType, String schema) {
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String beNodes = config.getString("doris.be.nodes", "127.0.0.1:8040");
        String database = config.getString("doris.database");
        String dorisTable = getDorisTableName(tableType);
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        
        logger.info("创建 Doris Sink 表: {}", tableName);
        logger.info("  FE Nodes: {}", feNodes);
        logger.info("  BE Nodes: {}", beNodes);
        logger.info("  Database: {}", database);
        logger.info("  Table: {}", dorisTable);
        
        StringBuilder ddl = new StringBuilder();
        ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
        ddl.append(schema);
        ddl.append("\n) WITH (\n");
        ddl.append("    'connector' = 'doris',\n");
        ddl.append("    'fenodes' = '").append(feNodes).append("',\n");
        ddl.append("    'benodes' = '").append(beNodes).append("',\n");
        ddl.append("    'table.identifier' = '").append(database).append(".").append(dorisTable).append("',\n");
        ddl.append("    'username' = '").append(username).append("',\n");
        ddl.append("    'password' = '").append(password).append("',\n");
        ddl.append("    'sink.label-prefix' = '").append(tableType).append("_").append(System.currentTimeMillis()).append("',\n");
        ddl.append("    'sink.properties.format' = 'json',\n");
        ddl.append("    'sink.properties.read_json_by_line' = 'true',\n");  // 修复：添加逗号
        ddl.append("    'sink.enable-2pc' = 'true'\n");  // ⭐ 启用两阶段提交（精准一次性必需）
        ddl.append(")");
        
        return ddl.toString();
    }
    
    /**
     * 根据表类型获取 Doris 表名
     */
    private String getDorisTableName(String tableType) {
        switch (tableType.toLowerCase()) {
            case "ods":
                return config.getString("doris.tables.ods", "ods_crypto_ticker_rt");
            case "dwd":
                return config.getString("doris.tables.dwd", "dwd_crypto_ticker_detail");
            case "dws-1min":
                return config.getString("doris.tables.dws-1min", "dws_crypto_ticker_1min");
            default:
                throw new IllegalArgumentException("Unknown table type: " + tableType);
        }
    }
}
