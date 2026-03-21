package com.crypto.dw.flink;

import com.alibaba.fastjson2.JSONObject;
import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import com.crypto.dw.flink.watermark.WatermarkStrategyFactory;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.doris.flink.cfg.DorisExecutionOptions;
import org.apache.doris.flink.cfg.DorisOptions;
import org.apache.doris.flink.cfg.DorisReadOptions;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.doris.flink.sink.writer.serializer.SimpleStringSerializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;

/**
 * Flink ODS 作业 - 使用官方 Doris Connector
 * 从 Kafka 消费数据并写入 Doris ODS 层
 * 
 * 更新说明:
 * 1. 使用官方 DorisSink 替代自定义 HTTP Stream Load
 * 2. 兼容 Doris 3.1.x 版本
 * 3. 支持自动重试和错误处理
 */ 
@Slf4j
public class FlinkODSJobDataStream {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkODSJobDataStream.class);
    
    public static void main(String[] args) throws Exception {
        log.info("==========================================");
        log.info("Flink ODS Job (官方 Doris Connector)");
        log.info("==========================================");

        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();

        // 创建 Flink 执行环境（启用 Web UI 和 Metrics）
        Configuration flinkConfig = new Configuration();

        // 启用 Web UI（注意：端口参数必须是 int 类型）
        flinkConfig.setBoolean("web.submit.enable", config.getBoolean("flink.web.submit.enable", true));
        flinkConfig.setBoolean("web.cancel.enable", config.getBoolean("flink.web.cancel.enable", true));
        flinkConfig.setInteger("rest.port", config.getInt("flink.web.port", 8081));  // Web UI 端口
        flinkConfig.setString("rest.address", config.getString("flink.web.address", "0.0.0.0"));  // 监听地址
        flinkConfig.setString("rest.bind-port", "8081-8090");  // 端口范围（字符串类型）
        flinkConfig.setBoolean("rest.flamegraph.enabled",true);
        
        // 配置 Prometheus Metrics（推送到 Pushgateway）
        // 注意：从配置文件读取 Pushgateway 地址，支持本地和 Docker 环境
        String pushgatewayHost = config.getString("application.metrics.pushgateway.host", "localhost");
        int pushgatewayPort = config.getInt("application.metrics.pushgateway.port", 9091);
        
        MetricsConfig.configurePushgatewayReporter(
            flinkConfig,
            pushgatewayHost,  // 从配置文件读取
            pushgatewayPort,  // 从配置文件读取
            "flink-ods-job"  // 作业名称
        );
        
        // 配置通用 Metrics 选项
        MetricsConfig.configureCommonMetrics(flinkConfig);
        
        // 创建 Flink 执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        // 设置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        // 启用 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 30000);
        env.enableCheckpointing(checkpointInterval);
        
        log.info("Flink Environment:");
        log.info("  Parallelism: " + parallelism);
        log.info("  Checkpoint Interval: " + checkpointInterval + " ms");
        
        
        // 配置 Kafka Source
        KafkaSource<String> kafkaSource = createKafkaSource(config);
        
        // 创建数据流
        DataStream<String> rawStream = env.fromSource(
            kafkaSource,
                WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5),
            "Kafka Source"
        );
        
        log.info("Kafka Source created:");
        log.info("  Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        log.info("  Topic: " + config.getString("kafka.topic.crypto-ticker"));
        log.info("  Group ID: " + config.getString("kafka.consumer.group-id"));
        log.info("  Startup Mode: " + config.getString("kafka.consumer.startup-mode", "earliest"));
        
        
        // 数据转换：JSON -> Doris 格式
        DataStream<String> odsStream = rawStream
            .map(new ODSTransformFunction()).disableChaining()
            .name("ODS Transform");
        
        // 配置官方 Doris Sink
        DorisSink<String> dorisSink = createDorisSink(config);
        
        // 写入 Doris
        odsStream.sinkTo(dorisSink).name("Doris ODS Sink");
        
        log.info("Doris Sink created (官方 Connector):");
        log.info("  FE Nodes: " + config.getString("doris.fe.http-url"));
        log.info("  Database: " + config.getString("doris.database"));
        log.info("  Table: " + config.getString("doris.tables.ods"));
        
        
        log.info("==========================================");
        log.info("Starting Flink Job...");
        log.info("==========================================");
        
        
        // 执行作业
        env.execute("Flink ODS Job - 官方 Doris Connector");
    }
    
    /**
     * 创建 Kafka Source
     * 
     * 消费模式说明：
     * - earliest: 从最早的数据开始消费（适合处理历史数据）
     * - latest: 从最新的数据开始消费（适合实时处理）
     * - committed: 从上次提交的 offset 开始消费
     */
    private static KafkaSource<String> createKafkaSource(ConfigLoader config) {
        // 读取消费模式配置，默认使用 earliest（从头开始消费）
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        // 根据配置选择消费模式
        OffsetsInitializer offsetsInitializer;
        switch (startupMode.toLowerCase()) {
            case "latest":
                offsetsInitializer = OffsetsInitializer.latest();
                log.info("  Startup Mode: latest（从最新数据开始）");
                break;
            case "committed":
                offsetsInitializer = OffsetsInitializer.committedOffsets();
                log.info("  Startup Mode: committed（从上次提交的 offset 开始）");
                break;
            case "earliest":
            default:
                offsetsInitializer = OffsetsInitializer.earliest();
                log.info("  Startup Mode: earliest（从最早数据开始）");
                break;
        }
        
        return KafkaSource.<String>builder()
            .setBootstrapServers(config.getString("kafka.bootstrap-servers"))
            .setTopics(config.getString("kafka.topic.crypto-ticker"))
            .setGroupId(config.getString("kafka.consumer.group-id", "flink-ods-consumer"))
            .setStartingOffsets(offsetsInitializer)  // 使用配置的消费模式
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
    }
    
    /**
     * 创建官方 Doris Sink
     * 使用 Doris Flink Connector,内部处理了 HTTP 协议兼容性问题
     * 
     * 注意: 如果 BE 使用 Docker 内部 IP,需要配置 benodes 参数
     */
    private static DorisSink<String> createDorisSink(ConfigLoader config) {
        // 提取 FE 地址 (去掉 http:// 前缀)
        String feHttpUrl = config.getString("doris.fe.http-url");
        String feNodes = feHttpUrl.replace("http://", "").replace("https://", "");
        
        // 读取数据库和表名配置
        String database = config.getString("doris.database", "crypto_dw");
        String table = config.getString("doris.tables.ods", "ods_crypto_ticker_rt");
        String tableIdentifier = database + "." + table;
        
        logger.info("Doris Sink 配置:");
        logger.info("  FE Nodes: {}", feNodes);
        logger.info("  Database: {}", database);
        logger.info("  Table: {}", table);
        logger.info("  Table Identifier: {}", tableIdentifier);
        
        // Doris 连接配置
        DorisOptions.Builder dorisBuilder = DorisOptions.builder()
            .setFenodes(feNodes)  // FE 地址,格式: host:port
            .setTableIdentifier(tableIdentifier)  // 数据库.表名
            .setUsername(config.getString("doris.fe.username", "root"))
            .setPassword(config.getString("doris.fe.password", ""));
        
        // 关键修复: 如果 BE 使用 Docker 内部 IP,直接指定 BE 地址
        // 这样可以绕过 FE 返回的内部 IP
        String beNodes = config.getString("doris.be.nodes", "");
        if (!beNodes.isEmpty()) {
            dorisBuilder.setBenodes(beNodes);
            logger.info("使用配置的 BE 节点: {}", beNodes);
        }
        
        // Stream Load 执行配置
        Properties streamLoadProp = new Properties();
        streamLoadProp.setProperty("format", "json");  // 数据格式
        streamLoadProp.setProperty("read_json_by_line", "true");  // 按行读取 JSON
        streamLoadProp.setProperty("strip_outer_array", "false");  // 不剥离外层数组
        
        // 批量写入配置
        int batchSize = config.getInt("doris.stream-load.batch-size", 1000);  // 批量行数
        int batchIntervalMs = config.getInt("doris.stream-load.batch-interval-ms", 5000);  // 批量间隔（毫秒）
        
        DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
            .setStreamLoadProp(streamLoadProp)  // Stream Load 属性
            .setMaxRetries(config.getInt("doris.stream-load.max-retries", 3))  // 最大重试次数
            // 修复：setBufferSize 是字节数，不是行数，设置为 10MB
            .setBufferSize(10 * 1024 * 1024)  // 缓冲区大小：10MB
            .setBufferCount(3)  // 缓冲区数量
            // 修复：使用 setBufferFlushMaxRows 设置批量行数
            .setBufferFlushMaxRows(batchSize)  // 批量行数：1000 行
            // 修复：使用 setBufferFlushIntervalMs 设置批量间隔
            .setBufferFlushIntervalMs(batchIntervalMs)  // 批量间隔：5000ms
            .setLabelPrefix("flink-ods-" + System.currentTimeMillis())  // 使用时间戳作为 Label 前缀,避免重复
            .build();
        
        // 构建 DorisSink
        return DorisSink.<String>builder()
            .setDorisReadOptions(DorisReadOptions.builder().build())
            .setDorisExecutionOptions(executionOptions)
            .setDorisOptions(dorisBuilder.build())
            .setSerializer(new SimpleStringSerializer())  // JSON 字符串序列化器
            .build();
    }
    
    /**
     * ODS 数据转换函数
     * 将 Kafka JSON 数据转换为 Doris 格式
     */
    public static class ODSTransformFunction implements MapFunction<String, String> {
        
        private final ObjectMapper objectMapper = new ObjectMapper();
        
        @Override
        public String map(String value) throws Exception {
            try {
                // 解析 Ticker 数据
                TickerData ticker = objectMapper.readValue(value, TickerData.class);
                
                // 构建 Doris JSON 格式
                StringBuilder json = new StringBuilder();
                json.append("{");
                json.append("\"inst_id\":\"").append(ticker.getInstId()).append("\",");
                json.append("\"timestamp\":").append(ticker.getTs()).append(",");
                json.append("\"last_price\":").append(ticker.getLast()).append(",");
                json.append("\"bid_price\":").append(ticker.getBidPx()).append(",");
                json.append("\"ask_price\":").append(ticker.getAskPx()).append(",");
                json.append("\"bid_size\":").append(ticker.getBidSz()).append(",");
                json.append("\"ask_size\":").append(ticker.getAskSz()).append(",");
                json.append("\"volume_24h\":").append(ticker.getVol24h()).append(",");
                json.append("\"high_24h\":").append(ticker.getHigh24h()).append(",");
                json.append("\"low_24h\":").append(ticker.getLow24h()).append(",");
                json.append("\"open_24h\":").append(ticker.getOpen24h()).append(",");
                json.append("\"data_source\":\"OKX\",");
                json.append("\"ingest_time\":").append(System.currentTimeMillis());
                json.append("}");
                
                return json.toString();
                
            } catch (Exception e) {
                logger.error("Failed to transform data: {}", e.getMessage());
                // 返回 null 会被过滤掉
                return null;
            }
        }
    }
}
