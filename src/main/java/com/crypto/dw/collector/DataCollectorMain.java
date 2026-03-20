package com.crypto.dw.collector;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.kafka.KafkaProducerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;

/**
 * 数据采集器主程序
 * 订阅 OKX WebSocket 行情数据并发送到 Kafka
 */
public class DataCollectorMain {
    
    private static final Logger logger = LoggerFactory.getLogger(DataCollectorMain.class);
    
    public static void main(String[] args) {
        logger.info("========================================");
        logger.info("Crypto Data Collector Starting...");
        logger.info("========================================");
        
        try {
            // 加载配置
            ConfigLoader config = ConfigLoader.getInstance();
            logger.info("Configuration loaded successfully");
            
            // 验证环境变量
            validateEnvironment(config);
            
            // 创建 Kafka Producer
            KafkaProducerManager kafkaProducer = new KafkaProducerManager(config);
            logger.info("Kafka Producer created. Topic: {}", kafkaProducer.getTopic());
            
            // 订阅的交易对（从配置读取或使用默认值）
            List<String> symbols = getSymbols(args, config);
            logger.info("Subscribing to symbols: {}", symbols);
            
            // 创建 WebSocket 客户端
            OKXWebSocketClient wsClient = new OKXWebSocketClient(config, kafkaProducer, symbols);
            
            // 连接 WebSocket
            logger.info("Connecting to OKX WebSocket...");
            wsClient.connectBlocking();
            
            if (wsClient.isConnected()) {
                logger.info("WebSocket connected successfully");
                logger.info("========================================");
                logger.info("Data collection started");
                logger.info("Press Ctrl+C to stop");
                logger.info("========================================");
            } else {
                logger.error("Failed to connect to WebSocket");
                System.exit(1);
            }
            
            // 添加关闭钩子
            addShutdownHook(wsClient, kafkaProducer);
            
            // 保持运行
            CountDownLatch latch = new CountDownLatch(1);
            
            // 定期打印统计信息
            startStatisticsThread(wsClient, kafkaProducer);
            
            // 等待
            latch.await();
            
        } catch (Exception e) {
            logger.error("Fatal error in data collector", e);
            System.exit(1);
        }
    }
    
    /**
     * 验证环境变量
     */
    private static void validateEnvironment(ConfigLoader config) {
        logger.info("Validating environment variables...");
        
        String apiKey = config.getString("okx.api.key");
        String secretKey = config.getString("okx.api.secret");
        String passphrase = config.getString("okx.api.passphrase");
        
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("OKX_API_KEY not set (not required for public WebSocket)");
        } else {
            logger.info("OKX_API_KEY: {}...{}", 
                apiKey.substring(0, Math.min(4, apiKey.length())),
                apiKey.length() > 8 ? apiKey.substring(apiKey.length() - 4) : "");
        }
        
        if (secretKey == null || secretKey.isEmpty()) {
            logger.warn("OKX_SECRET_KEY not set (not required for public WebSocket)");
        }
        
        if (passphrase == null || passphrase.isEmpty()) {
            logger.warn("OKX_PASSPHRASE not set (not required for public WebSocket)");
        }
        
        logger.info("Environment validation completed");
    }
    
    /**
     * 获取订阅的交易对列表
     * 
     * 注意：为了让数据均匀分布到 Kafka 的多个分区，建议订阅多个交易对
     * Kafka 使用 key（交易对名称）的 hash 值来决定分区，不同的交易对会分布到不同分区
     */
    private static List<String> getSymbols(String[] args, ConfigLoader config) {
        // 如果命令行参数提供了交易对，使用命令行参数
        if (args.length > 0) {
            logger.info("Using symbols from command line arguments: {}", Arrays.toString(args));
            return Arrays.asList(args);
        }
        
        // 尝试从配置文件读取（支持逗号分隔的字符串）
        String symbolsConfig = config.getString("okx.symbols", "");
        logger.info("Reading okx.symbols from config: '{}'", symbolsConfig); // 添加调试日志
        
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
    
    /**
     * 添加关闭钩子
     */
    private static void addShutdownHook(OKXWebSocketClient wsClient, KafkaProducerManager kafkaProducer) {
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            logger.info("========================================");
            logger.info("Shutdown signal received");
            logger.info("========================================");
            
            try {
                // 关闭 WebSocket
                logger.info("Closing WebSocket connection...");
                wsClient.shutdown();
                
                // 刷新并关闭 Kafka Producer
                logger.info("Flushing and closing Kafka Producer...");
                kafkaProducer.flush();
                kafkaProducer.close();
                
                // 打印最终统计
                logger.info("========================================");
                logger.info("Final Statistics:");
                logger.info("  WebSocket Messages: {}", wsClient.getMessageCount());
                logger.info("  WebSocket Errors: {}", wsClient.getErrorCount());
                logger.info("  Kafka Success: {}", kafkaProducer.getSuccessCount());
                logger.info("  Kafka Failure: {}", kafkaProducer.getFailureCount());
                logger.info("========================================");
                logger.info("Data collector stopped");
                logger.info("========================================");
                
            } catch (Exception e) {
                logger.error("Error during shutdown", e);
            }
        }));
    }
    
    /**
     * 启动统计信息线程
     */
    private static void startStatisticsThread(OKXWebSocketClient wsClient, KafkaProducerManager kafkaProducer) {
        Thread statsThread = new Thread(() -> {
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    Thread.sleep(60000); // 每分钟打印一次
                    
                    logger.info("========================================");
                    logger.info("Statistics (1 minute interval):");
                    logger.info("  WebSocket Status: {}", wsClient.isConnected() ? "Connected" : "Disconnected");
                    logger.info("  Messages Received: {}", wsClient.getMessageCount());
                    logger.info("  WebSocket Errors: {}", wsClient.getErrorCount());
                    logger.info("  Kafka Success: {}", kafkaProducer.getSuccessCount());
                    logger.info("  Kafka Failure: {}", kafkaProducer.getFailureCount());
                    logger.info("========================================");
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
        
        statsThread.setDaemon(true);
        statsThread.setName("statistics-thread");
        statsThread.start();
    }
}
