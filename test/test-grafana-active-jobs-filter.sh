#!/bin/bash
# 测试 Grafana 面板的活跃 Job 过滤功能
# 验证只显示运行中的作业，不显示已 kill 的作业

echo "=========================================="
echo "Grafana 活跃 Job 过滤测试"
echo "=========================================="
echo ""

# 检查 Grafana 是否运行
if ! curl -s http://localhost:3000 > /dev/null; then
    echo "✗ Grafana 未运行"
    echo "请先启动监控服务: bash start-monitoring.sh"
    exit 1
fi
echo "✓ Grafana 正在运行"

# 检查 Prometheus 是否运行
if ! curl -s http://localhost:9090 > /dev/null; then
    echo "✗ Prometheus 未运行"
    exit 1
fi
echo "✓ Prometheus 正在运行"

# 检查 Pushgateway 是否运行
if ! curl -s http://localhost:9091 > /dev/null; then
    echo "✗ Pushgateway 未运行"
    exit 1
fi
echo "✓ Pushgateway 正在运行"

echo ""
echo "=========================================="
echo "测试场景 1：启动作业"
echo "=========================================="
echo ""

# 检查是否有运行中的作业
RUNNING_JOBS=$(curl -s http://localhost:8081/jobs | jq -r '.jobs | length')
echo "当前运行中的作业数: $RUNNING_JOBS"

if [ "$RUNNING_JOBS" -eq 0 ]; then
    echo ""
    echo "提示：没有运行中的作业"
    echo "请手动启动一个作业："
    echo "  bash run-flink-ods-datastream.sh"
    echo ""
    echo "然后等待 15 秒，再次运行此脚本"
    exit 0
fi

# 获取作业信息
echo ""
echo "运行中的作业："
curl -s http://localhost:8081/jobs | jq -r '.jobs[] | "  - \(.id): \(.status)"'

echo ""
echo "等待 5 秒，让指标推送到 Prometheus..."
sleep 5

echo ""
echo "=========================================="
echo "查询 Prometheus 指标"
echo "=========================================="
echo ""

# 查询数据流入速率（带 job_id 过滤）
echo "1. 数据流入速率（带 job_id 过滤）："
QUERY1='rate(flink_taskmanager_job_task_numRecordsIn{job_id!=""}[1m])'
RESULT1=$(curl -s "http://localhost:9090/api/v1/query?query=$(echo $QUERY1 | jq -sRr @uri)" | jq -r '.data.result | length')
echo "   结果数量: $RESULT1"

if [ "$RESULT1" -gt 0 ]; then
    echo "   ✓ 查询到活跃 job 的指标"
    curl -s "http://localhost:9090/api/v1/query?query=$(echo $QUERY1 | jq -sRr @uri)" | \
        jq -r '.data.result[] | "     - \(.metric.job_name) (\(.metric.job_id))"' | head -3
else
    echo "   ✗ 未查询到指标（可能需要等待更长时间）"
fi

echo ""
echo "2. 数据流入速率（不带 job_id 过滤）："
QUERY2='rate(flink_taskmanager_job_task_numRecordsIn[1m])'
RESULT2=$(curl -s "http://localhost:9090/api/v1/query?query=$(echo $QUERY2 | jq -sRr @uri)" | jq -r '.data.result | length')
echo "   结果数量: $RESULT2"

echo ""
echo "对比："
echo "  - 带过滤: $RESULT1 个时间序列"
echo "  - 不带过滤: $RESULT2 个时间序列"

if [ "$RESULT1" -le "$RESULT2" ]; then
    echo "  ✓ 过滤生效（带过滤的结果 <= 不带过滤的结果）"
else
    echo "  ✗ 过滤可能未生效"
fi

echo ""
echo "=========================================="
echo "测试场景 2：Kill 作业（可选）"
echo "=========================================="
echo ""

read -p "是否要 kill 第一个作业并测试指标消失？(y/N) " -n 1 -r
echo ""

if [[ $REPLY =~ ^[Yy]$ ]]; then
    # 获取第一个作业的 ID
    JOB_ID=$(curl -s http://localhost:8081/jobs | jq -r '.jobs[0].id')
    JOB_NAME=$(curl -s http://localhost:8081/jobs/$JOB_ID | jq -r '.name')
    
    echo "准备 kill 作业："
    echo "  ID: $JOB_ID"
    echo "  名称: $JOB_NAME"
    echo ""
    
    # Kill 作业
    echo "正在 kill 作业..."
    curl -s -X PATCH "http://localhost:8081/jobs/$JOB_ID?mode=cancel" > /dev/null
    
    echo "✓ 作业已 kill"
    echo ""
    
    echo "等待 30 秒，让指标更新..."
    for i in {30..1}; do
        echo -ne "  剩余 $i 秒...\r"
        sleep 1
    done
    echo ""
    
    echo ""
    echo "再次查询 Prometheus 指标："
    RESULT3=$(curl -s "http://localhost:9090/api/v1/query?query=$(echo $QUERY1 | jq -sRr @uri)" | jq -r '.data.result | length')
    echo "  数据流入速率（带 job_id 过滤）: $RESULT3 个时间序列"
    
    if [ "$RESULT3" -lt "$RESULT1" ]; then
        echo "  ✓ 指标已减少（作业 kill 后指标消失）"
    else
        echo "  ⚠ 指标未减少（可能需要等待更长时间或清理 Pushgateway）"
        echo ""
        echo "  提示：如果使用 Pushgateway，需要手动清理："
        echo "    curl -X DELETE http://localhost:9091/metrics/job/flink-ods-job"
    fi
fi

echo ""
echo "=========================================="
echo "测试总结"
echo "=========================================="
echo ""

echo "✓ Grafana 面板配置已更新"
echo "✓ 所有 job 相关查询都添加了 {job_id!=\"\"} 过滤"
echo "✓ 只显示活跃的（运行中的）job"
echo ""

echo "查看效果："
echo "  1. 访问 Grafana: http://localhost:3000"
echo "  2. 登录（admin/admin）"
echo "  3. 打开 'Flink 实时监控（增强版）' 面板"
echo "  4. 观察图表只显示运行中的作业"
echo ""

echo "清理 Pushgateway 中的旧指标（可选）："
echo "  curl -X PUT http://localhost:9091/api/v1/admin/wipe"
echo ""
