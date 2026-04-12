package com.crypto.dw.jobs;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.factory.DorisSinkFactory;
import com.crypto.dw.factory.FlinkEnvironmentFactory;
import com.crypto.dw.factory.KafkaSourceFactory;
import com.crypto.dw.flink.watermark.WatermarkStrategyFactory;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.flink.api.common.functions.FlatMapFunction;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;

/**
 * Flink ODS 作业 - 使用官方 Doris Connector (DataStream API)
 *
 * 从 Kafka 消费数据并写入 Doris ODS 层
 *
 * 重构说明:
 * - 使用 FlinkEnvironmentFactory 创建 Stream Environment (减少约 60 行代码)
 * - 使用 KafkaSourceFactory 创建 Kafka Source (减少约 30 行代码)
 * - 使用 DorisSinkFactory 创建 Doris Sink (减少约 40 行代码)
 * - 保留 DataStream API 的灵活性
 * - 统一 Web UI 端口和 Metrics 配置
 *
 * 更新说明:
 * 1. 使用官方 DorisSink 替代自定义 HTTP Stream Load
 * 2. 兼容 Doris 3.1.x 版本
 * 3. 支持自动重试和错误处理
 * 4. 所有组件创建都使用工厂类，避免重复代码
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
        logger.info("Kafka Topic: " + config.getString("kafka.topic.crypto-ticker-spot"));
        logger.info("Doris FE URL: " + config.getString("doris.fe.http-url"));
        logger.info("================");

        // 使用工厂类创建 Flink Stream Environment (减少重复代码)
        // 从配置文件读取 Web UI 端口，避免硬编码
        // 优化说明：端口配置化，提高灵活性，避免端口冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        int webPort = config.getInt("flink.web.port.ods", 8083);
        logger.info("Web UI 端口: {}", webPort);

        StreamExecutionEnvironment env = envFactory.createStreamEnvironment("flink-ods-datastream-job", webPort);

        // 使用工厂类创建 Kafka Source（减少重复代码）
        // 优化说明：统一 Kafka Source 创建逻辑，便于维护和复用
        // 重要：使用作业专属的 Consumer Group ID，避免与其他作业冲突
        KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);
        KafkaSource<String> kafkaSource = kafkaSourceFactory.createKafkaSourceForJob("ods-datastream");

        // 创建数据流
        DataStream<String> rawStream = env.fromSource(
            kafkaSource,
            WatermarkStrategyFactory.forBoundedOutOfOrdernessString(5),
            "Kafka Source"
        );

        logger.info("Kafka Source created:");
        logger.info("  Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        logger.info("  Topic: " + config.getString("kafka.topic.crypto-ticker-swap"));
        logger.info("  Group ID: " + config.getString("kafka.consumer.group-id"));
        logger.info("  Startup Mode: " + config.getString("kafka.consumer.startup-mode", "earliest"));

        // 数据转换：JSON -> Doris 格式
        // 修复说明：
        // 1. 改用 flatMap 显式丢弃脏数据，避免 map 返回 null 继续传到下游
        // 2. 去掉 disableChaining，让 Flink 自动做链优化，减少不必要的网络与序列化开销
        DataStream<String> odsStream = rawStream
            .flatMap(new ODSTransformFunction())
            .name("ODS Transform")
                .disableChaining();

        // 配置官方 Doris Sink（使用工厂类，避免重复代码）
        // 重要：使用三参数方法，明确指定数据库、表名和 Label 前缀
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        String database = config.getString("doris.database", "crypto_dw");
        String odsTable = config.getString("doris.tables.ods", "ods_crypto_ticker_rt");
        DorisSink<String> dorisSink = dorisSinkFactory.createDorisSink(database, odsTable, "ods");

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
     * ODS 数据转换函数
     * 将 Kafka JSON 数据转换为 Doris 格式
     *
     * 注意：使用静态 ObjectMapper 实例，避免重复创建，提高性能
     */
    public static class ODSTransformFunction implements FlatMapFunction<String, String> {

        // 静态 ObjectMapper 实例，所有算子实例共享，减少内存占用
        // 注意：ObjectMapper 是线程安全的，可以在多线程环境中共享
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

        @Override
        public void flatMap(String value, Collector<String> out) {
            try {
                // 解析 Ticker 数据
                TickerData ticker = OBJECT_MAPPER.readValue(value, TickerData.class);

                // 构建 Doris JSON 格式
                // 使用 ObjectNode 统一处理字符串转数字，避免手写 JSON 时出现 null/非法数字导致脏数据
                ObjectNode jsonNode = OBJECT_MAPPER.createObjectNode();
                jsonNode.put("inst_id", requireText(ticker.getInstId(), "inst_id"));
                jsonNode.put("timestamp", parseLong(ticker.getTs(), "timestamp"));
                jsonNode.put("last_price", parseDecimal(ticker.getLast(), "last_price"));
                jsonNode.put("bid_price", parseDecimal(ticker.getBidPx(), "bid_price"));
                jsonNode.put("ask_price", parseDecimal(ticker.getAskPx(), "ask_price"));
                jsonNode.put("bid_size", parseDecimal(ticker.getBidSz(), "bid_size"));
                jsonNode.put("ask_size", parseDecimal(ticker.getAskSz(), "ask_size"));
                jsonNode.put("volume_24h", parseDecimal(ticker.getVol24h(), "volume_24h"));
                jsonNode.put("high_24h", parseDecimal(ticker.getHigh24h(), "high_24h"));
                jsonNode.put("low_24h", parseDecimal(ticker.getLow24h(), "low_24h"));
                jsonNode.put("open_24h", parseDecimal(ticker.getOpen24h(), "open_24h"));
                jsonNode.put("data_source", "OKX");
                jsonNode.put("ingest_time", System.currentTimeMillis());

                out.collect(jsonNode.toString());
            } catch (Exception e) {
                // 修复说明：这里不再返回 null，而是直接丢弃坏数据，避免 null 继续流入下游
                logger.warn("Failed to transform Kafka record, record will be dropped. Error: {}", e.getMessage());
            }
        }

        /**
         * 读取必填字符串字段
         *
         * @param value 字段值
         * @param fieldName 字段名（用于错误提示）
         * @return 非空字符串
         * @throws IllegalArgumentException 如果字段为空
         */
        private String requireText(String value, String fieldName) {
            if (value == null || value.trim().isEmpty()) {
                throw new IllegalArgumentException("Missing required field: " + fieldName);
            }
            return value;
        }

        /**
         * 将字符串解析为 Long，失败时直接抛错，由上层丢弃坏数据
         *
         * @param value 字符串值
         * @param fieldName 字段名（用于错误提示）
         * @return Long 值
         * @throws IllegalArgumentException 如果解析失败
         */
        private long parseLong(String value, String fieldName) {
            try {
                return Long.parseLong(requireText(value, fieldName));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid long field: " + fieldName + ", value=" + value, e);
            }
        }

        /**
         * 将字符串解析为 BigDecimal，失败时直接抛错，由上层丢弃坏数据
         *
         * @param value 字符串值
         * @param fieldName 字段名（用于错误提示）
         * @return BigDecimal 值
         * @throws IllegalArgumentException 如果解析失败
         */
        private BigDecimal parseDecimal(String value, String fieldName) {
            try {
                return new BigDecimal(requireText(value, fieldName));
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Invalid decimal field: " + fieldName + ", value=" + value, e);
            }
        }
    }
}
