#!/bin/bash

# Kafka 命令测试脚本
# 用于验证 Kafka 命令是否可以正常执行

set -e

echo "=========================================="
echo "Kafka 命令测试"
echo "=========================================="

# 测试 1: 列出 Topic
echo ""
echo "测试 1: 列出所有 Topic"
docker exec kafka bash -c "JMX_PORT= kafka-topics --list --bootstrap-server localhost:9092"

# 测试 2: 查看 Topic 详情
echo ""
echo "测试 2: 查看 crypto_ticker Topic 详情"
docker exec kafka bash -c "JMX_PORT= kafka-topics --describe --topic crypto_ticker --bootstrap-server localhost:9092" || echo "Topic 不存在，这是正常的"

# 测试 3: 创建 Topic (如果不存在)
echo ""
echo "测试 3: 创建 crypto_ticker Topic"
docker exec kafka bash -c "JMX_PORT= kafka-topics \
    --create \
    --topic crypto_ticker \
    --bootstrap-server localhost:9092 \
    --partitions 4 \
    --replication-factor 1 \
    --config retention.ms=604800000 \
    --config segment.ms=3600000 \
    --config compression.type=lz4 \
    --if-not-exists"

# 测试 4: 再次列出 Topic
echo ""
echo "测试 4: 验证 Topic 创建成功"
docker exec kafka bash -c "JMX_PORT= kafka-topics --list --bootstrap-server localhost:9092"

echo ""
echo "=========================================="
echo "所有测试通过！✅"
echo "=========================================="
