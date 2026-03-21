#!/bin/bash

# Doris 监控测试脚本
# 用于验证 Doris 原生 Prometheus 指标是否正常工作

echo "=========================================="
echo "Doris 监控测试"
echo "=========================================="

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试结果统计
PASSED=0
FAILED=0

# 步骤 1: 检查 Doris 是否运行
echo ""
echo "步骤 1: 检查 Doris 容器..."
if docker ps | grep -q doris-fe && docker ps | grep -q doris-be; then
    echo -e "${GREEN}✓ Doris FE 和 BE 容器运行正常${NC}"
else
    echo -e "${RED}✗ Doris 容器未运行${NC}"
    echo "请先启动 Doris: bash start-doris-for-flink.sh"
    exit 1
fi

# 步骤 2: 检查 Doris FE metrics 端点
echo ""
echo "步骤 2: 检查 Doris FE metrics 端点..."
if curl -s http://127.0.0.1:8030/metrics | grep -q "doris_fe"; then
    echo -e "${GREEN}✓ Doris FE metrics 端点可访问${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ Doris FE metrics 端点不可访问${NC}"
    ((FAILED++))
fi

# 步骤 3: 检查 Doris BE metrics 端点
echo ""
echo "步骤 3: 检查 Doris BE metrics 端点..."
if curl -s http://127.0.0.1:8040/metrics | grep -q "doris_be"; then
    echo -e "${GREEN}✓ Doris BE metrics 端点可访问${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ Doris BE metrics 端点不可访问${NC}"
    ((FAILED++))
fi

# 步骤 4: 检查 Prometheus 是否抓取到 Doris FE 指标
echo ""
echo "步骤 4: 检查 Prometheus 中的 Doris FE 指标..."

# QPS
if curl -s "http://localhost:9090/api/v1/query?query=doris_fe_qps" | grep -q "success"; then
    echo -e "${GREEN}✓ doris_fe_qps 指标存在${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ doris_fe_qps 指标不存在${NC}"
    ((FAILED++))
fi

# 表数量
if curl -s "http://localhost:9090/api/v1/query?query=doris_fe_internal_table_num" | grep -q "success"; then
    echo -e "${GREEN}✓ doris_fe_internal_table_num 指标存在${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ doris_fe_internal_table_num 指标不存在${NC}"
    ((FAILED++))
fi

# 事务计数
if curl -s "http://localhost:9090/api/v1/query?query=doris_fe_txn_counter" | grep -q "success"; then
    echo -e "${GREEN}✓ doris_fe_txn_counter 指标存在${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ doris_fe_txn_counter 指标不存在${NC}"
    ((FAILED++))
fi

# JVM 内存
if curl -s "http://localhost:9090/api/v1/query?query=jvm_heap_size_bytes{job=\"doris-fe\"}" | grep -q "success"; then
    echo -e "${GREEN}✓ jvm_heap_size_bytes 指标存在${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ jvm_heap_size_bytes 指标不存在${NC}"
    ((FAILED++))
fi

# 步骤 5: 检查 Prometheus 中的 Doris BE 指标
echo ""
echo "步骤 5: 检查 Prometheus 中的 Doris BE 指标..."

# 磁盘容量
if curl -s "http://localhost:9090/api/v1/query?query=doris_be_disks_total_capacity" | grep -q "success"; then
    echo -e "${GREEN}✓ doris_be_disks_total_capacity 指标存在${NC}"
    ((PASSED++))
else
    echo -e "${RED}✗ doris_be_disks_total_capacity 指标不存在${NC}"
    ((FAILED++))
fi

# 步骤 6: 显示实际指标值
echo ""
echo "步骤 6: 显示实际指标值..."
echo ""
echo "=== Doris FE 状态 ==="

# 表数量
TABLE_NUM=$(curl -s "http://localhost:9090/api/v1/query?query=doris_fe_internal_table_num" | \
    python -c "import sys, json; data=json.load(sys.stdin); print(data['data']['result'][0]['value'][1] if data['data']['result'] else '0')" 2>/dev/null || echo "N/A")
echo "表数量: $TABLE_NUM"

# 数据库数量
DB_NUM=$(curl -s "http://localhost:9090/api/v1/query?query=doris_fe_internal_database_num" | \
    python -c "import sys, json; data=json.load(sys.stdin); print(data['data']['result'][0]['value'][1] if data['data']['result'] else '0')" 2>/dev/null || echo "N/A")
echo "数据库数量: $DB_NUM"

# Tablet 总数
TABLET_NUM=$(curl -s "http://localhost:9090/api/v1/query?query=doris_fe_tablet_status_count{type=\"total\"}" | \
    python -c "import sys, json; data=json.load(sys.stdin); print(data['data']['result'][0]['value'][1] if data['data']['result'] else '0')" 2>/dev/null || echo "N/A")
echo "Tablet 总数: $TABLET_NUM"

# QPS
QPS=$(curl -s "http://localhost:9090/api/v1/query?query=doris_fe_qps" | \
    python -c "import sys, json; data=json.load(sys.stdin); print(data['data']['result'][0]['value'][1] if data['data']['result'] else '0')" 2>/dev/null || echo "N/A")
echo "当前 QPS: $QPS"

echo ""
echo "=== Doris BE 状态 ==="

# BE 节点数
BE_NODES=$(curl -s "http://localhost:9090/api/v1/query?query=node_info{type=\"be_node_num\",state=\"alive\"}" | \
    python -c "import sys, json; data=json.load(sys.stdin); print(data['data']['result'][0]['value'][1] if data['data']['result'] else '0')" 2>/dev/null || echo "N/A")
echo "BE 节点数 (存活): $BE_NODES"

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
    echo "  1. 访问 Grafana: http://localhost:3000"
    echo "  2. 导航到 Dashboards"
    echo "  3. 查看 'Doris 监控 (原生指标)' 面板"
    echo "  4. 所有指标应该正常显示"
    echo ""
    exit 0
else
    echo -e "${RED}✗ 部分测试失败${NC}"
    echo ""
    echo "故障排查:"
    echo "  1. 检查 Doris 是否正常运行:"
    echo "     docker ps | grep doris"
    echo ""
    echo "  2. 检查 Doris FE metrics 端点:"
    echo "     curl http://127.0.0.1:8030/metrics | head -20"
    echo ""
    echo "  3. 检查 Prometheus 配置:"
    echo "     cat monitoring/prometheus/prometheus.yml | grep doris -A 5"
    echo ""
    echo "  4. 重启监控服务:"
    echo "     docker-compose -f docker-compose-monitoring.yml restart"
    echo ""
    exit 1
fi
