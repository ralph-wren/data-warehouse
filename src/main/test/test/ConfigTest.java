package test;

import com.crypto.dw.config.ConfigLoader;
import lombok.extern.slf4j.Slf4j;

/**
 * 配置测试类
 * 用于验证配置加载和环境变量解析
 */
@Slf4j
public class ConfigTest {
    
    public static void main(String[] args) {
        log.info("=== 配置加载测试 ===\n");
        
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 测试 OKX 配置
        log.info("OKX 配置:");
        log.info("  WebSocket URL: " + config.getString("okx.websocket.url"));
        log.info("  API Key: " + maskSensitive(config.getString("okx.api.key")));
        log.info("  Secret Key: " + maskSensitive(config.getString("okx.api.secret")));
        log.info("  Passphrase: " + maskSensitive(config.getString("okx.api.passphrase")));
        log.info("  Max Retries: " + config.getInt("okx.websocket.reconnect.max-retries"));
        
        
        // 测试 Kafka 配置
        log.info("Kafka 配置:");
        log.info("  Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        log.info("  Topic: " + config.getString("kafka.topic.crypto-ticker"));
        log.info("  Producer Acks: " + config.getString("kafka.producer.acks"));
        log.info("  Batch Size: " + config.getInt("kafka.producer.batch-size"));
        
        
        // 测试 Doris 配置
        log.info("Doris 配置:");
        log.info("  HTTP URL: " + config.getString("doris.fe.http-url"));
        log.info("  JDBC URL: " + config.getString("doris.fe.jdbc-url"));
        log.info("  Username: " + config.getString("doris.fe.username"));
        log.info("  Database: " + config.getString("doris.database"));
        log.info("  Batch Size: " + config.getInt("doris.stream-load.batch-size"));
        
        
        // 测试 Flink 配置
        log.info("Flink 配置:");
        log.info("  Runtime Mode: " + config.getString("flink.execution.runtime-mode"));
        log.info("  Parallelism: " + config.getInt("flink.execution.parallelism"));
        log.info("  Max Parallelism: " + config.getInt("flink.execution.max-parallelism"));
        log.info("  Buffer Timeout(ms): " + config.getLong("flink.execution.buffer-timeout-ms"));
        log.info("  Auto Watermark Interval(ms): " + config.getLong("flink.execution.auto-watermark-interval-ms"));
        log.info("  Checkpoint Interval: " + config.getLong("flink.checkpoint.interval"));
        log.info("  Checkpoint Mode: " + config.getString("flink.checkpoint.mode"));
        log.info("  Checkpoint Timeout: " + config.getLong("flink.checkpoint.timeout"));
        log.info("  Checkpoint Min Pause: " + config.getLong("flink.checkpoint.min-pause"));
        log.info("  Checkpoint Max Concurrent: " + config.getInt("flink.checkpoint.max-concurrent"));
        log.info("  Tolerable Failed Checkpoints: " + config.getInt("flink.checkpoint.tolerable-failed-checkpoints"));
        log.info("  Externalized Retention: " + config.getString("flink.checkpoint.externalized-retention"));
        log.info("  Snapshot Compression: " + config.getBoolean("flink.checkpoint.snapshot-compression"));
        log.info("  Checkpoints After Tasks Finish: " + config.getBoolean("flink.checkpoint.checkpoints-after-tasks-finish"));
        log.info("  Unaligned Enabled: " + config.getBoolean("flink.checkpoint.unaligned.enabled"));
        log.info("  Unaligned Aligned Timeout(ms): " + config.getLong("flink.checkpoint.unaligned.aligned-timeout-ms"));
        log.info("  Unaligned Max Subtasks/File: " + config.getInt("flink.checkpoint.unaligned.max-subtasks-per-channel-state-file"));
        // 新增：输出状态后端相关配置，方便确认配置文件是否读取正确
        log.info("  State Backend: " + config.getString("flink.state.backend"));
        log.info("  Checkpoint Dir: " + config.getString("flink.state.checkpoint-dir"));
        log.info("  Savepoint Dir: " + config.getString("flink.state.savepoint-dir"));
        log.info("  Incremental: " + config.getBoolean("flink.state.incremental"));
        log.info("  Local Recovery: " + config.getBoolean("flink.state.local-recovery"));
        log.info("  Restart Strategy: " + config.getString("flink.restart.strategy"));
        log.info("  Fixed Delay Attempts: " + config.getInt("flink.restart.fixed-delay.attempts"));
        log.info("  Fixed Delay(ms): " + config.getLong("flink.restart.fixed-delay.delay-ms"));
        log.info("  Failure Rate Max Failures/Interval: " + config.getInt("flink.restart.failure-rate.max-failures-per-interval"));
        log.info("  Failure Rate Interval(ms): " + config.getLong("flink.restart.failure-rate.failure-rate-interval-ms"));
        log.info("  Failure Rate Delay(ms): " + config.getLong("flink.restart.failure-rate.delay-ms"));
        
        
        // 测试应用配置
        log.info("应用配置:");
        log.info("  Log Level: " + config.getString("application.logging.level"));
        log.info("  Metrics Enabled: " + config.getBoolean("application.metrics.enabled"));
        log.info("  Dedup Window: " + config.getLong("application.data-quality.dedup-window"));
        
        
        // 打印所有配置
        log.info("\n=== 完整配置 ===");
        config.printConfig();
        
        // 测试环境变量
        log.info("\n=== 环境变量检查 ===");
        checkEnvVar("OKX_API_KEY");
        checkEnvVar("OKX_SECRET_KEY");
        checkEnvVar("OKX_PASSPHRASE");
        checkEnvVar("APP_ENV");
    }
    
    private static String maskSensitive(String value) {
        if (value == null || value.isEmpty()) {
            return "<未设置>";
        }
        if (value.length() <= 8) {
            return "******";
        }
        return value.substring(0, 4) + "..." + value.substring(value.length() - 4);
    }
    
    private static void checkEnvVar(String name) {
        String value = System.getenv(name);
        if (value == null || value.isEmpty()) {
            log.info("  " + name + ": ❌ 未设置");
        } else {
            log.info("  " + name + ": ✓ 已设置 (" + maskSensitive(value) + ")");
        }
    }
}
