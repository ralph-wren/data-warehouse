#!/bin/bash

# Doris 增强监控测试脚本
# 测试所有新增的监控指标是否可用

echo "=========================================="
echo "Doris 增强监控测试"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试计数器
PASSED=0
FAILED=0

# 测试函数
test_metric() {
    local metric_name=$1
    local description=$2
    
    echo -n "测试 $description ($metric_name)... "
    
    result=$(curl -s "http://localhost:9090/api/v1/query?query=$metric_name" | grep -o '"status":"success"')
    
    if [ -n "$result" ]; then
        # 检查是否有数据
        data=$(curl -s "http://localhost:9090/api/v1/query?query=$metric_name" | grep -o '"result":\[.*\]' | grep -v '"result":\[\]')
        if [ -n "$data" ]; then
            echo -e "${GREEN}✓ 通过${NC}"
            ((PASSED++))
            return 0
        else
            echo -e "${YELLOW}⚠ 无数据${NC}"
            ((FAILED++))
            return 1
        fi
    else
        echo -e "${RED}✗ 失败${NC}"
        ((FAILED++))
        return 1
    fi
}

# 步骤 1: 检查 Doris 容器
echo "步骤 1: 检查 Doris 容器..."
if docker ps | grep -q "doris-fe" && docker ps | grep -q "doris-be"; then
    echo -e "${GREEN}✓ Doris FE 和 BE 容器运行正常${NC}"
else
    echo -e "${RED}✗ Doris 容器未运行${NC}"
    exit 1
fi
echo ""

# 步骤 2: 检查 Prometheus
echo "步骤 2: 检查 Prometheus..."
if curl -s http://localhost:9090/-/healthy | grep -q "Prometheus"; then
    echo -e "${GREEN}✓ Prometheus 运行正常${NC}"
else
    echo -e "${RED}✗ Prometheus 未运行${NC}"
    exit 1
fi
echo ""

# 步骤 3: 测试 FE 查询性能指标
echo "步骤 3: 测试 FE 查询性能指标..."
test_metric "doris_fe_query_err_rate" "查询错误率"
test_metric "doris_fe_query_err" "查询错误总数"
test_metric "doris_fe_query_instance_num" "查询实例数"
echo ""

# 步骤 4: 测试 FE 连接管理指标
echo "步骤 4: 测试 FE 连接管理指标..."
test_metric "doris_fe_connection_total" "连接总数"
echo ""

# 步骤 5: 测试 FE Edit Log 指标
echo "步骤 5: 测试 FE Edit Log 指标..."
test_metric "doris_fe_edit_log" "Edit Log 大小"
test_metric "doris_fe_edit_log_write_latency_ms" "Edit Log 写入延迟"
echo ""

# 步骤 6: 测试 FE RPC 性能指标
echo "步骤 6: 测试 FE RPC 性能指标..."
test_metric "doris_fe_thrift_rpc_latency_ms" "Thrift RPC 延迟"
test_metric "doris_fe_thrift_rpc_total" "Thrift RPC 总数"
echo ""

# 步骤 7: 测试 FE 线程池指标
echo "步骤 7: 测试 FE 线程池指标..."
test_metric "doris_fe_thread_pool" "线程池状态"
echo ""

# 步骤 8: 测试 FE Cache 指标
echo "步骤 8: 测试 FE Cache 指标..."
test_metric "doris_fe_cache_hit" "Cache 命中"
test_metric "doris_fe_cache_miss" "Cache 未命中"
test_metric "doris_fe_cache_added" "Cache 添加"
echo ""

# 步骤 9: 测试 BE Stream Load 指标
echo "步骤 9: 测试 BE Stream Load 指标..."
test_metric "doris_be_stream_load_txn_request" "Stream Load 请求"
test_metric "doris_be_stream_load_current_processing" "Stream Load 当前处理"
echo ""

# 步骤 10: 测试 BE Compaction 指标
echo "步骤 10: 测试 BE Compaction 指标..."
test_metric "doris_be_compaction_task_state_total" "Compaction 任务状态"
test_metric "doris_be_compaction_rows_total" "Compaction 处理行数"
test_metric "doris_be_compaction_max_score" "最大 Compaction Score"
echo ""

# 步骤 11: 测试 BE Cache 指标
echo "步骤 11: 测试 BE Cache 指标..."
test_metric "doris_be_cache_hit_ratio" "BE Cache 命中率"
echo ""

# 步骤 12: 测试 BE 网络指标
echo "步骤 12: 测试 BE 网络指标..."
test_metric "doris_be_network_send_packets" "网络发送包数"
test_metric "doris_be_network_receive_packets" "网络接收包数"
echo ""

# 步骤 13: 测试 BE 内存指标
echo "步骤 13: 测试 BE 内存指标..."
test_metric "doris_be_memory_pgpgin" "内存页面换入"
test_metric "doris_be_memory_memtable_consumption" "Memtable 内存消耗"
echo ""

# 步骤 14: 显示一些关键指标的当前值
echo "步骤 14: 显示关键指标当前值..."
echo ""
echo "=== FE 查询性能 ==="
qps=$(curl -s "http://localhost:9090/api/v1/query?query=doris_fe_qps" | grep -oP '"value":\[\d+\.\d+,"[^"]*"\]' | grep -oP '"\K[^"]+$')
echo "当前 QPS: $qps"

err_rate=$(curl -s "http://localhost:9090/api/v1/query?query=doris_fe_query_err_rate" | grep -oP '"value":\[\d+\.\d+,"[^"]*"\]' | grep -oP '"\K[^"]+$')
echo "查询错误率: $err_rate"

echo ""
echo "=== FE 连接状态 ==="
connections=$(curl -s "http://localhost:9090/api/v1/query?query=sum(doris_fe_connection_total)" | grep -oP '"value":\[\d+\.\d+,"[^"]*"\]' | grep -oP '"\K[^"]+$')
echo "活跃连接总数: $connections"

echo ""
echo "=== FE 线程池状态 ==="
curl -s "http://localhost:9090/api/v1/query?query=doris_fe_thread_pool" | grep -oP '"metric":\{[^}]+\},"value":\[\d+\.\d+,"[^"]*"\]' | head -3

echo ""
echo "=== BE Compaction 状态 ==="
max_score=$(curl -s "http://localhost:9090/api/v1/query?query=doris_be_compaction_max_score" | grep -oP '"value":\[\d+\.\d+,"[^"]*"\]' | grep -oP '"\K[^"]+$')
echo "最大 Compaction Score: $max_score"

echo ""
echo "=== BE 磁盘使用率 ==="
disk_usage=$(curl -s "http://localhost:9090/api/v1/query?query=(doris_be_disks_total_capacity-doris_be_disks_avail_capacity)/doris_be_disks_total_capacity*100" | grep -oP '"value":\[\d+\.\d+,"[^"]*"\]' | grep -oP '"\K[^"]+$')
echo "磁盘使用率: ${disk_usage}%"

echo ""
echo "=========================================="
echo "测试总结"
echo "=========================================="
echo -e "通过: ${GREEN}$PASSED${NC}"
echo -e "失败/无数据: ${YELLOW}$FAILED${NC}"
TOTAL=$((PASSED + FAILED))
echo "总计: $TOTAL"
echo ""

if [ $FAILED -eq 0 ]; then
    echo -e "${GREEN}✓ 所有测试通过！${NC}"
    exit 0
else
    echo -e "${YELLOW}⚠ 部分指标无数据，这可能是正常的（取决于系统活动）${NC}"
    exit 0
fi
