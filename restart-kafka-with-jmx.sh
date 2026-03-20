#!/bin/bash

# Kafka 重启脚本 - 启用 JMX 监控
# 用于重启 Kafka 容器并启用 JMX 端口 9999

set -e

echo "=========================================="
echo "重启 Kafka 并启用 JMX 监控"
echo "=========================================="

# 停止现有的 Kafka 容器
echo ""
echo "步骤 1: 停止现有的 Kafka 容器..."
if docker ps -a | grep -q kafka; then
    docker stop kafka || true
    docker rm kafka || true
    echo "✓ Kafka 容器已停止并移除"
else
    echo "✓ 没有运行中的 Kafka 容器"
fi

# 启动新的 Kafka 容器 (启用 JMX)
echo ""
echo "步骤 2: 启动 Kafka 容器 (启用 JMX)..."
docker run -d \
  --name kafka \
  --hostname kafka \
  -p 9092:9092 \
  -p 9999:9999 \
  -e KAFKA_NODE_ID=1 \
  -e KAFKA_PROCESS_ROLES=broker,controller \
  -e KAFKA_LISTENERS=PLAINTEXT://0.0.0.0:9092,CONTROLLER://0.0.0.0:9093 \
  -e KAFKA_ADVERTISED_LISTENERS=PLAINTEXT://localhost:9092 \
  -e KAFKA_CONTROLLER_LISTENER_NAMES=CONTROLLER \
  -e KAFKA_LISTENER_SECURITY_PROTOCOL_MAP=CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT \
  -e KAFKA_CONTROLLER_QUORUM_VOTERS=1@localhost:9093 \
  -e KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_REPLICATION_FACTOR=1 \
  -e KAFKA_TRANSACTION_STATE_LOG_MIN_ISR=1 \
  -e KAFKA_LOG_DIRS=/var/lib/kafka/data \
  -e KAFKA_LOG_RETENTION_HOURS=168 \
  -e KAFKA_NUM_NETWORK_THREADS=3 \
  -e KAFKA_NUM_IO_THREADS=8 \
  -e CLUSTER_ID=MkU3OEVBNTcwNTJENDM2Qk \
  -e KAFKA_JMX_OPTS="-Dcom.sun.management.jmxremote -Dcom.sun.management.jmxremote.port=9999 -Dcom.sun.management.jmxremote.rmi.port=9999 -Dcom.sun.management.jmxremote.authenticate=false -Dcom.sun.management.jmxremote.ssl=false -Djava.rmi.server.hostname=localhost" \
  -e JMX_PORT=9999 \
  --restart unless-stopped \
  confluentinc/cp-kafka:7.6.0

echo "✓ Kafka 容器已启动"

# 等待 Kafka 启动
echo ""
echo "步骤 3: 等待 Kafka 启动..."
sleep 15

# 检查 Kafka 是否正常运行
echo ""
echo "步骤 4: 检查 Kafka 状态..."
if docker ps | grep -q kafka; then
    echo "✓ Kafka 容器运行正常"
else
    echo "✗ Kafka 容器启动失败"
    docker logs kafka
    exit 1
fi

# 检查 JMX 端口是否开放
echo ""
echo "步骤 5: 检查 JMX 端口..."
if netstat -ano | grep -q 9999; then
    echo "✓ JMX 端口 9999 已开放"
else
    echo "⚠ JMX 端口 9999 未开放，可能需要等待更长时间"
fi

# 重新创建 Topic
echo ""
echo "步骤 6: 重新创建 crypto_ticker Topic..."
sleep 5
# Kafka 命令在 /usr/bin/ 目录下，没有 .sh 后缀
# 设置 KAFKA_JMX_OPTS 为空，完全禁用 JMX，避免端口冲突
docker exec kafka bash -c "KAFKA_JMX_OPTS='' kafka-topics \
    --create \
    --topic crypto_ticker \
    --bootstrap-server localhost:9092 \
    --partitions 4 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --config segment.ms=3600000 \
    --config compression.type=lz4 \
    --if-not-exists"

echo "✓ Topic 创建成功"

# 列出所有 Topic
echo ""
echo "步骤 7: 验证 Topic..."
docker exec kafka bash -c "KAFKA_JMX_OPTS='' kafka-topics \
    --list \
    --bootstrap-server localhost:9092"

echo ""
echo "=========================================="
echo "Kafka 重启完成!"
echo "=========================================="
echo ""
echo "Kafka 连接信息:"
echo "  Bootstrap Servers: localhost:9092"
echo "  JMX Port: 9999"
echo "  Topic: crypto_ticker"
echo "  Partitions: 4"
echo ""
echo "下一步:"
echo "  1. 重启监控服务: docker-compose -f docker-compose-monitoring.yml up -d"
echo "  2. 等待 30 秒让 JMX Exporter 连接"
echo "  3. 验证 JMX 指标: curl http://localhost:5556/metrics | grep kafka_server"
echo "  4. 在 Grafana 中导入新的监控面板"
echo ""
