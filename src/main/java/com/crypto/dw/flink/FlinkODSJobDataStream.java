package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.doris.flink.cfg.DorisExecutionOptions;
import org.apache.doris.flink.cfg.DorisOptions;
import org.apache.doris.flink.cfg.DorisReadOptions;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.doris.flink.sink.writer.serializer.SimpleStringSerializer;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.MapFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
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
public class FlinkODSJobDataStream {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkODSJobDataStream.class);
    
    public static void main(String[] args) throws Exception {
        System.out.println("==========================================");
        System.out.println("Flink ODS Job (官方 Doris Connector)");
        System.out.println("==========================================");
        System.out.println();
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 创建 Flink 执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // 设置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 2);
        env.setParallelism(parallelism);
        
        // 启用 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 30000);
        env.enableCheckpointing(checkpointInterval);
        
        System.out.println("Flink Environment:");
        System.out.println("  Parallelism: " + parallelism);
        System.out.println("  Checkpoint Interval: " + checkpointInterval + " ms");
        System.out.println();
        
        // 配置 Kafka Source
        KafkaSource<String> kafkaSource = createKafkaSource(config);
        
        // 创建数据流
        DataStream<String> rawStream = env.fromSource(
            kafkaSource,
            WatermarkStrategy.noWatermarks(),
            "Kafka Source"
        );
        
        System.out.println("Kafka Source created:");
        System.out.println("  Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        System.out.println("  Topic: " + config.getString("kafka.topic.crypto-ticker"));
        System.out.println("  Group ID: " + config.getString("kafka.consumer.group-id"));
        System.out.println();
        
        // 数据转换：JSON -> Doris 格式
        DataStream<String> odsStream = rawStream
            .map(new ODSTransformFunction())
            .name("ODS Transform");
        
        // 配置官方 Doris Sink
        DorisSink<String> dorisSink = createDorisSink(config);
        
        // 写入 Doris
        odsStream.sinkTo(dorisSink).name("Doris ODS Sink");
        
        System.out.println("Doris Sink created (官方 Connector):");
        System.out.println("  FE Nodes: " + config.getString("doris.fe.http-url"));
        System.out.println("  Database: " + config.getString("doris.database"));
        System.out.println("  Table: " + config.getString("doris.tables.ods"));
        System.out.println();
        
        System.out.println("==========================================");
        System.out.println("Starting Flink Job...");
        System.out.println("==========================================");
        System.out.println();
        
        // 执行作业
        env.execute("Flink ODS Job - 官方 Doris Connector");
    }
    
    /**
     * 创建 Kafka Source
     */
    private static KafkaSource<String> createKafkaSource(ConfigLoader config) {
        return KafkaSource.<String>builder()
            .setBootstrapServers(config.getString("kafka.bootstrap-servers"))
            .setTopics(config.getString("kafka.topic.crypto-ticker"))
            .setGroupId(config.getString("kafka.consumer.group-id", "flink-ods-consumer"))
            .setStartingOffsets(OffsetsInitializer.latest())
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
        
        // Doris 连接配置
        DorisOptions.Builder dorisBuilder = DorisOptions.builder()
            .setFenodes(feNodes)  // FE 地址,格式: host:port
            .setTableIdentifier(
                config.getString("doris.database") + "." + 
                config.getString("doris.tables.ods")
            )
            .setUsername(config.getString("doris.fe.username"))
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
        
        DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
            .setStreamLoadProp(streamLoadProp)  // Stream Load 属性
            .setMaxRetries(config.getInt("doris.stream-load.max-retries", 3))  // 最大重试次数
            .setBufferSize(config.getInt("doris.stream-load.batch-size", 1000) * 1024)  // 缓冲区大小 (字节)
            .setBufferCount(3)  // 缓冲区数量
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
