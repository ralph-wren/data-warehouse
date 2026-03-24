package com.crypto.dw.flink.factory;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.utils.SqlFileLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Flink Table DDL 工厂类
 * 
 * 封装各个组件的连接配置,避免重复代码
 * 支持的组件:
 * - Kafka Source
 * - Doris Source
 * - Doris Sink
 * 
 * 优化说明：
 * 1. 所有 DDL 使用 SQL 模板文件，便于维护和修改
 * 2. 支持两种方式创建 Doris 表：
 *    - 方式 1: 使用表类型（从配置文件读取库名和表名）
 *    - 方式 2: 显式传入库名和表名（更灵活）
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
 *     true,  // 带 Watermark
 *     "my-consumer-group"
 * );
 * 
 * // 方式 1: 使用表类型创建 Doris Source 表（从配置文件读取）
 * String dorisSourceDDL = factory.createDorisSourceTable(
 *     "doris_ods_source",
 *     "ods",  // 表类型
 *     DorisSourceSchema.ODS_SCHEMA
 * );
 * 
 * // 方式 2: 显式传入库名和表名创建 Doris Source 表（更灵活）
 * String dorisSourceDDL = factory.createDorisSourceTable(
 *     "doris_test_source",
 *     "test_db",      // 数据库名
 *     "test_table",   // 表名
 *     "*",            // 读取字段
 *     DorisSourceSchema.TEST_SCHEMA
 * );
 * 
 * // 方式 1: 使用表类型创建 Doris Sink 表（从配置文件读取）
 * String dorisSinkDDL = factory.createDorisSinkTable(
 *     "doris_dwd_sink",
 *     "dwd",  // 表类型
 *     DorisSinkSchema.DWD_SCHEMA
 * );
 * 
 * // 方式 2: 显式传入库名和表名创建 Doris Sink 表（更灵活）
 * String dorisSinkDDL = factory.createDorisSinkTable(
 *     "doris_test_sink",
 *     "test_db",      // 数据库名
 *     "test_table",   // 表名
 *     "test",         // Label 前缀
 *     DorisSinkSchema.TEST_SCHEMA
 * );
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
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
     * 优化说明：使用 SQL 模板文件，便于维护和修改
     * 
     * @param tableName 表名
     * @param schema 字段定义(包含字段名、类型、注释)
     * @param withWatermark 是否添加 Watermark(用于窗口聚合)
     * @param groupId Consumer Group ID
     * @return Kafka Source 表 DDL
     */
    public String createKafkaSourceTable(String tableName, String schema, boolean withWatermark, String groupId) {
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        String topic = config.getString("kafka.topic.crypto-ticker-spot");
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        // 转换 startup mode 为 Flink Table API 期望的格式
        // 配置文件使用简写: earliest/latest/committed
        // Flink Table API 需要完整枚举值: earliest-offset/latest-offset/group-offsets
        String flinkStartupMode = convertStartupMode(startupMode);
        
        logger.info("创建 Kafka Source 表: {}", tableName);
        logger.info("  Bootstrap Servers: {}", bootstrapServers);
        logger.info("  Topic: {}", topic);
        logger.info("  Group ID: {}", groupId);
        logger.info("  Startup Mode: {} -> {}", startupMode, flinkStartupMode);
        
        // 构建 Watermark 定义
        String watermark = "";
        if (withWatermark) {
            watermark = ",\n" +
                    "    event_time AS TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000)),\n" +
                    "    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND\n";
        } else {
            watermark = "\n";
        }
        
        // 准备参数
        Map<String, String> params = new HashMap<>();
        params.put("tableName", tableName);
        params.put("schema", schema);
        params.put("watermark", watermark);
        params.put("bootstrapServers", bootstrapServers);
        params.put("topic", topic);
        params.put("groupId", groupId);
        params.put("startupMode", flinkStartupMode);  // 使用转换后的值
        
        // 从 SQL 模板文件加载并替换参数
        return SqlFileLoader.loadSqlWithParams("sql/flink/ddl/kafka_source.sql", params);
    }
    
    /**
     * 转换 startup mode 为 Flink Table API 期望的格式
     * 
     * 配置文件使用简写形式,便于理解和配置:
     * - earliest: 从最早数据开始
     * - latest: 从最新数据开始
     * - committed: 从上次提交位置开始
     * 
     * Flink Table API 需要完整的枚举值:
     * - earliest-offset: 从最早数据开始
     * - latest-offset: 从最新数据开始
     * - group-offsets: 从上次提交位置开始
     * 
     * @param startupMode 配置文件中的 startup mode
     * @return Flink Table API 期望的 startup mode
     */
    private String convertStartupMode(String startupMode) {
        switch (startupMode.toLowerCase()) {
            case "earliest":
                return "earliest-offset";
            case "latest":
                return "latest-offset";
            case "committed":
            case "group-offsets":
                return "group-offsets";
            case "earliest-offset":
            case "latest-offset":
                // 如果已经是完整格式,直接返回
                return startupMode;
            default:
                logger.warn("未知的 startup mode: {}, 使用默认值 latest-offset", startupMode);
                return "latest-offset";
        }
    }
    
    /**
     * 创建 Doris Source 表 DDL - 显式传入库名和表名（推荐使用）
     * 
     * 优化说明：
     * 1. 使用 SQL 模板文件，便于维护和修改
     * 2. 显式传入库名和表名，提高代码可读性
     * 3. 可以读取任意数据库和表，不受配置文件限制
     * 4. 使用 ArrowFlightSQL 高性能读取模式
     * 
     * ArrowFlightSQL 读取方式说明:
     * - Doris 2.1+ 支持,3.0+ 推荐使用
     * - 性能提升 2-10 倍 (相比 Thrift)
     * - 内存占用更少,支持更大数据量
     * - 异步反序列化,提高吞吐量
     * - 不支持 CDC 增量读取,只能读取快照数据
     * 
     * 使用场景：
     * - 读取测试数据库
     * - 读取临时表
     * - 读取其他项目的表
     * - 动态指定源表
     * 
     * 使用示例：
     * <pre>
     * // 读取 ODS 表
     * String database = config.getString("doris.database", "crypto_dw");
     * String odsTable = config.getString("doris.tables.ods", "ods_crypto_ticker_rt");
     * String ddl = factory.createDorisSourceTable(
     *     "ods_source",          // Flink 表名
     *     database,              // 数据库名
     *     odsTable,              // 表名
     *     "*",                   // 读取字段（* 表示所有字段）
     *     schema                 // 字段定义
     * );
     * 
     * // 读取指定字段
     * String ddl = factory.createDorisSourceTable(
     *     "ods_source",
     *     "crypto_dw",
     *     "ods_crypto_ticker_rt",
     *     "symbol,price,timestamp",  // 只读取这些字段
     *     schema
     * );
     * </pre>
     * 
     * @param tableName Flink 表名
     * @param database Doris 数据库名
     * @param dorisTable Doris 表名
     * @param readFields 读取字段列表（* 表示所有字段，或逗号分隔的字段名）
     * @param schema 字段定义
     * @return Doris Source 表 DDL
     */
    public String createDorisSourceTable(String tableName, String database, String dorisTable, 
                                         String readFields, String schema) {
        // 读取 Doris 连接配置
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        
        logger.info("创建 Doris Source 表 (ArrowFlightSQL): {}", tableName);
        logger.info("  FE Nodes: {}", feNodes);
        logger.info("  Database: {}", database);
        logger.info("  Table: {}", dorisTable);
        logger.info("  Read Fields: {}", readFields);
        logger.info("  Read Mode: ArrowFlightSQL (高性能模式)");
        
        // 准备参数
        Map<String, String> params = new HashMap<>();
        params.put("tableName", tableName);
        params.put("schema", schema);
        params.put("feNodes", feNodes);
        params.put("tableIdentifier", database + "." + dorisTable);
        params.put("username", username);
        params.put("password", password);
        params.put("readFields", readFields);
        
        // 从 SQL 模板文件加载并替换参数
        return SqlFileLoader.loadSqlWithParams("sql/flink/ddl/doris_source.sql", params);
    }
    
    /**
     * 创建 Doris Sink 表 DDL - 显式传入库名和表名（推荐使用）
     * 
     * 优化说明：
     * 1. 使用 SQL 模板文件，便于维护和修改
     * 2. 支持显式传入库名和表名，提高灵活性
     * 3. 可以写入到任意数据库和表，不受配置文件限制
     * 4. 根据集群模式自动选择 BE 地址（local/remote）
     * 
     * BE 地址自动选择说明：
     * - local 模式：使用 doris.be.nodes.local (宿主机地址 127.0.0.1:8040)
     * - remote 模式：使用 doris.be.nodes.remote (容器名称 doris-be:8040)
     * - 代码会根据 flink.cluster.mode 自动选择正确的地址
     * 
     * 使用场景：
     * - 写入到测试数据库
     * - 写入到临时表
     * - 写入到其他项目的表
     * - 动态指定目标表
     * 
     * 使用示例：
     * <pre>
     * // 写入到测试数据库
     * String ddl = factory.createDorisSinkTable(
     *     "test_sink",           // Flink 表名
     *     "test_db",             // 数据库名
     *     "test_table",          // 表名
     *     "test",                // Label 前缀
     *     schema                 // 字段定义
     * );
     * 
     * // 写入到临时表
     * String ddl = factory.createDorisSinkTable(
     *     "temp_sink",
     *     "crypto_dw",
     *     "temp_ticker_" + System.currentTimeMillis(),
     *     "temp",
     *     schema
     * );
     * </pre>
     * 
     * @param tableName Flink 表名
     * @param database Doris 数据库名
     * @param dorisTable Doris 表名
     * @param labelPrefix Label 前缀（用于去重）
     * @param schema 字段定义
     * @return Doris Sink 表 DDL
     */
    public String createDorisSinkTable(String tableName, String database, String dorisTable, 
                                       String labelPrefix, String schema) {
        // 读取 Doris 连接配置
        String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
        String username = config.getString("doris.fe.username");
        String password = config.getString("doris.fe.password", "");
        
        // 根据集群模式自动选择 BE 地址
        String clusterMode = config.getString("flink.cluster.mode", "local");
        String beNodes;
        
        if ("remote".equalsIgnoreCase(clusterMode)) {
            // 远程集群模式：使用容器名称
            beNodes = config.getString("doris.be.nodes.remote", "doris-be:8040");
            logger.info("远程集群模式：使用容器名称访问 BE: {}", beNodes);
        } else {
            // 本地模式：使用宿主机地址
            beNodes = config.getString("doris.be.nodes.local", "127.0.0.1:8040");
            logger.info("本地模式：使用宿主机地址访问 BE: {}", beNodes);
        }
        
        logger.info("创建 Doris Sink 表: {}", tableName);
        logger.info("  集群模式: {}", clusterMode);
        logger.info("  FE Nodes: {}", feNodes);
        logger.info("  BE Nodes: {}", beNodes);
        logger.info("  Database: {}", database);
        logger.info("  Table: {}", dorisTable);
        logger.info("  Label Prefix: {}", labelPrefix);
        
        // 准备参数
        Map<String, String> params = new HashMap<>();
        params.put("tableName", tableName);
        params.put("schema", schema);
        params.put("feNodes", feNodes);
        params.put("beNodes", beNodes);
        params.put("tableIdentifier", database + "." + dorisTable);
        params.put("username", username);
        params.put("password", password);
        params.put("labelPrefix", labelPrefix);
        
        // 从 SQL 模板文件加载并替换参数
        return SqlFileLoader.loadSqlWithParams("sql/flink/ddl/doris_sink.sql", params);
    }
}
