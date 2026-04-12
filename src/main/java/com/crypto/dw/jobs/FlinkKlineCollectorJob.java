package com.crypto.dw.jobs;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.source.OKXKlineWebSocketSourceFunction;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink K线数据采集作业
 * <p>
 * 从 OKX WebSocket 接收实时 K线数据并发送到 Kafka
 * <p>
 * 功能:
 * - 从 Redis kline:subscriptions 获取订阅的币对和周期
 * - 币对格式: BTC-USDT:4H, ETH-USDT:1H, SOL-USDT:15m
 * - 定时（5分钟）从 Redis 检查订阅变化
 * - 动态添加/取消订阅
 * - 写入 Kafka Topic: okx-kline-data
 * <p>
 * 数据流:
 * <pre>
 * Redis (kline:subscriptions)
 *     │
 *     ├─ BTC-USDT:4H
 *     ├─ ETH-USDT:1H
 *     └─ SOL-USDT:15m
 *     │
 *     ▼
 * OKX WebSocket (candle channel)
 *     │
 *     ├─ candle4H-BTC-USDT
 *     ├─ candle1H-ETH-USDT
 *     └─ candle15m-SOL-USDT
 *     │
 *     ▼
 * Kafka Topic: okx-kline-data
 * </pre>
 * <p>
 * 使用方法:
 * <pre>
 * # 本地运行
 * mvn clean compile
 * bash run-flink-kline-collector.sh
 *
 * # 指定 Redis 地址
 * bash run-flink-kline-collector.sh --redis-host localhost --redis-port 6379
 * </pre>
 * <p>
 * Redis 数据格式:
 * <pre>
 * # 使用 Set 存储订阅
 * SADD kline:subscriptions "BTC-USDT:4H"
 * SADD kline:subscriptions "ETH-USDT:1H"
 * SADD kline:subscriptions "SOL-USDT:15m"
 *
 * # 查看所有订阅
 * SMEMBERS kline:subscriptions
 *
 * # 删除订阅
 * SREM kline:subscriptions "SOL-USDT:15m"
 * </pre>
 */
public class FlinkKlineCollectorJob {

    private static final Logger logger = LoggerFactory.getLogger(FlinkKlineCollectorJob.class);

    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink K-line Collector Job");
        logger.info("==========================================");

        // 从程序参数中读取 APP_ENV（支持 StreamPark Remote 模式）
        for (int i = 0; i < args.length - 1; i++) {
            if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
                String envFromArgs = args[i + 1];
                logger.info("Found APP_ENV in program arguments: " + envFromArgs);
                System.setProperty("APP_ENV", envFromArgs);
                break;
            }
        }

        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        logger.info("Configuration loaded successfully");

        // 打印配置信息
        logger.info("=== 配置信息 ===");
        logger.info("Kafka Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        logger.info("Kafka Topic: " + config.getString("kline.kafka.topic", "okx-kline-data"));
        logger.info("OKX WebSocket URL: " + config.getString("okx.websocket.url"));
        logger.info("Redis Host: " + config.getString("redis.host", "localhost"));
        logger.info("Redis Port: " + config.getInt("redis.port", 6379));
        logger.info("Subscription Refresh Interval: " + config.getInt("kline.subscription.refresh-interval-seconds", 300) + " seconds");
        logger.info("================");

        // 使用工厂类创建 Flink Stream Environment
        // 注意: 使用端口 8086 避免与其他作业冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment("flink-kline-collector-job", 8086);

        // 获取 Kafka 配置
        String kafkaBootstrapServers = config.getString("kafka.bootstrap-servers");
        String klineTopic = config.getString("kline.kafka.topic", "okx-kline-data");

        logger.info("Kafka Configuration:");
        logger.info("  Bootstrap Servers: {}", kafkaBootstrapServers);
        logger.info("  K-line Topic: {}", klineTopic);

        // 创建数据流: OKX K-line WebSocket Source
        DataStream<String> sourceStream = env.addSource(
                new OKXKlineWebSocketSourceFunction(config)
        ).name("OKX K-line WebSocket Source");

        // 创建 Kafka Sink（K线数据）
        logger.info("Creating Kafka Sink for K-line data...");
        KafkaSink<String> klineKafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaBootstrapServers)
                .setRecordSerializer(
                        KafkaRecordSerializationSchema.builder()
                                .setTopic(klineTopic)
                                .setValueSerializationSchema(new SimpleStringSchema())
                                .build()
                )
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        // K线数据写入 Kafka
        sourceStream.sinkTo(klineKafkaSink).name("Kafka Sink (K-line)");

        logger.info("==========================================");
        logger.info("Starting Flink K-line Collector Job...");
        logger.info("Web UI: http://localhost:8086");
        logger.info("==========================================");

        // 执行作业
        env.execute("Flink K-line Collector Job");
    }
}
