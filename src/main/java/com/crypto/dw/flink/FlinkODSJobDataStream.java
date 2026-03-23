package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.watermark.WatermarkStrategyFactory;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink ODS 作业 - 使用官方 Doris Connector (DataStream API)
 * 
 * 从 Kafka 消费数据并写入 Doris ODS 层
 * 
 * 重构说明:
 * - 使用 FlinkEnvironmentFactory 创建 Stream Environment (减少约 60 行代码)
 * - 保留 DataStream API 的灵活性
 * - 统一 Web UI 端口和 Metrics 配置
 * 
 * 更新说明:
 * 1. 使用官方 DorisSink 替代自定义 HTTP Stream Load
 * 2. 兼容 Doris 3.1.x 版本
 * 3. 支持自动重试和错误处理
 */ 
public class FlinkODSJobDataStream {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkODSJobDataStream.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ODS Job (DataStream API)");
        logger.info("==========================================");

        // 诊断日志：输出所有相关的 System Properties 和环境变量
        logger.info("=== 诊断信息 ===");
        logger.info("Program Arguments: " + java.util.Arrays.toString(args));
        logger.info("System Property APP_ENV: " + System.getProperty("APP_ENV"));
        logger.info("Environment Variable APP_ENV: " + System.getenv("APP_ENV"));
        
        // 从程序参数中读取 APP_ENV（支持 StreamPark Remote 模式）
        // 格式：--env docker 或 --APP_ENV docker
        for (int i = 0; i < args.length - 1; i++) {
            if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
                String envFromArgs = args[i + 1];
                logger.info("Found APP_ENV in program arguments: " + envFromArgs);
                // 设置为 System Property，让 ConfigLoader 能读取到
                System.setProperty("APP_ENV", envFromArgs);
                break;
            }
        }
        
        logger.info("所有 System Properties:");
        System.getProperties().forEach((key, value) -> {
            String keyStr = key.toString();
            // 只输出我们关心的属性
            if (keyStr.startsWith("APP_") || keyStr.contains("flink") || keyStr.contains("kafka") || keyStr.contains("doris")) {
                logger.info("  " + keyStr + " = " + value);
            }
        });
        logger.info("================");
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 打印配置信息（调试用）
        logger.info("=== 配置信息 ===");
        logger.info("Kafka Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        logger.info("Kafka Topic: " + config.getString("kafka.topic.crypto-ticker"));
        logger.info("Doris FE URL: " + config.getString("doris.fe.http-url"));
        logger.info("================");

        // 使用工厂类创建 Flink Stream Environment (减少重复代码)
        // 注意: 使用端口 8083 避免与其他作业冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment("flink-ods-datastream-job", 8083);
        
        // 配置 Kafka Source
        KafkaSource<String> kafkaSource = createKafkaSource(config);
        
        // 创建数据流
        DataStream<String> rawStream = env.fromSource(
            kafkaSource,
            WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5),
            "Kafka Source"
        );
        
        logger.info("Kafka Source created:");
        logger.info("  Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        logger.info("  Topic: " + config.getString("kafka.topic.crypto-ticker"));
        logger.info("  Group ID: " + config.getString("kafka.consumer.group-id"));
        logger.info("  Startup Mode: " + config.getString("kafka.consumer.startup-mode", "earliest"));
        
        // 数据转换：JSON -> Doris 格式
        DataStream<String> odsStream = rawStream
            .map(new ODSTransformFunction()).disableChaining()
            .name("ODS Transform");
        
        // 配置官方 Doris Sink（使用工厂类，避免重复代码）
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        DorisSink<String> dorisSink = dorisSinkFactory.createDorisSink("ods");
        
        // 写入 Doris
        odsStream.sinkTo(dorisSink).name("Doris ODS Sink");
        
        logger.info("Doris Sink created (官方 Connector):");
        logger.info("  FE Nodes: " + config.getString("doris.fe.http-url"));
        logger.info("  Database: " + config.getString("doris.database"));
        logger.info("  Table: " + config.getString("doris.tables.ods"));
        
        logger.info("==========================================");
        logger.info("Starting Flink Job...");
        logger.info("==========================================");
        
        // 执行作业
        env.execute("Flink ODS Job - DataStream API");
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
        // 读取消费模式配置，默认使用 latest（从最新数据开始）
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        // 根据配置选择消费模式
        OffsetsInitializer offsetsInitializer;
        switch (startupMode.toLowerCase()) {
            case "latest":
                offsetsInitializer = OffsetsInitializer.latest();
                logger.info("  Startup Mode: latest（从最新数据开始）");
                break;
            case "committed":
                offsetsInitializer = OffsetsInitializer.committedOffsets();
                logger.info("  Startup Mode: committed（从上次提交的 offset 开始）");
                break;
            case "earliest":
            default:
                offsetsInitializer = OffsetsInitializer.earliest();
                logger.info("  Startup Mode: earliest（从最早数据开始）");
                break;
        }
        
        return KafkaSource.<String>builder()
            .setBootstrapServers(config.getString("kafka.bootstrap-servers"))
            .setTopics(config.getString("kafka.topic.crypto-ticker"))
            .setGroupId(config.getString("kafka.consumer.group-id", "flink-ods-datastream-consumer"))
            .setStartingOffsets(offsetsInitializer)  // 使用配置的消费模式
            .setValueOnlyDeserializer(new SimpleStringSchema())
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
