package com.crypto.dw;

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
        log.info("  Parallelism: " + config.getInt("flink.execution.parallelism"));
        log.info("  Checkpoint Interval: " + config.getLong("flink.checkpoint.interval"));
        log.info("  Checkpoint Mode: " + config.getString("flink.checkpoint.mode"));
        
        
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
