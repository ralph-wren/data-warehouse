#!/bin/bash

# Kafka 设置脚本
# 用于创建 Topic 和配置 Kafka

set -e

echo "=========================================="
echo "Kafka Setup Script"
echo "=========================================="

# 等待 Kafka 启动
echo "等待 Kafka 启动..."
sleep 10

# 检查 Kafka 是否运行
if ! docker ps | grep -q kafka; then
    echo "错误: Kafka 容器未运行"
    echo "请先运行: docker-compose up -d"
    exit 1
fi

echo "Kafka 容器运行正常"

# 创建 crypto_ticker Topic
echo ""
echo "创建 crypto_ticker Topic..."
# Kafka 命令在 /usr/bin/ 目录下，没有 .sh 后缀
# 使用 env -i 清除所有环境变量，只保留必要的 PATH，避免 JMX 端口冲突
docker exec kafka bash -c "env -i PATH=/usr/bin kafka-topics \
    --create \
    --topic crypto_ticker \
    --bootstrap-server localhost:9092 \
    --partitions 4 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --config segment.ms=3600000 \
    --config compression.type=lz4 \
    --if-not-exists"

echo "crypto_ticker Topic 创建成功"

# 列出所有 Topic
echo ""
echo "当前 Kafka Topic 列表:"
docker exec kafka bash -c "env -i PATH=/usr/bin kafka-topics \
    --list \
    --bootstrap-server localhost:9092"

# 查看 Topic 详情
echo ""
echo "crypto_ticker Topic 详情:"
docker exec kafka bash -c "env -i PATH=/usr/bin kafka-topics \
    --describe \
    --topic crypto_ticker \
    --bootstrap-server localhost:9092"

echo ""
echo "=========================================="
echo "Kafka 设置完成!"
echo "=========================================="
echo ""
echo "Kafka 连接信息:"
echo "  Bootstrap Servers: localhost:9092"
echo "  Topic: crypto_ticker"
echo "  Partitions: 4"
echo "  Replication Factor: 1"
echo ""
echo "测试命令:"
echo "  # 生产消息"
echo "  docker exec -it kafka /opt/kafka/bin/kafka-console-producer.sh \\"
echo "    --bootstrap-server localhost:9092 \\"
echo "    --topic crypto_ticker"
echo ""
echo "  # 消费消息"
echo "  docker exec -it kafka /opt/kafka/bin/kafka-console-consumer.sh \\"
echo "    --bootstrap-server localhost:9092 \\"
echo "    --topic crypto_ticker \\"
echo "    --from-beginning"
echo ""
