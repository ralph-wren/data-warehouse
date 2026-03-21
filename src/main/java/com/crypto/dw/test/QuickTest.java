package com.crypto.dw.test;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.kafka.KafkaProducerManager;
import com.crypto.dw.collector.OKXWebSocketClient;
import lombok.extern.slf4j.Slf4j;

import java.util.Arrays;
import java.util.List;

/**
 * 快速测试程序
 * 测试 WebSocket 连接和 Kafka 写入
 */
@Slf4j
public class QuickTest {
    
    public static void main(String[] args) throws Exception {
        log.info("==========================================");
        log.info("快速测试 - OKX WebSocket + Kafka");
        log.info("==========================================");
        
        
        // 加载配置
        log.info("1. 加载配置...");
        ConfigLoader config = ConfigLoader.getInstance();
        log.info("   ✓ 配置加载成功");
        
        
        // 创建 Kafka Producer
        log.info("2. 创建 Kafka Producer...");
        KafkaProducerManager kafkaProducer = new KafkaProducerManager(config);
        log.info("   ✓ Kafka Producer 创建成功");
        log.info("   Topic: " + kafkaProducer.getTopic());
        
        
        // 订阅交易对
        List<String> symbols = Arrays.asList("BTC-USDT");
        log.info("3. 订阅交易对: " + symbols);
        
        
        // 创建 WebSocket 客户端
        log.info("4. 创建 WebSocket 客户端...");
        OKXWebSocketClient wsClient = new OKXWebSocketClient(config, kafkaProducer, symbols);
        log.info("   ✓ WebSocket 客户端创建成功");
        
        
        // 连接 WebSocket
        log.info("5. 连接 OKX WebSocket...");
        wsClient.connectBlocking();
        
        if (wsClient.isConnected()) {
            log.info("   ✓ WebSocket 连接成功");
        } else {
            log.info("   ❌ WebSocket 连接失败");
            System.exit(1);
        }
        
        
        log.info("==========================================");
        log.info("数据采集已启动");
        log.info("运行 30 秒后自动停止...");
        log.info("==========================================");
        
        
        // 运行 30 秒
        for (int i = 1; i <= 30; i++) {
            Thread.sleep(1000);
            
            if (i % 5 == 0) {
                log.info("运行中... " + i + "秒");
                log.info("  WebSocket 消息: " + wsClient.getMessageCount());
                
                // 刷新 Kafka
                kafkaProducer.flush();
                Thread.sleep(500); // 等待异步完成
                
                log.info("  Kafka 成功: " + kafkaProducer.getSuccessCount());
                log.info("  Kafka 失败: " + kafkaProducer.getFailureCount());
                
            }
        }
        
        // 关闭连接
        log.info("==========================================");
        log.info("停止数据采集...");
        log.info("==========================================");
        
        
        wsClient.shutdown();
        kafkaProducer.flush();
        kafkaProducer.close();
        
        // 打印最终统计
        log.info("最终统计:");
        log.info("  WebSocket 消息: " + wsClient.getMessageCount());
        log.info("  WebSocket 错误: " + wsClient.getErrorCount());
        log.info("  Kafka 成功: " + kafkaProducer.getSuccessCount());
        log.info("  Kafka 失败: " + kafkaProducer.getFailureCount());
        
        
        if (kafkaProducer.getSuccessCount() > 0) {
            log.info("✓ 测试成功！数据已写入 Kafka");
            
            log.info("查看 Kafka 消息:");
            log.info("  bash test-kafka-consumer.sh");
        } else {
            log.info("❌ 测试失败：没有数据写入 Kafka");
        }
        
        
        log.info("==========================================");
        log.info("测试完成");
        log.info("==========================================");
        
        System.exit(0);
    }
}
