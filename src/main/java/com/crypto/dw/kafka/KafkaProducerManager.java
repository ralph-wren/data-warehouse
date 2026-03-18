package com.crypto.dw.kafka;

import com.crypto.dw.config.ConfigLoader;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.StringSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Kafka Producer 管理器
 */
public class KafkaProducerManager {
    
    private static final Logger logger = LoggerFactory.getLogger(KafkaProducerManager.class);
    
    private final KafkaProducer<String, String> producer;
    private final String topic;
    private final AtomicLong successCount = new AtomicLong(0);
    private final AtomicLong failureCount = new AtomicLong(0);
    
    public KafkaProducerManager(ConfigLoader config) {
        this.topic = config.getString("kafka.topic.crypto-ticker");
        this.producer = createProducer(config);
        logger.info("Kafka Producer initialized. Topic: {}", topic);
    }
    
    /**
     * 创建 Kafka Producer
     */
    private KafkaProducer<String, String> createProducer(ConfigLoader config) {
        Properties props = new Properties();
        
        // 基础配置
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, 
            config.getString("kafka.bootstrap-servers"));
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, 
            StringSerializer.class.getName());
        
        // Producer 配置
        props.put(ProducerConfig.ACKS_CONFIG, 
            config.getString("kafka.producer.acks", "1"));
        props.put(ProducerConfig.RETRIES_CONFIG, 
            config.getInt("kafka.producer.retries", 3));
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 
            config.getInt("kafka.producer.batch-size", 16384));
        props.put(ProducerConfig.LINGER_MS_CONFIG, 
            config.getInt("kafka.producer.linger-ms", 10));
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, 
            config.getString("kafka.producer.compression-type", "lz4"));
        props.put(ProducerConfig.MAX_IN_FLIGHT_REQUESTS_PER_CONNECTION, 
            config.getInt("kafka.producer.max-in-flight-requests", 5));
        
        // 客户端 ID
        props.put(ProducerConfig.CLIENT_ID_CONFIG, "crypto-dw-producer");
        
        return new KafkaProducer<>(props);
    }
    
    /**
     * 发送消息到 Kafka
     * 
     * @param key 消息键（用于分区）
     * @param value 消息内容（JSON 格式）
     * @return CompletableFuture<RecordMetadata>
     */
    public CompletableFuture<RecordMetadata> send(String key, String value) {
        CompletableFuture<RecordMetadata> future = new CompletableFuture<>();
        
        ProducerRecord<String, String> record = new ProducerRecord<>(topic, key, value);
        
        producer.send(record, (metadata, exception) -> {
            if (exception == null) {
                successCount.incrementAndGet();
                future.complete(metadata);
                
                if (logger.isDebugEnabled()) {
                    logger.debug("Message sent successfully. Key: {}, Partition: {}, Offset: {}", 
                        key, metadata.partition(), metadata.offset());
                }
            } else {
                failureCount.incrementAndGet();
                future.completeExceptionally(exception);
                logger.error("Failed to send message. Key: {}, Error: {}", key, exception.getMessage());
            }
        });
        
        return future;
    }
    
    /**
     * 刷新缓冲区
     */
    public void flush() {
        producer.flush();
    }
    
    /**
     * 关闭 Producer
     */
    public void close() {
        logger.info("Closing Kafka Producer. Success: {}, Failure: {}", 
            successCount.get(), failureCount.get());
        producer.close();
    }
    
    /**
     * 获取成功计数
     */
    public long getSuccessCount() {
        return successCount.get();
    }
    
    /**
     * 获取失败计数
     */
    public long getFailureCount() {
        return failureCount.get();
    }
    
    /**
     * 获取 Topic 名称
     */
    public String getTopic() {
        return topic;
    }
}
