#!/bin/bash

# 检查所有服务状态的脚本

echo "=========================================="
echo "检查服务状态"
echo "=========================================="
echo ""

# 1. 检查 Kafka
echo "1. Kafka 状态:"
if docker ps | grep -q kafka; then
    echo "   ✅ Kafka 容器运行中"
    
    # 检查 Topic
    echo "   检查 Topic..."
    if docker exec kafka kafka-topics --list --bootstrap-server localhost:9092 2>/dev/null | grep -q crypto_ticker; then
        echo "   ✅ crypto_ticker Topic 存在"
    else
        echo "   ❌ crypto_ticker Topic 不存在"
    fi
else
    echo "   ❌ Kafka 容器未运行"
fi
echo ""

# 2. 检查 Doris
echo "2. Doris 状态:"
if docker ps | grep -q doris-fe; then
    echo "   ✅ Doris FE 运行中"
else
    echo "   ❌ Doris FE 未运行"
fi

if docker ps | grep -q doris-be; then
    echo "   ✅ Doris BE 运行中"
else
    echo "   ❌ Doris BE 未运行"
fi
echo ""

# 3. 检查 Flink
echo "3. Flink 状态:"
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

# 4. 检查监控系统
echo "4. 监控系统状态:"
if docker ps | grep -q prometheus; then
    echo "   ✅ Prometheus 运行中"
else
    echo "   ❌ Prometheus 未运行"
fi

if docker ps | grep -q grafana; then
    echo "   ✅ Grafana 运行中"
else
    echo "   ❌ Grafana 未运行"
fi
echo ""

# 5. 检查 StreamPark
echo "5. StreamPark 状态:"
if docker ps | grep -q streampark; then
    echo "   ✅ StreamPark 运行中"
else
    echo "   ❌ StreamPark 未运行"
fi
echo ""

echo "=========================================="
echo "服务访问地址"
echo "=========================================="
echo "Kafka:          localhost:9092"
echo "Doris FE:       localhost:9030 (MySQL)"
echo "Doris BE:       localhost:8040 (HTTP)"
echo "Flink WebUI:    http://localhost:8081"
echo "Prometheus:     http://localhost:9090"
echo "Grafana:        http://localhost:3000 (admin/admin)"
echo "StreamPark:     http://localhost:10000 (admin/streampark)"
echo ""

