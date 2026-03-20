#!/bin/bash

# Kafka JMX 监控测试脚本
# 用于验证 JMX Exporter 是否正常工作

set -e

echo "=========================================="
echo "Kafka JMX 监控测试"
echo "=========================================="

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试结果统计
PASSED=0
FAILED=0

# 测试函数
test_endpoint() {
    local name=$1
    local url=$2
    local expected=$3
    
    echo ""
    echo "测试: $name"
    echo "URL: $url"
    
    if curl -s "$url" | grep -q "$expected"; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASSED++))
        return 0
    else
        echo -e "${RED}✗ 失败${NC}"
        ((FAILED++))
        return 1
    fi
}

# 步骤 1: 检查 Kafka 是否运行
echo ""
echo "步骤 1: 检查 Kafka 容器..."
if docker ps | grep -q kafka; then
    echo -e "${GREEN}✓ Kafka 容器运行正常${NC}"
else
    echo -e "${RED}✗ Kafka 容器未运行${NC}"
    echo "请先运行: ./restart-kafka-with-jmx.sh"
    exit 1
fi

# 步骤 2: 检查 JMX 端口
echo ""
echo "步骤 2: 检查 JMX 端口 9999..."
if netstat -ano | grep -q 9999; then
    echo -e "${GREEN}✓ JMX 端口 9999 已开放${NC}"
else
    echo -e "${RED}✗ JMX 端口 9999 未开放${NC}"
    echo "Kafka 可能未启用 JMX，请运行: ./restart-kafka-with-jmx.sh"
    exit 1
fi

# 步骤 3: 检查监控服务
echo ""
echo "步骤 3: 检查监控服务..."
if docker ps | grep -q kafka-jmx-exporter; then
    echo -e "${GREEN}✓ JMX Exporter 运行正常${NC}"
else
    echo -e "${RED}✗ JMX Exporter 未运行${NC}"
    echo "请运行: docker-compose -f docker-compose-monitoring.yml up -d"
    exit 1
fi

# 步骤 4: 测试 JMX Exporter 端点
echo ""
echo "步骤 4: 测试 JMX Exporter 指标..."
test_endpoint "JMX Exporter 可访问性" "http://localhost:5556/metrics" "kafka_server"

# 步骤 5: 测试具体的 JMX 指标
echo ""
echo "步骤 5: 测试具体的 JMX 指标..."

# 消息流入速率
test_endpoint "消息流入速率指标" "http://localhost:5556/metrics" "kafka_server_brokertopicmetrics_messagesinpersec_total"

# 字节吞吐量
test_endpoint "字节流入速率指标" "http://localhost:5556/metrics" "kafka_server_brokertopicmetrics_bytesinpersec_total"
test_endpoint "字节流出速率指标" "http://localhost:5556/metrics" "kafka_server_brokertopicmetrics_bytesoutpersec_total"

# 分区和副本
test_endpoint "分区数量指标" "http://localhost:5556/metrics" "kafka_server_replicamanager_partitioncount"
test_endpoint "Leader 数量指标" "http://localhost:5556/metrics" "kafka_server_replicamanager_leadercount"
test_endpoint "未充分复制分区指标" "http://localhost:5556/metrics" "kafka_server_replicamanager_underreplicatedpartitions"

# 请求性能
test_endpoint "请求总时间指标" "http://localhost:5556/metrics" "kafka_network_requestmetrics_totaltimems"
test_endpoint "请求队列时间指标" "http://localhost:5556/metrics" "kafka_network_requestmetrics_requestqueuetimems"

# 控制器
test_endpoint "活跃控制器指标" "http://localhost:5556/metrics" "kafka_controller_kafkacontroller_activecontrollercount"
test_endpoint "离线分区指标" "http://localhost:5556/metrics" "kafka_controller_kafkacontroller_offlinepartitionscount"

# JVM 指标
test_endpoint "JVM CPU 使用率指标" "http://localhost:5556/metrics" "jvm_process_cpu_load"
test_endpoint "JVM 内存指标" "http://localhost:5556/metrics" "jvm_memory_heap_used"
test_endpoint "JVM GC 指标" "http://localhost:5556/metrics" "jvm_gc_collection_count_total"

# 步骤 6: 检查 Prometheus 是否抓取到指标
echo ""
echo "步骤 6: 检查 Prometheus 是否抓取到 JMX 指标..."
sleep 2
test_endpoint "Prometheus 中的 JMX 指标" "http://localhost:9090/api/v1/query?query=kafka_server_brokertopicmetrics_messagesinpersec_total" "success"

# 步骤 7: 显示一些实际指标值
echo ""
echo "步骤 7: 显示实际指标值..."
echo ""
echo "=== Kafka 集群状态 ==="
curl -s "http://localhost:9090/api/v1/query?query=kafka_server_replicamanager_partitioncount" | \
    grep -o '"value":\[[^]]*\]' | sed 's/"value":\[.*,"\(.*\)"\]/分区总数: \1/'

curl -s "http://localhost:9090/api/v1/query?query=kafka_server_replicamanager_leadercount" | \
    grep -o '"value":\[[^]]*\]' | sed 's/"value":\[.*,"\(.*\)"\]/Leader 分区数: \1/'

curl -s "http://localhost:9090/api/v1/query?query=kafka_server_replicamanager_underreplicatedpartitions" | \
    grep -o '"value":\[[^]]*\]' | sed 's/"value":\[.*,"\(.*\)"\]/未充分复制的分区: \1/'

echo ""
echo "=== 消息吞吐量 (最近 1 分钟平均) ==="
curl -s "http://localhost:9090/api/v1/query?query=rate(kafka_server_brokertopicmetrics_messagesinpersec_total[1m])" | \
    grep -o '"value":\[[^]]*\]' | head -1 | sed 's/"value":\[.*,"\(.*\)"\]/消息流入速率: \1 msg\/s/'

curl -s "http://localhost:9090/api/v1/query?query=rate(kafka_server_brokertopicmetrics_bytesinpersec_total[1m])" | \
    grep -o '"value":\[[^]]*\]' | head -1 | sed 's/"value":\[.*,"\(.*\)"\]/字节流入速率: \1 bytes\/s/'

# 测试总结
echo ""
echo "=========================================="
echo "测试总结"
echo "=========================================="
echo -e "通过: ${GREEN}$PASSED${NC}"
echo -e "失败: ${RED}$FAILED${NC}"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ 所有测试通过!${NC}"
    echo ""
    echo "下一步:"
    echo "  1. 在 Grafana 中导入完整版监控面板"
    echo "     文件: monitoring/grafana/dashboards/kafka-monitoring-advanced.json"
    echo "  2. 访问 Grafana: http://localhost:3000"
    echo "  3. 导航到 Dashboards → Import"
    echo "  4. 上传 kafka-monitoring-advanced.json"
    echo "  5. 选择 Prometheus 数据源"
    echo "  6. 点击 Import"
    echo ""
    exit 0
else
    echo -e "${RED}✗ 部分测试失败${NC}"
    echo ""
    echo "故障排查:"
    echo "  1. 检查 Kafka 是否启用了 JMX:"
    echo "     docker exec kafka env | grep JMX"
    echo ""
    echo "  2. 检查 JMX Exporter 日志:"
    echo "     docker logs kafka-jmx-exporter"
    echo ""
    echo "  3. 重启 Kafka 并启用 JMX:"
    echo "     ./restart-kafka-with-jmx.sh"
    echo ""
    echo "  4. 重启监控服务:"
    echo "     docker-compose -f docker-compose-monitoring.yml restart"
    echo ""
    exit 1
fi
