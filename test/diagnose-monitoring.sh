#!/bin/bash
# 诊断 Kafka 和 Doris 监控问题
# 检查每个环节是否正常

echo "=========================================="
echo "监控系统诊断"
echo "=========================================="
echo ""

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 1. 检查监控服务是否运行
echo "1. 检查监控服务状态"
echo "----------------------------------------"

# Prometheus
if curl -s http://localhost:9090/-/healthy > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Prometheus 运行正常 (http://localhost:9090)"
else
    echo -e "${RED}✗${NC} Prometheus 未运行"
    exit 1
fi

# Grafana
if curl -s http://localhost:3000/api/health > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Grafana 运行正常 (http://localhost:3000)"
else
    echo -e "${RED}✗${NC} Grafana 未运行"
    exit 1
fi

# Kafka Exporter
if curl -s http://localhost:9308/metrics > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Kafka Exporter 运行正常 (http://localhost:9308)"
else
    echo -e "${RED}✗${NC} Kafka Exporter 未运行"
    echo -e "${YELLOW}提示: 检查 docker logs kafka-exporter${NC}"
fi

# MySQL Exporter
if curl -s http://localhost:9104/metrics > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} MySQL Exporter 运行正常 (http://localhost:9104)"
else
    echo -e "${RED}✗${NC} MySQL Exporter 未运行"
    echo -e "${YELLOW}提示: 检查 docker logs mysql-exporter${NC}"
fi

echo ""
echo "2. 检查 Kafka 和 Doris 服务"
echo "----------------------------------------"

# Kafka
if netstat -an 2>/dev/null | grep -q ":9092.*LISTEN" || lsof -i :9092 > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Kafka 运行正常 (端口 9092)"
else
    echo -e "${RED}✗${NC} Kafka 未运行"
    echo -e "${YELLOW}提示: 启动 Kafka 服务${NC}"
fi

# Doris
if mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" > /dev/null 2>&1; then
    echo -e "${GREEN}✓${NC} Doris 运行正常 (端口 9030)"
else
    echo -e "${RED}✗${NC} Doris 未运行或无法连接"
    echo -e "${YELLOW}提示: bash start-doris-for-flink.sh${NC}"
fi

echo ""
echo "3. 检查 Exporter 指标"
echo "----------------------------------------"

# Kafka Exporter 指标
echo "Kafka Exporter:"
KAFKA_METRICS=$(curl -s http://localhost:9308/metrics 2>/dev/null)
if [ -n "$KAFKA_METRICS" ]; then
    KAFKA_BROKERS=$(echo "$KAFKA_METRICS" | grep "^kafka_brokers " | awk '{print $2}')
    KAFKA_UP=$(echo "$KAFKA_METRICS" | grep "^kafka_exporter_build_info" | wc -l)
    
    if [ "$KAFKA_UP" -gt 0 ]; then
        echo -e "  ${GREEN}✓${NC} Exporter 正常工作"
        if [ -n "$KAFKA_BROKERS" ]; then
            echo -e "  ${GREEN}✓${NC} Kafka Brokers: $KAFKA_BROKERS"
        else
            echo -e "  ${RED}✗${NC} 无法获取 Kafka Brokers 信息"
            echo -e "  ${YELLOW}可能原因: Kafka 未运行或 Exporter 无法连接${NC}"
        fi
    else
        echo -e "  ${RED}✗${NC} Exporter 未正常工作"
    fi
else
    echo -e "  ${RED}✗${NC} 无法获取 Exporter 指标"
fi

echo ""
echo "MySQL Exporter (Doris):"
MYSQL_METRICS=$(curl -s http://localhost:9104/metrics 2>/dev/null)
if [ -n "$MYSQL_METRICS" ]; then
    MYSQL_UP=$(echo "$MYSQL_METRICS" | grep "^mysql_up " | awk '{print $2}')
    MYSQL_SCRAPE=$(echo "$MYSQL_METRICS" | grep "^mysql_exporter_scrapes_total" | wc -l)
    
    if [ "$MYSQL_SCRAPE" -gt 0 ]; then
        echo -e "  ${GREEN}✓${NC} Exporter 正常工作"
        if [ "$MYSQL_UP" = "1" ]; then
            echo -e "  ${GREEN}✓${NC} Doris 连接成功"
            MYSQL_CONN=$(echo "$MYSQL_METRICS" | grep "^mysql_global_status_threads_connected " | awk '{print $2}')
            echo -e "  ${GREEN}✓${NC} 当前连接数: $MYSQL_CONN"
        else
            echo -e "  ${RED}✗${NC} 无法连接 Doris"
            echo -e "  ${YELLOW}可能原因: Doris 未运行或密码错误${NC}"
        fi
    else
        echo -e "  ${RED}✗${NC} Exporter 未正常工作"
    fi
else
    echo -e "  ${RED}✗${NC} 无法获取 Exporter 指标"
fi

echo ""
echo "4. 检查 Prometheus Targets"
echo "----------------------------------------"

TARGETS=$(curl -s http://localhost:9090/api/v1/targets 2>/dev/null)
if [ -n "$TARGETS" ]; then
    # Kafka target
    KAFKA_TARGET=$(echo "$TARGETS" | jq -r '.data.activeTargets[] | select(.labels.job=="kafka") | .health' 2>/dev/null)
    if [ "$KAFKA_TARGET" = "up" ]; then
        echo -e "${GREEN}✓${NC} Kafka target: UP"
    else
        echo -e "${RED}✗${NC} Kafka target: $KAFKA_TARGET"
        KAFKA_ERROR=$(echo "$TARGETS" | jq -r '.data.activeTargets[] | select(.labels.job=="kafka") | .lastError' 2>/dev/null)
        echo -e "  ${YELLOW}错误: $KAFKA_ERROR${NC}"
    fi
    
    # Doris target
    DORIS_TARGET=$(echo "$TARGETS" | jq -r '.data.activeTargets[] | select(.labels.job=="doris-mysql") | .health' 2>/dev/null)
    if [ "$DORIS_TARGET" = "up" ]; then
        echo -e "${GREEN}✓${NC} Doris target: UP"
    else
        echo -e "${RED}✗${NC} Doris target: $DORIS_TARGET"
        DORIS_ERROR=$(echo "$TARGETS" | jq -r '.data.activeTargets[] | select(.labels.job=="doris-mysql") | .lastError' 2>/dev/null)
        echo -e "  ${YELLOW}错误: $DORIS_ERROR${NC}"
    fi
else
    echo -e "${RED}✗${NC} 无法获取 Prometheus targets"
fi

echo ""
echo "5. 查询 Prometheus 指标"
echo "----------------------------------------"

# Kafka 指标
echo "Kafka 指标:"
KAFKA_QUERY=$(curl -s "http://localhost:9090/api/v1/query?query=kafka_brokers" 2>/dev/null)
KAFKA_RESULT=$(echo "$KAFKA_QUERY" | jq -r '.data.result | length' 2>/dev/null)
if [ "$KAFKA_RESULT" -gt 0 ]; then
    echo -e "  ${GREEN}✓${NC} 查询到 Kafka 指标"
    echo "$KAFKA_QUERY" | jq -r '.data.result[] | "    Brokers: \(.value[1])"' 2>/dev/null
else
    echo -e "  ${RED}✗${NC} 未查询到 Kafka 指标"
    echo -e "  ${YELLOW}可能原因: Prometheus 还未抓取到数据，等待 15-30 秒${NC}"
fi

echo ""
echo "Doris 指标:"
DORIS_QUERY=$(curl -s "http://localhost:9090/api/v1/query?query=mysql_up" 2>/dev/null)
DORIS_RESULT=$(echo "$DORIS_QUERY" | jq -r '.data.result | length' 2>/dev/null)
if [ "$DORIS_RESULT" -gt 0 ]; then
    echo -e "  ${GREEN}✓${NC} 查询到 Doris 指标"
    DORIS_UP_VALUE=$(echo "$DORIS_QUERY" | jq -r '.data.result[0].value[1]' 2>/dev/null)
    if [ "$DORIS_UP_VALUE" = "1" ]; then
        echo -e "    ${GREEN}✓${NC} Doris 状态: UP"
    else
        echo -e "    ${RED}✗${NC} Doris 状态: DOWN"
    fi
else
    echo -e "  ${RED}✗${NC} 未查询到 Doris 指标"
    echo -e "  ${YELLOW}可能原因: Prometheus 还未抓取到数据，等待 15-30 秒${NC}"
fi

echo ""
echo "6. 检查 Docker 容器"
echo "----------------------------------------"

# 检查 Kafka Exporter 容器
if docker ps | grep -q kafka-exporter; then
    echo -e "${GREEN}✓${NC} kafka-exporter 容器运行中"
else
    echo -e "${RED}✗${NC} kafka-exporter 容器未运行"
    echo -e "${YELLOW}提示: docker-compose -f docker-compose-monitoring.yml up -d kafka-exporter${NC}"
fi

# 检查 MySQL Exporter 容器
if docker ps | grep -q mysql-exporter; then
    echo -e "${GREEN}✓${NC} mysql-exporter 容器运行中"
else
    echo -e "${RED}✗${NC} mysql-exporter 容器未运行"
    echo -e "${YELLOW}提示: docker-compose -f docker-compose-monitoring.yml up -d mysql-exporter${NC}"
fi

echo ""
echo "7. 查看 Exporter 日志（最后 10 行）"
echo "----------------------------------------"

echo "Kafka Exporter 日志:"
docker logs --tail 10 kafka-exporter 2>&1 | sed 's/^/  /'

echo ""
echo "MySQL Exporter 日志:"
docker logs --tail 10 mysql-exporter 2>&1 | sed 's/^/  /'

echo ""
echo "=========================================="
echo "诊断总结"
echo "=========================================="
echo ""

# 给出建议
echo "常见问题和解决方法:"
echo ""
echo "1. 如果 Kafka Exporter 无法连接 Kafka:"
echo "   - 检查 Kafka 是否运行: netstat -an | grep 9092"
echo "   - 检查 host.docker.internal 是否可解析"
echo "   - 查看详细日志: docker logs kafka-exporter"
echo ""
echo "2. 如果 MySQL Exporter 无法连接 Doris:"
echo "   - 检查 Doris 是否运行: mysql -h 127.0.0.1 -P 9030 -u root"
echo "   - 检查 Doris 密码是否正确"
echo "   - 查看详细日志: docker logs mysql-exporter"
echo ""
echo "3. 如果 Prometheus 无法抓取数据:"
echo "   - 访问 http://localhost:9090/targets 查看详细错误"
echo "   - 等待 15-30 秒让 Prometheus 完成首次抓取"
echo ""
echo "4. 如果 Grafana 面板显示 'No data':"
echo "   - 确认 Prometheus 已经有数据（步骤 5）"
echo "   - 刷新 Grafana 面板"
echo "   - 检查面板的时间范围设置"
echo ""
echo "快速修复命令:"
echo "  bash stop-monitoring.sh && bash start-monitoring.sh"
echo ""
