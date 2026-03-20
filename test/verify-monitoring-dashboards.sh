#!/bin/bash
# 验证 Kafka 和 Doris 监控面板数据
# 检查 Prometheus 指标和 Grafana 面板

echo "=========================================="
echo "监控面板数据验证"
echo "=========================================="
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 检查 Prometheus 是否有数据
echo "1. 检查 Prometheus 指标"
echo "----------------------------------------"

# Kafka 指标
echo "Kafka 指标:"
KAFKA_BROKERS=$(curl -s "http://localhost:9090/api/v1/query?query=kafka_brokers" | python -m json.tool 2>/dev/null | grep '"value"' | awk -F'"' '{print $4}')
if [ -n "$KAFKA_BROKERS" ]; then
    echo -e "  ${GREEN}✓${NC} kafka_brokers = $KAFKA_BROKERS"
else
    echo -e "  ${RED}✗${NC} kafka_brokers 无数据"
fi

KAFKA_TOPICS=$(curl -s "http://localhost:9090/api/v1/query?query=count(kafka_topic_partitions)" | python -m json.tool 2>/dev/null | grep '"value"' | awk -F'"' '{print $4}')
if [ -n "$KAFKA_TOPICS" ]; then
    echo -e "  ${GREEN}✓${NC} kafka topics = $KAFKA_TOPICS"
else
    echo -e "  ${RED}✗${NC} kafka topics 无数据"
fi

echo ""
echo "Doris FE 指标:"
DORIS_HEAP=$(curl -s "http://localhost:9090/api/v1/query?query=jvm_heap_size_bytes{type=\"used\"}" | python -m json.tool 2>/dev/null | grep '"value"' | head -1 | awk -F'"' '{print $4}')
if [ -n "$DORIS_HEAP" ]; then
    HEAP_MB=$((DORIS_HEAP / 1024 / 1024))
    echo -e "  ${GREEN}✓${NC} jvm_heap_size_bytes (used) = ${HEAP_MB} MB"
else
    echo -e "  ${RED}✗${NC} jvm_heap_size_bytes 无数据"
fi

echo ""

# 2. 检查 Grafana 数据源
echo "2. 检查 Grafana 数据源"
echo "----------------------------------------"

DATASOURCE=$(curl -s -u admin:admin http://localhost:3000/api/datasources 2>/dev/null | python -m json.tool 2>/dev/null | grep '"name"' | grep -i prometheus)
if [ -n "$DATASOURCE" ]; then
    echo -e "${GREEN}✓${NC} Prometheus 数据源已配置"
else
    echo -e "${RED}✗${NC} Prometheus 数据源未配置"
fi

echo ""

# 3. 检查 Grafana 面板
echo "3. 检查 Grafana 面板"
echo "----------------------------------------"

# 检查 Kafka 面板
if [ -f "monitoring/grafana/dashboards/kafka-monitoring.json" ]; then
    echo -e "${GREEN}✓${NC} Kafka 监控面板文件存在"
else
    echo -e "${RED}✗${NC} Kafka 监控面板文件不存在"
fi

# 检查 Doris 面板
if [ -f "monitoring/grafana/dashboards/doris-monitoring.json" ]; then
    echo -e "${GREEN}✓${NC} Doris 监控面板文件存在"
else
    echo -e "${RED}✗${NC} Doris 监控面板文件不存在"
fi

echo ""

# 4. 访问说明
echo "=========================================="
echo "访问监控面板"
echo "=========================================="
echo ""
echo "Grafana: http://localhost:3000"
echo "  用户名: admin"
echo "  密码: admin"
echo ""
echo "监控面板:"
echo "  - Flink 监控: http://localhost:3000/d/flink-monitoring"
echo "  - Kafka 监控: http://localhost:3000/d/kafka-monitoring"
echo "  - Doris 监控: http://localhost:3000/d/doris-monitoring"
echo ""
echo "Prometheus: http://localhost:9090"
echo ""

# 5. 故障排查提示
echo "=========================================="
echo "故障排查"
echo "=========================================="
echo ""
echo "如果 Grafana 面板显示 'No data':"
echo ""
echo "1. 等待 15-30 秒让 Prometheus 抓取数据"
echo ""
echo "2. 检查 Prometheus Targets:"
echo "   http://localhost:9090/targets"
echo "   确保 kafka 和 doris-fe/doris-be 状态为 UP"
echo ""
echo "3. 在 Prometheus 中手动查询:"
echo "   http://localhost:9090/graph"
echo "   查询: kafka_brokers"
echo "   查询: jvm_heap_size_bytes"
echo ""
echo "4. 刷新 Grafana 面板:"
echo "   点击面板右上角的刷新按钮"
echo "   或者调整时间范围（如：Last 5 minutes）"
echo ""
echo "5. 检查 Grafana 数据源:"
echo "   http://localhost:3000/datasources"
echo "   点击 Prometheus 数据源"
echo "   点击 'Test' 按钮，应该显示 'Data source is working'"
echo ""

echo "=========================================="
echo "验证完成"
echo "=========================================="
