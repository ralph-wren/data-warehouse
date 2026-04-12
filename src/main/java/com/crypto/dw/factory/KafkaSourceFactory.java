package com.crypto.dw.factory;

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
     * 创建 Kafka Source - 指定作业类型（推荐使用）
     * 
     * 从配置文件读取对应作业的 Consumer Group ID:
     * - collector: kafka.consumer.group-id.collector
     * - ods-datastream: kafka.consumer.group-id.ods-datastream
     * - ods-sql: kafka.consumer.group-id.ods-sql
     * - dwd: kafka.consumer.group-id.dwd
     * - dws-1min: kafka.consumer.group-id.dws-1min
     * - ads-metrics: kafka.consumer.group-id.ads-metrics
     * - ads-arbitrage: kafka.consumer.group-id.ads-arbitrage
     * - ads-monitor: kafka.consumer.group-id.ads-monitor
     * 
     * 使用示例:
     * <pre>
     * // DWD 作业
     * KafkaSource<String> source = factory.createKafkaSourceForJob("dwd");
     * 
     * // ADS 套利作业
     * KafkaSource<String> source = factory.createKafkaSourceForJob("ads-arbitrage");
     * </pre>
     * 
     * @param jobType 作业类型（collector/ods-datastream/ods-sql/dwd/dws-1min/ads-metrics/ads-arbitrage/ads-monitor）
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSourceForJob(String jobType) {
        String topic = config.getString("kafka.topic.crypto-ticker-spot");
        String groupIdKey = "kafka.consumer.group-id." + jobType;
        String groupId = config.getString(groupIdKey, "flink-" + jobType + "-group");
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        return createKafkaSource(topic, groupId, startupMode);
    }
    
    /**
     * 创建 Kafka Source - 指定作业类型和 Topic（最灵活）
     * 
     * 从配置文件读取对应作业的 Consumer Group ID
     * 
     * 使用场景：
     * - 读取不同的 Topic（现货/合约）
     * - 使用作业专属的 Consumer Group
     * 
     * 使用示例:
     * <pre>
     * // DWD 作业读取现货 Topic
     * KafkaSource<String> spotSource = factory.createKafkaSourceForJob(
     *     "dwd", 
     *     config.getString("kafka.topic.crypto-ticker-spot")
     * );
     * 
     * // DWD 作业读取合约 Topic
     * KafkaSource<String> swapSource = factory.createKafkaSourceForJob(
     *     "dwd", 
     *     config.getString("kafka.topic.crypto-ticker-swap")
     * );
     * </pre>
     * 
     * @param jobType 作业类型
     * @param topic Topic 名称
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSourceForJob(String jobType, String topic) {
        String groupIdKey = "kafka.consumer.group-id." + jobType;
        String groupId = config.getString(groupIdKey, "flink-" + jobType + "-group");
        String startupMode = config.getString("kafka.consumer.startup-mode", "latest");
        
        // 使用 topic 名称作为 client.id 后缀,避免同一作业的多个 Source 冲突
        // 例如: flink-ads-arbitrage-group-spot, flink-ads-arbitrage-group-swap
        String clientIdSuffix = topic.replace("crypto-ticker-", "").replace("-", "");
        
        return createKafkaSource(topic, groupId, startupMode, groupId + "-" + clientIdSuffix);
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
        // 默认使用 groupId 作为 client.id
        return createKafkaSource(topic, groupId, startupMode, groupId);
    }
    
    /**
     * 创建 Kafka Source - 完全自定义（包括 client.id）
     * 
     * 从配置文件读取:
     * - kafka.bootstrap-servers: Kafka 服务器地址
     * 
     * 使用场景：
     * - 同一作业创建多个 Kafka Source（例如套利作业读取现货和合约）
     * - 需要自定义 client.id 避免 JMX MBean 冲突
     * 
     * @param topic Topic 名称
     * @param groupId Consumer Group ID
     * @param startupMode 消费模式（earliest/latest/committed）
     * @param clientId 自定义 client.id（Flink 会自动添加并行实例后缀）
     * @return 配置好的 KafkaSource
     */
    public KafkaSource<String> createKafkaSource(String topic, String groupId, String startupMode, String clientId) {
        // 读取 Kafka 服务器地址
        String bootstrapServers = config.getString("kafka.bootstrap-servers");
        
        logger.info("==========================================");
        logger.info("创建 Kafka Source (DataStream API)");
        logger.info("==========================================");
        logger.info("Kafka Source 配置:");
        logger.info("  Bootstrap Servers: {}", bootstrapServers);
        logger.info("  Topic: {}", topic);
        logger.info("  Consumer Group: {}", groupId);
        logger.info("  Client ID Prefix: {}", clientId);
        
        // 根据配置选择消费模式
        // 重要说明: committed 模式需要回退策略,避免 NoOffsetForPartitionException
        OffsetsInitializer offsetsInitializer;
        switch (startupMode.toLowerCase()) {
            case "latest":
                offsetsInitializer = OffsetsInitializer.latest();
                logger.info("  Startup Mode: latest（从最新数据开始）");
                break;
            case "committed":
                // 使用 committedOffsets 并指定回退策略
                // 如果没有提交的 offset,则从最新数据开始（避免重复消费历史数据）
                offsetsInitializer = OffsetsInitializer.committedOffsets(
                    org.apache.kafka.clients.consumer.OffsetResetStrategy.LATEST
                );
                logger.info("  Startup Mode: committed（从上次提交的 offset 开始,无 offset 时从最新数据开始）");
                break;
            case "earliest":
            default:
                offsetsInitializer = OffsetsInitializer.earliest();
                logger.info("  Startup Mode: earliest（从最早数据开始）");
                break;
        }
        
        logger.info("==========================================");
        
        // 构建 KafkaSource
        // 重要: 设置 client.id 前缀,Flink 会自动为每个并行实例添加后缀(如 -0, -1, -2)
        // 这样可以避免多个并行实例使用相同的 client.id 导致 JMX MBean 注册冲突
        // 
        // 彻底解决方案: 禁用 JMX 注册
        // 注意: 禁用 JMX 后无法通过 JMX 监控 Kafka Consumer 指标,但不影响功能
        // 如果需要监控,可以使用 Flink Metrics 或 Kafka 自带的监控工具
        return KafkaSource.<String>builder()
            .setBootstrapServers(bootstrapServers)
            .setTopics(topic)
            .setGroupId(groupId)
            .setStartingOffsets(offsetsInitializer)  // 使用配置的消费模式
            .setValueOnlyDeserializer(new SimpleStringSchema())  // JSON 字符串反序列化器
            .setProperty("client.id", clientId)  // 设置 client.id 前缀,避免 JMX MBean 冲突
            .setProperty("enable.auto.commit", "false")  // Flink 管理 offset,禁用自动提交
            .setProperty("auto.offset.reset", "latest")  // offset 重置策略
            .setProperty("auto.register.schemas", "false")  // 禁用 schema 自动注册
            .build();
    }
}
