#!/bin/bash

# 测试监控系统集成
# 验证 Prometheus、Grafana 和 Pushgateway 是否正常工作

echo "=========================================="
echo "测试监控系统集成"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试函数
test_service() {
    local name=$1
    local url=$2
    local expected=$3
    
    echo -n "测试 $name ... "
    
    response=$(curl -s -o /dev/null -w "%{http_code}" "$url" 2>/dev/null)
    
    if [ "$response" = "$expected" ]; then
        echo -e "${GREEN}✓ 成功${NC} (HTTP $response)"
        return 0
    else
        echo -e "${RED}✗ 失败${NC} (HTTP $response, 期望 $expected)"
        return 1
    fi
}

# 测试 Prometheus 指标查询
test_prometheus_query() {
    local query=$1
    local description=$2
    
    echo -n "测试 Prometheus 查询: $description ... "
    
    response=$(curl -s "http://localhost:9090/api/v1/query?query=$query" 2>/dev/null)
    
    if echo "$response" | grep -q '"status":"success"'; then
        result_count=$(echo "$response" | grep -o '"result":\[' | wc -l)
        echo -e "${GREEN}✓ 成功${NC} (查询成功)"
        return 0
    else
        echo -e "${RED}✗ 失败${NC} (查询失败)"
        return 1
    fi
}

# 1. 测试 Prometheus
echo "1. 测试 Prometheus"
test_service "Prometheus Web UI" "http://localhost:9090" "200"
test_service "Prometheus API" "http://localhost:9090/api/v1/status/config" "200"
test_service "Prometheus Targets" "http://localhost:9090/api/v1/targets" "200"
echo ""

# 2. 测试 Grafana
echo "2. 测试 Grafana"
test_service "Grafana Web UI" "http://localhost:3000" "200"
test_service "Grafana API" "http://localhost:3000/api/health" "200"
echo ""

# 3. 测试 Pushgateway
echo "3. 测试 Pushgateway"
test_service "Pushgateway Web UI" "http://localhost:9091" "200"
test_service "Pushgateway Metrics" "http://localhost:9091/metrics" "200"
echo ""

# 4. 测试 Flink Metrics（如果作业正在运行）
echo "4. 测试 Flink Metrics"
echo "检查 Pushgateway 中的 Flink 指标..."

# 获取 Pushgateway 中的所有指标
metrics=$(curl -s http://localhost:9091/metrics 2>/dev/null)

if echo "$metrics" | grep -q "flink_"; then
    echo -e "${GREEN}✓ 找到 Flink 指标${NC}"
    
    # 统计指标数量
    flink_metrics_count=$(echo "$metrics" | grep -c "^flink_")
    echo "  - Flink 指标数量: $flink_metrics_count"
    
    # 检查关键指标
    if echo "$metrics" | grep -q "flink_taskmanager_job_task_numRecordsInPerSecond"; then
        echo -e "  - ${GREEN}✓${NC} 找到输入速率指标"
    else
        echo -e "  - ${YELLOW}⚠${NC} 未找到输入速率指标"
    fi
    
    if echo "$metrics" | grep -q "flink_taskmanager_job_task_numRecordsOutPerSecond"; then
        echo -e "  - ${GREEN}✓${NC} 找到输出速率指标"
    else
        echo -e "  - ${YELLOW}⚠${NC} 未找到输出速率指标"
    fi
    
    if echo "$metrics" | grep -q "flink_taskmanager_Status_JVM_Memory_Heap_Used"; then
        echo -e "  - ${GREEN}✓${NC} 找到 JVM 内存指标"
    else
        echo -e "  - ${YELLOW}⚠${NC} 未找到 JVM 内存指标"
    fi
else
    echo -e "${YELLOW}⚠ 未找到 Flink 指标${NC}"
    echo "  提示: 请确保 Flink 作业正在运行"
fi
echo ""

# 5. 测试 Prometheus 查询（如果有数据）
echo "5. 测试 Prometheus 查询"
test_prometheus_query "up" "服务状态"
test_prometheus_query "flink_taskmanager_job_task_numRecordsInPerSecond" "Flink 输入速率"
echo ""

# 6. 检查 Grafana 数据源
echo "6. 检查 Grafana 数据源"
echo -n "测试 Grafana Prometheus 数据源 ... "

datasources=$(curl -s -u admin:admin "http://localhost:3000/api/datasources" 2>/dev/null)

if echo "$datasources" | grep -q '"type":"prometheus"'; then
    echo -e "${GREEN}✓ 成功${NC}"
    
    # 提取数据源名称
    datasource_name=$(echo "$datasources" | grep -o '"name":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "  - 数据源名称: $datasource_name"
else
    echo -e "${RED}✗ 失败${NC}"
    echo "  提示: 请检查 Grafana 数据源配置"
fi
echo ""

# 7. 检查 Grafana Dashboard
echo "7. 检查 Grafana Dashboard"
echo -n "测试 Flink Monitoring Dashboard ... "

dashboards=$(curl -s -u admin:admin "http://localhost:3000/api/search?type=dash-db" 2>/dev/null)

if echo "$dashboards" | grep -q "Flink Monitoring"; then
    echo -e "${GREEN}✓ 成功${NC}"
    
    # 提取 Dashboard UID
    dashboard_uid=$(echo "$dashboards" | grep -o '"uid":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "  - Dashboard UID: $dashboard_uid"
    echo "  - Dashboard URL: http://localhost:3000/d/$dashboard_uid/flink-monitoring"
else
    echo -e "${YELLOW}⚠ 未找到${NC}"
    echo "  提示: Dashboard 可能需要手动导入"
fi
echo ""

# 8. 测试 Flink Web UI（如果作业正在运行）
echo "8. 测试 Flink Web UI"
test_service "Flink ODS Job Web UI" "http://localhost:8081" "200"
test_service "Flink DWD Job Web UI" "http://localhost:8082" "200"
echo ""

# 总结
echo "=========================================="
echo "测试完成"
echo "=========================================="
echo ""
echo "访问地址:"
echo "  - Prometheus: http://localhost:9090"
echo "  - Grafana: http://localhost:3000 (admin/admin)"
echo "  - Pushgateway: http://localhost:9091"
echo "  - Flink ODS Job: http://localhost:8081"
echo "  - Flink DWD Job: http://localhost:8082"
echo ""
echo "提示:"
echo "  1. 如果 Flink 指标未显示，请确保 Flink 作业正在运行"
echo "  2. 指标推送间隔为 15 秒，请等待一段时间后再检查"
echo "  3. 如果 Grafana Dashboard 未显示，请手动导入 monitoring/grafana/dashboards/flink-monitoring.json"
echo ""
