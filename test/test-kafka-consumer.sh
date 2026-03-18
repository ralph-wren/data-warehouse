#!/bin/bash

# Kafka 消费者测试脚本
# 用于验证数据采集器是否正常写入数据

echo "=========================================="
echo "Kafka 消费者测试"
echo "=========================================="
echo ""

# 检查 Kafka 是否运行
docker ps | grep kafka > /dev/null
if [ $? -ne 0 ]; then
    echo "❌ Kafka 容器未运行"
    echo "请先启动 Kafka: docker-compose up -d"
    exit 1
fi

echo "从 crypto_ticker Topic 消费消息..."
echo "按 Ctrl+C 停止"
echo "=========================================="
echo ""

# 消费消息
docker exec -it kafka kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic crypto_ticker \
    --from-beginning \
    --property print.key=true \
    --property key.separator=" => "
