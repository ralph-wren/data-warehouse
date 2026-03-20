#!/bin/bash

# Kafka 监控升级脚本
# 一键升级到 JMX Exporter 以获得详细监控

set -e

echo "=========================================="
echo "Kafka 监控系统升级"
echo "从基础监控升级到完整 JMX 监控"
echo "=========================================="

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m'

# 确认升级
echo ""
echo -e "${YELLOW}警告: 此操作将重启 Kafka 容器${NC}"
echo "Kafka 将会短暂不可用 (约 30 秒)"
echo ""
read -p "是否继续? (y/n) " -n 1 -r
echo
if [[ ! $REPLY =~ ^[Yy]$ ]]; then
    echo "升级已取消"
    exit 0
fi

# 步骤 1: 重启 Kafka 并启用 JMX
echo ""
echo "=========================================="
echo "步骤 1: 重启 Kafka 并启用 JMX"
echo "=========================================="
./restart-kafka-with-jmx.sh

# 步骤 2: 重启监控服务
echo ""
echo "=========================================="
echo "步骤 2: 重启监控服务"
echo "=========================================="
echo "停止现有监控服务..."
docker-compose -f docker-compose-monitoring.yml down

echo ""
echo "启动新的监控服务 (包含 JMX Exporter)..."
docker-compose -f docker-compose-monitoring.yml up -d

echo ""
echo "等待服务启动..."
sleep 10

# 步骤 3: 验证服务状态
echo ""
echo "=========================================="
echo "步骤 3: 验证服务状态"
echo "=========================================="

echo ""
echo "检查 Docker 容器..."
docker ps --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}" | grep -E "kafka|prometheus|grafana|exporter|pushgateway"

# 步骤 4: 测试 JMX 指标
echo ""
echo "=========================================="
echo "步骤 4: 测试 JMX 指标"
echo "=========================================="
echo "等待 JMX Exporter 连接到 Kafka..."
sleep 20

echo ""
echo "运行测试脚本..."
./test/test-kafka-jmx-monitoring.sh

# 步骤 5: 显示访问信息
echo ""
echo "=========================================="
echo "升级完成!"
echo "=========================================="
echo ""
echo "监控系统访问信息:"
echo "  Grafana:    http://localhost:3000"
echo "              用户名: admin"
echo "              密码: admin"
echo ""
echo "  Prometheus: http://localhost:9090"
echo ""
echo "  JMX Exporter: http://localhost:5556/metrics"
echo "  Kafka Exporter: http://localhost:9308/metrics"
echo ""
echo "可用的监控面板:"
echo "  1. Flink 监控 (已有)"
echo "  2. Kafka 监控 (简化版) - 基础指标"
echo "  3. Kafka 监控 (完整版) - 详细指标 ⭐ 新增"
echo "  4. Doris 监控 (已有)"
echo ""
echo "导入完整版 Kafka 监控面板:"
echo "  1. 访问 Grafana: http://localhost:3000"
echo "  2. 导航到 Dashboards → Import"
echo "  3. 点击 'Upload JSON file'"
echo "  4. 选择文件: monitoring/grafana/dashboards/kafka-monitoring-advanced.json"
echo "  5. 选择 Prometheus 数据源"
echo "  6. 点击 Import"
echo ""
echo "完整版面板包含以下监控:"
echo "  ✓ 集群概览 (Broker、分区、Leader、离线分区等)"
echo "  ✓ 消息吞吐量 (消息速率、字节吞吐量)"
echo "  ✓ 请求性能 (请求延迟、队列时间、失败率)"
echo "  ✓ 副本和分区 (ISR 状态、副本变化)"
echo "  ✓ JVM 和系统资源 (CPU、内存、GC)"
echo ""
echo "查看实时指标:"
echo "  # 消息流入速率"
echo "  curl 'http://localhost:9090/api/v1/query?query=rate(kafka_server_brokertopicmetrics_messagesinpersec_total[1m])'"
echo ""
echo "  # 请求延迟"
echo "  curl 'http://localhost:9090/api/v1/query?query=kafka_network_requestmetrics_totaltimems'"
echo ""
echo "  # JVM 内存使用"
echo "  curl 'http://localhost:9090/api/v1/query?query=jvm_memory_heap_used'"
echo ""
