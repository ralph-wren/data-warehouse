#!/bin/bash

# Flink 指标检查脚本
# 用于从 Flink REST API 获取真实的指标值

echo "=========================================="
echo "Flink 指标检查"
echo "=========================================="
echo ""

# 获取 Job ID
JOB_ID=$(curl -s http://localhost:8081/jobs 2>/dev/null | python -c "import sys, json; jobs = json.load(sys.stdin)['jobs']; print(jobs[0]['id']) if jobs and len(jobs) > 0 else print('')" 2>/dev/null)

if [ -z "$JOB_ID" ]; then
    echo "❌ 没有找到运行中的 Flink 作业"
    exit 1
fi

echo "✓ Job ID: $JOB_ID"
echo ""

# 获取 Vertex ID
VERTEX_ID=$(curl -s "http://localhost:8081/jobs/$JOB_ID" 2>/dev/null | python -c "import sys, json; data = json.load(sys.stdin); print(data['vertices'][0]['id']) if data.get('vertices') else print('')" 2>/dev/null)

if [ -z "$VERTEX_ID" ]; then
    echo "❌ 无法获取 Vertex ID"
    exit 1
fi

echo "✓ Vertex ID: $VERTEX_ID"
echo ""

# 获取 Vertex 名称
VERTEX_NAME=$(curl -s "http://localhost:8081/jobs/$JOB_ID" 2>/dev/null | python -c "import sys, json; data = json.load(sys.stdin); print(data['vertices'][0]['name']) if data.get('vertices') else print('')" 2>/dev/null)
echo "✓ Vertex: $VERTEX_NAME"
echo ""

echo "=========================================="
echo "指标详情"
echo "=========================================="
echo ""

# 获取并行度
PARALLELISM=$(curl -s "http://localhost:8081/jobs/$JOB_ID" 2>/dev/null | python -c "import sys, json; data = json.load(sys.stdin); print(data['vertices'][0]['parallelism']) if data.get('vertices') else print('0')" 2>/dev/null)

echo "并行度: $PARALLELISM"
echo ""

# 定义要查询的指标
METRICS="Source__Kafka_Source.numRecordsIn,Source__Kafka_Source.numRecordsOut,ODS_Transform.numRecordsIn,ODS_Transform.numRecordsOut,Doris_ODS_Sink__Writer.numRecordsIn,Doris_ODS_Sink__Writer.numRecordsOut"

# 初始化总计
TOTAL_RECORDS_IN=0
TOTAL_RECORDS_OUT=0

# 遍历每个 subtask
for ((i=0; i<$PARALLELISM; i++)); do
    echo "--- Subtask $i ---"
    
    # 获取指标
    METRICS_DATA=$(curl -s "http://localhost:8081/jobs/$JOB_ID/vertices/$VERTEX_ID/subtasks/$i/metrics?get=$METRICS" 2>/dev/null)
    
    # 解析并显示指标
    echo "$METRICS_DATA" | python -c "
import sys, json
try:
    metrics = json.load(sys.stdin)
    records_in = 0
    records_out = 0
    for m in metrics:
        print(f\"  {m['id']}: {m['value']}\")
        if 'Source__Kafka_Source.numRecordsIn' in m['id']:
            records_in = int(m['value'])
        if 'Doris_ODS_Sink__Writer.numRecordsOut' in m['id']:
            records_out = int(m['value'])
    print(f\"  小计: In={records_in}, Out={records_out}\")
    print(f\"{records_in},{records_out}\")
except:
    print('  解析失败')
    print('0,0')
" | tee /tmp/flink_metrics_$i.txt
    
    # 读取小计
    SUBTASK_IN=$(tail -1 /tmp/flink_metrics_$i.txt | cut -d',' -f1)
    SUBTASK_OUT=$(tail -1 /tmp/flink_metrics_$i.txt | cut -d',' -f2)
    
    # 累加到总计
    TOTAL_RECORDS_IN=$((TOTAL_RECORDS_IN + SUBTASK_IN))
    TOTAL_RECORDS_OUT=$((TOTAL_RECORDS_OUT + SUBTASK_OUT))
    
    echo ""
done

echo "=========================================="
echo "总计"
echo "=========================================="
echo "Records In:  $TOTAL_RECORDS_IN"
echo "Records Out: $TOTAL_RECORDS_OUT"
echo ""

# 对比 Kafka 消费状态
echo "=========================================="
echo "Kafka 消费状态"
echo "=========================================="
docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group flink-ods-consumer 2>&1 | grep "crypto_ticker" | grep -v "GROUP" | awk '{
    total_current += $4
    total_end += $5
    total_lag += $6
}
END {
    print "Current Offset: " total_current
    print "End Offset:     " total_end
    print "LAG:            " total_lag
}'
echo ""

# 对比 Doris 数据
echo "=========================================="
echo "Doris 数据状态"
echo "=========================================="
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT COUNT(*) as total, FROM_UNIXTIME(MAX(timestamp)/1000) as latest_time FROM crypto_dw.ods_crypto_ticker_rt;" 2>/dev/null | tail -n +2 | awk '{
    print "Total Records:  " $1
    print "Latest Time:    " $2 " " $3
}'
echo ""

echo "=========================================="
echo "结论"
echo "=========================================="
if [ $TOTAL_RECORDS_IN -gt 0 ]; then
    echo "✅ Flink 正在正常接收和处理数据"
    echo "✅ 总共处理了 $TOTAL_RECORDS_IN 条记录"
    echo ""
    echo "注意: Flink Web UI 的 Overview 页面在本地模式下"
    echo "      可能不会正确显示指标，这是已知问题。"
    echo "      请使用此脚本或查看 Vertex 详情页面来查看真实指标。"
else
    echo "⚠️  Flink 没有接收到数据"
    echo "    请检查 Kafka 和数据采集器状态"
fi
echo ""

# 清理临时文件
rm -f /tmp/flink_metrics_*.txt

echo "=========================================="
