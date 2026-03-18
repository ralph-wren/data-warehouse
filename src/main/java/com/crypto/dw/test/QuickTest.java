package com.crypto.dw.test;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.kafka.KafkaProducerManager;
import com.crypto.dw.collector.OKXWebSocketClient;

import java.util.Arrays;
import java.util.List;

/**
 * 快速测试程序
 * 测试 WebSocket 连接和 Kafka 写入
 */
public class QuickTest {
    
    public static void main(String[] args) throws Exception {
        System.out.println("==========================================");
        System.out.println("快速测试 - OKX WebSocket + Kafka");
        System.out.println("==========================================");
        System.out.println();
        
        // 加载配置
        System.out.println("1. 加载配置...");
        ConfigLoader config = ConfigLoader.getInstance();
        System.out.println("   ✓ 配置加载成功");
        System.out.println();
        
        // 创建 Kafka Producer
        System.out.println("2. 创建 Kafka Producer...");
        KafkaProducerManager kafkaProducer = new KafkaProducerManager(config);
        System.out.println("   ✓ Kafka Producer 创建成功");
        System.out.println("   Topic: " + kafkaProducer.getTopic());
        System.out.println();
        
        // 订阅交易对
        List<String> symbols = Arrays.asList("BTC-USDT");
        System.out.println("3. 订阅交易对: " + symbols);
        System.out.println();
        
        // 创建 WebSocket 客户端
        System.out.println("4. 创建 WebSocket 客户端...");
        OKXWebSocketClient wsClient = new OKXWebSocketClient(config, kafkaProducer, symbols);
        System.out.println("   ✓ WebSocket 客户端创建成功");
        System.out.println();
        
        // 连接 WebSocket
        System.out.println("5. 连接 OKX WebSocket...");
        wsClient.connectBlocking();
        
        if (wsClient.isConnected()) {
            System.out.println("   ✓ WebSocket 连接成功");
        } else {
            System.out.println("   ❌ WebSocket 连接失败");
            System.exit(1);
        }
        System.out.println();
        
        System.out.println("==========================================");
        System.out.println("数据采集已启动");
        System.out.println("运行 30 秒后自动停止...");
        System.out.println("==========================================");
        System.out.println();
        
        // 运行 30 秒
        for (int i = 1; i <= 30; i++) {
            Thread.sleep(1000);
            
            if (i % 5 == 0) {
                System.out.println("运行中... " + i + "秒");
                System.out.println("  WebSocket 消息: " + wsClient.getMessageCount());
                
                // 刷新 Kafka
                kafkaProducer.flush();
                Thread.sleep(500); // 等待异步完成
                
                System.out.println("  Kafka 成功: " + kafkaProducer.getSuccessCount());
                System.out.println("  Kafka 失败: " + kafkaProducer.getFailureCount());
                System.out.println();
            }
        }
        
        // 关闭连接
        System.out.println("==========================================");
        System.out.println("停止数据采集...");
        System.out.println("==========================================");
        System.out.println();
        
        wsClient.shutdown();
        kafkaProducer.flush();
        kafkaProducer.close();
        
        // 打印最终统计
        System.out.println("最终统计:");
        System.out.println("  WebSocket 消息: " + wsClient.getMessageCount());
        System.out.println("  WebSocket 错误: " + wsClient.getErrorCount());
        System.out.println("  Kafka 成功: " + kafkaProducer.getSuccessCount());
        System.out.println("  Kafka 失败: " + kafkaProducer.getFailureCount());
        System.out.println();
        
        if (kafkaProducer.getSuccessCount() > 0) {
            System.out.println("✓ 测试成功！数据已写入 Kafka");
            System.out.println();
            System.out.println("查看 Kafka 消息:");
            System.out.println("  bash test-kafka-consumer.sh");
        } else {
            System.out.println("❌ 测试失败：没有数据写入 Kafka");
        }
        
        System.out.println();
        System.out.println("==========================================");
        System.out.println("测试完成");
        System.out.println("==========================================");
        
        System.exit(0);
    }
}
