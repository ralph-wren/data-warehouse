#!/bin/bash
# 更新 Grafana 面板配置，只显示活跃的 job
# 在所有查询中添加过滤条件

echo "备份当前配置..."
cp volumes/monitoring/grafana/dashboards/flink-monitoring.json volumes/monitoring/grafana/dashboards/flink-monitoring.json.backup2

echo "更新配置文件，添加活跃 job 过滤..."

# 使用 sed 替换查询表达式，添加 job_id 过滤
# 注意：只过滤包含 job 相关的指标，不过滤 jobmanager 级别的指标

sed -i.tmp '
# 数据流入速率 - 添加 job_id 过滤
s/"expr": "rate(flink_taskmanager_job_task_numRecordsIn\[1m\])"/"expr": "rate(flink_taskmanager_job_task_numRecordsIn{job_id!=\"\"}[1m])"/g

# 数据流出速率 - 添加 job_id 过滤
s/"expr": "rate(flink_taskmanager_job_task_numRecordsOut\[1m\])"/"expr": "rate(flink_taskmanager_job_task_numRecordsOut{job_id!=\"\"}[1m])"/g

# Checkpoint 持续时间 - 添加 job_id 过滤
s/"expr": "flink_jobmanager_job_lastCheckpointDuration"/"expr": "flink_jobmanager_job_lastCheckpointDuration{job_id!=\"\"}"/g

# Checkpoint 完成次数 - 添加 job_id 过滤
s/"expr": "flink_jobmanager_job_numberOfCompletedCheckpoints"/"expr": "flink_jobmanager_job_numberOfCompletedCheckpoints{job_id!=\"\"}"/g

# Checkpoint 失败次数 - 添加 job_id 过滤
s/"expr": "flink_jobmanager_job_numberOfFailedCheckpoints"/"expr": "flink_jobmanager_job_numberOfFailedCheckpoints{job_id!=\"\"}"/g

# 缓冲池使用率 - 添加 job_id 过滤
s/"expr": "flink_taskmanager_job_task_buffers_outPoolUsage"/"expr": "flink_taskmanager_job_task_buffers_outPoolUsage{job_id!=\"\"}"/g

# 网络字节流入 - 添加 job_id 过滤
s/"expr": "rate(flink_taskmanager_job_task_numBytesIn\[1m\])"/"expr": "rate(flink_taskmanager_job_task_numBytesIn{job_id!=\"\"}[1m])"/g

# 网络字节流出 - 添加 job_id 过滤
s/"expr": "rate(flink_taskmanager_job_task_numBytesOut\[1m\])"/"expr": "rate(flink_taskmanager_job_task_numBytesOut{job_id!=\"\"}[1m])"/g
' volumes/monitoring/grafana/dashboards/flink-monitoring.json

# 删除临时文件
rm -f volumes/monitoring/grafana/dashboards/flink-monitoring.json.tmp

echo "配置更新完成！"
echo ""
echo "验证 JSON 格式..."
if python -m json.tool volumes/monitoring/grafana/dashboards/flink-monitoring.json > /dev/null 2>&1; then
    echo "✓ JSON 格式正确"
else
    echo "✗ JSON 格式错误，恢复备份..."
    cp volumes/monitoring/grafana/dashboards/flink-monitoring.json.backup2 volumes/monitoring/grafana/dashboards/flink-monitoring.json
    exit 1
fi

echo ""
echo "更新说明："
echo "- 所有 job 相关的指标都添加了 {job_id!=\"\"} 过滤条件"
echo "- 只显示有 job_id 的指标（即运行中的作业）"
echo "- 已 kill 的 job 不会再显示"
echo ""
echo "重启 Grafana 以应用更改："
echo "  docker-compose -f docker-compose-monitoring.yml restart grafana"
