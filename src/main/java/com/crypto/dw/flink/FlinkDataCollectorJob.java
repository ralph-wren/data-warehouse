package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.source.OKXWebSocketSourceFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Flink 数据采集作业
 * 
 * 从 OKX WebSocket 接收实时行情数据并发送到 Kafka
 * 
 * 功能:
 * - 使用 Flink Source Function 封装 WebSocket 客户端
 * - 自动重连和错误处理
 * - 支持 Flink 的 Checkpoint 机制
 * - 统一的监控和管理
 * 
 * 优势:
 * - 利用 Flink 的容错机制
 * - 统一管理所有数据流
 * - 更好的监控和管理
 * - 支持动态扩缩容
 * 
 * 使用方法:
 * <pre>
 * # 本地运行
 * mvn clean compile
 * bash run-flink-collector.sh
 * 
 * # 指定交易对
 * bash run-flink-collector.sh BTC-USDT ETH-USDT SOL-USDT
 * </pre>
 */
public class FlinkDataCollectorJob {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkDataCollectorJob.class);
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink Data Collector Job");
        logger.info("==========================================");
        
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
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        logger.info("Configuration loaded successfully");
        
        // 打印配置信息（调试用）
        logger.info("=== 配置信息 ===");
        logger.info("Kafka Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        logger.info("Kafka Topic: " + config.getString("kafka.topic.crypto-ticker"));
        logger.info("OKX WebSocket URL: " + config.getString("okx.websocket.url"));
        logger.info("================");
        
        // 使用工厂类创建 Flink Stream Environment (减少重复代码)
        // 注意: 使用端口 8085 避免与其他作业冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment("flink-data-collector-job", 8085);
        
        // 获取订阅的交易对列表
        List<String> symbols = getSymbols(args, config);
        logger.info("Subscribing to symbols: {}", symbols);
        
        // 创建 Kafka Sink (使用 Flink Kafka Connector)
        // 注意: 使用 AT_LEAST_ONCE 保证数据不丢失
        String kafkaTopic = config.getString("kafka.topic.crypto-ticker", "crypto-ticker");
        String kafkaBootstrapServers = config.getString("kafka.bootstrap-servers");
        
        logger.info("Creating Kafka Sink...");
        logger.info("  Topic: {}", kafkaTopic);
        logger.info("  Bootstrap Servers: {}", kafkaBootstrapServers);
        
        KafkaSink<String> kafkaSink = KafkaSink.<String>builder()
            .setBootstrapServers(kafkaBootstrapServers)
            .setRecordSerializer(
                KafkaRecordSerializationSchema.builder()
                    .setTopic(kafkaTopic)
                    .setValueSerializationSchema(new SimpleStringSchema())
                    .build()
            )
            .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)  // 至少一次保证
            .build();
        
        // 创建数据流: OKX WebSocket Source → Kafka Sink
        // 架构: WebSocket → Flink Source → Flink Stream → Kafka Sink
        env.addSource(
            new OKXWebSocketSourceFunction(config, symbols)
        ).name("OKX WebSocket Source")
         .sinkTo(kafkaSink)  // 使用 Kafka Sink 写入数据
         .name("Kafka Sink");
        
        logger.info("==========================================");
        logger.info("Starting Flink Data Collector Job...");
        logger.info("Web UI: http://localhost:8085");
        logger.info("==========================================");
        
        // 执行作业
        env.execute("Flink Data Collector Job");
    }
    
    /**
     * 获取订阅的交易对列表
     * 
     * 注意：为了让数据均匀分布到 Kafka 的多个分区，建议订阅多个交易对
     * Kafka 使用 key（交易对名称）的 hash 值来决定分区，不同的交易对会分布到不同分区
     * 
     * @param args 命令行参数
     * @param config 配置加载器
     * @return 交易对列表
     */
    private static List<String> getSymbols(String[] args, ConfigLoader config) {
        // 过滤掉 --env 和 --APP_ENV 参数
        List<String> filteredArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
                i++; // 跳过下一个参数（环境值）
                continue;
            }
            filteredArgs.add(args[i]);
        }
        
        // 如果命令行参数提供了交易对，使用命令行参数
        if (!filteredArgs.isEmpty()) {
            logger.info("Using symbols from command line arguments: {}", filteredArgs);
            return filteredArgs;
        }
        
        // 尝试从配置文件读取（支持逗号分隔的字符串）
        String symbolsConfig = config.getString("okx.symbols", "");
        logger.info("Reading okx.symbols from config: '{}'", symbolsConfig);
        
        if (!symbolsConfig.isEmpty()) {
            // 支持逗号分隔的多个交易对
            String[] symbolArray = symbolsConfig.split(",");
            List<String> symbols = new ArrayList<>();
            for (String symbol : symbolArray) {
                String trimmed = symbol.trim();
                if (!trimmed.isEmpty()) {
                    symbols.add(trimmed);
                }
            }
            if (!symbols.isEmpty()) {
                logger.info("Using symbols from config file: {}", symbols);
                return symbols;
            }
        }
        
        // 默认订阅 4 个主流交易对（对应 4 个 Kafka 分区）
        // 这样数据会均匀分布到不同分区
        List<String> defaultSymbols = Arrays.asList("BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT");
        logger.info("Config not found or empty, using default symbols: {}", defaultSymbols);
        return defaultSymbols;
    }
}
