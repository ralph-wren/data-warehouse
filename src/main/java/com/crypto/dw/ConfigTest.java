package com.crypto.dw;

import com.crypto.dw.config.ConfigLoader;

/**
 * 配置测试类
 * 用于验证配置加载和环境变量解析
 */
public class ConfigTest {
    
    public static void main(String[] args) {
        System.out.println("=== 配置加载测试 ===\n");
        
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 测试 OKX 配置
        System.out.println("OKX 配置:");
        System.out.println("  WebSocket URL: " + config.getString("okx.websocket.url"));
        System.out.println("  API Key: " + maskSensitive(config.getString("okx.api.key")));
        System.out.println("  Secret Key: " + maskSensitive(config.getString("okx.api.secret")));
        System.out.println("  Passphrase: " + maskSensitive(config.getString("okx.api.passphrase")));
        System.out.println("  Max Retries: " + config.getInt("okx.websocket.reconnect.max-retries"));
        System.out.println();
        
        // 测试 Kafka 配置
        System.out.println("Kafka 配置:");
        System.out.println("  Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        System.out.println("  Topic: " + config.getString("kafka.topic.crypto-ticker"));
        System.out.println("  Producer Acks: " + config.getString("kafka.producer.acks"));
        System.out.println("  Batch Size: " + config.getInt("kafka.producer.batch-size"));
        System.out.println();
        
        // 测试 Doris 配置
        System.out.println("Doris 配置:");
        System.out.println("  HTTP URL: " + config.getString("doris.fe.http-url"));
        System.out.println("  JDBC URL: " + config.getString("doris.fe.jdbc-url"));
        System.out.println("  Username: " + config.getString("doris.fe.username"));
        System.out.println("  Database: " + config.getString("doris.database"));
        System.out.println("  Batch Size: " + config.getInt("doris.stream-load.batch-size"));
        System.out.println();
        
        // 测试 Flink 配置
        System.out.println("Flink 配置:");
        System.out.println("  Parallelism: " + config.getInt("flink.execution.parallelism"));
        System.out.println("  Checkpoint Interval: " + config.getLong("flink.checkpoint.interval"));
        System.out.println("  Checkpoint Mode: " + config.getString("flink.checkpoint.mode"));
        System.out.println();
        
        // 测试应用配置
        System.out.println("应用配置:");
        System.out.println("  Log Level: " + config.getString("application.logging.level"));
        System.out.println("  Metrics Enabled: " + config.getBoolean("application.metrics.enabled"));
        System.out.println("  Dedup Window: " + config.getLong("application.data-quality.dedup-window"));
        System.out.println();
        
        // 打印所有配置
        System.out.println("\n=== 完整配置 ===");
        config.printConfig();
        
        // 测试环境变量
        System.out.println("\n=== 环境变量检查 ===");
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
            System.out.println("  " + name + ": ❌ 未设置");
        } else {
            System.out.println("  " + name + ": ✓ 已设置 (" + maskSensitive(value) + ")");
        }
    }
}
