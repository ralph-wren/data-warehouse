package com.crypto.dw.flink.factory;

import com.crypto.dw.config.ConfigLoader;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.connector.kafka.source.enumerator.initializer.OffsetsInitializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Kafka Source 工厂类
 * 
 * 封装 Kafka Source 的创建逻辑，避免重复代码
 * 统一管理 Kafka 连接配置（Bootstrap Servers、Topic、Consumer Group 等）
 * 
 * 功能:
 * - 创建 DataStream API 的 KafkaSource
 * - 统一配置管理（从 ConfigLoader 读取）
 * - 支持多种消费模式（earliest/latest/committed）
 * - 支持自定义 Topic 和 Consumer Group
 * 
 * 使用示例:
 * <pre>
 * ConfigLoader config = ConfigLoader.getInstance();
 * KafkaSourceFactory factory = new KafkaSourceFactory(config);
 * 
 * // 方式 1: 使用默认配置（从配置文件读取 Topic 和 Consumer Group）
 * KafkaSource<String> source1 = factory.createKafkaSource();
 * 
 * // 方式 2: 自定义 Consumer Group
 * KafkaSource<String> source2 = factory.createKafkaSource("my-consumer-group");
 * 
 * // 方式 3: 自定义 Topic 和 Consumer Group（更灵活）
 * KafkaSource<String> source3 = factory.createKafkaSource(
 *     "my-topic",              // Topic 名称
 *     "my-consumer-group"      // Consumer Group ID
 * );
 * 
 * // 方式 4: 完全自定义（指定消费模式）
 * KafkaSource<String> source4 = factory.createKafkaSource(
 *     "my-topic",              // Topic 名称
 *     "my-consumer-group",     // Consumer Group ID
 *     "earliest"               // 消费模式（earliest/latest/committed）
 * );
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class KafkaSourceFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaSourceFactory.class);
    
    private final ConfigLoader config;
    
    public KafkaSourceFactory(ConfigLoader config) {
        this.config = config;
    }
    
    /**
     * 创建 Kafka Source - 使用默认配置
     * 
     * 从配置文件读取:
     * - kafka.bootstrap-servers: Kafka 服务器地址
     * - kafka.topic.crypto-ticker: Topic 名称
     * - kafka.consumer.group-id: Consumer Group ID
     * - kafka.consumer.startup-mode: 消费模式（earliest/latest/committed）
     * 
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSource() {
        String topic = config.getString("kafka.topic.crypto-ticker");
        String groupId = config.getString("kafka.consumer.group-id", "flink-consumer");
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        return createKafkaSource(topic, groupId, startupMode);
    }
    
    /**
     * 创建 Kafka Source - 自定义 Consumer Group
     * 
     * 从配置文件读取:
     * - kafka.bootstrap-servers: Kafka 服务器地址
     * - kafka.topic.crypto-ticker: Topic 名称
     * - kafka.consumer.startup-mode: 消费模式
     * 
     * @param groupId Consumer Group ID
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSource(String groupId) {
        String topic = config.getString("kafka.topic.crypto-ticker");
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        return createKafkaSource(topic, groupId, startupMode);
    }
    
    /**
     * 创建 Kafka Source - 自定义 Topic 和 Consumer Group（更灵活）
     * 
     * 从配置文件读取:
     * - kafka.bootstrap-servers: Kafka 服务器地址
     * - kafka.consumer.startup-mode: 消费模式
     * 
     * 使用场景：
     * - 读取不同的 Topic
     * - 使用独立的 Consumer Group
     * - 多个作业读取同一个 Topic
     * 
     * @param topic Topic 名称
     * @param groupId Consumer Group ID
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSource(String topic, String groupId) {
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        return createKafkaSource(topic, groupId, startupMode);
    }
    
    /**
     * 创建 Kafka Source - 完全自定义（最灵活）
     * 
     * 从配置文件读取:
     * - kafka.bootstrap-servers: Kafka 服务器地址
     * 
     * 使用场景：
     * - 完全控制所有参数
     * - 测试不同的消费模式
     * - 动态指定 Topic 和 Consumer Group
     * 
     * 消费模式说明：
     * - earliest: 从最早的数据开始消费（适合处理历史数据）
     * - latest: 从最新的数据开始消费（适合实时处理）
     * - committed: 从上次提交的 offset 开始消费（适合故障恢复）
     * 
     * @param topic Topic 名称
     * @param groupId Consumer Group ID
     * @param startupMode 消费模式（earliest/latest/committed）
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSource(String topic, String groupId, String startupMode) {
        // 读取 Kafka 服务器地址
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        
        logger.info("==========================================");
        logger.info("创建 Kafka Source (DataStream API)");
        logger.info("==========================================");
        logger.info("Kafka Source 配置:");
        logger.info("  Bootstrap Servers: {}", bootstrapServers);
        logger.info("  Topic: {}", topic);
        logger.info("  Consumer Group: {}", groupId);
        
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
        
        logger.info("==========================================");
        
        // 构建 KafkaSource
        return KafkaSource.<String>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(topic)
            .setGroupId(groupId)
            .setStartingOffsets(offsetsInitializer)  // 使用配置的消费模式
            .setValueOnlyDeserializer(new SimpleStringSchema())  // JSON 字符串反序列化器
            .build();
    }
}
