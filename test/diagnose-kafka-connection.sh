#!/bin/bash

# Kafka 连接诊断脚本

echo "=========================================="
echo "Kafka 连接诊断"
echo "=========================================="
echo ""

# 1. 检查 Kafka 容器状态
echo "1. Kafka 容器状态:"
if docker ps | grep -q kafka; then
    echo "   ✅ Kafka 容器运行中"
    docker ps --format "   {{.Names}}: {{.Status}}" | grep kafka
else
    echo "   ❌ Kafka 容器未运行"
    exit 1
fi
echo ""

# 2. 检查 Kafka 健康状态
echo "2. Kafka 健康状态:"
if docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1; then
    echo "   ✅ Kafka broker 响应正常"
else
    echo "   ❌ Kafka broker 无响应"
    exit 1
fi
echo ""

# 3. 检查 Topic
echo "3. Kafka Topic:"
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 | while read topic; do
    echo "   - $topic"
done
echo ""

# 4. 检查 Flink 容器状态
echo "4. Flink 容器状态:"
if docker ps | grep -q flink-jobmanager; then
    echo "   ✅ Flink JobManager 运行中"
else
    echo "   ❌ Flink JobManager 未运行"
fi

if docker ps | grep -q flink-taskmanager; then
    echo "   ✅ Flink TaskManager 运行中"
else
    echo "   ❌ Flink TaskManager 未运行"
fi
echo ""

# 5. 检查网络连接
echo "5. 网络连接测试:"
echo "   Flink JobManager 的网络:"
docker inspect flink-jobmanager --format '   {{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}' 2>/dev/null || echo "   ❌ 无法获取网络信息"

echo "   Kafka 的网络:"
docker inspect kafka --format '   {{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}' 2>/dev/null || echo "   ❌ 无法获取网络信息"

echo ""
echo "   测试 Flink -> Kafka 连接:"
if docker exec flink-jobmanager bash -c "timeout 5 bash -c '</dev/tcp/kafka/9092'" 2>/dev/null; then
    echo "   ✅ Flink 可以连接到 Kafka (kafka:9092)"
else
    echo "   ❌ Flink 无法连接到 Kafka (kafka:9092)"
fi
echo ""

# 6. 检查配置文件
echo "6. 配置文件检查:"
echo "   application-dev.yml (本地环境):"
grep -A 1 "bootstrap-servers" src/main/resources/config/application-dev.yml 2>/dev/null | grep -v "^--$" || echo "   ❌ 文件不存在"

echo ""
echo "   application-docker.yml (Docker 环境):"
grep -A 1 "bootstrap-servers" src/main/resources/config/application-docker.yml 2>/dev/null | grep -v "^--$" || echo "   ❌ 文件不存在"
echo ""

# 7. 建议
echo "=========================================="
echo "诊断建议"
echo "=========================================="
echo ""
echo "如果 Flink 作业无法连接 Kafka，请检查："
echo ""
echo "1. 运行环境:"
echo "   - 本地运行: 使用 localhost:9092"
echo "   - Docker 运行: 使用 kafka:9092"
echo ""
echo "2. 配置文件:"
echo "   - 确保使用正确的配置文件 (--APP_ENV dev 或 docker)"
echo "   - 检查 bootstrap-servers 配置"
echo ""
echo "3. 网络连接:"
echo "   - 确保 Flink 和 Kafka 在同一网络中"
echo "   - 检查防火墙设置"
echo ""
echo "4. Kafka 状态:"
echo "   - 确保 Kafka 完全启动（等待 30 秒）"
echo "   - 检查 Kafka 日志: docker logs kafka"
echo ""

