#!/bin/bash

# 数据流监控脚本
# 用于监控数据采集器 -> Kafka -> Flink -> Doris 的完整数据流

echo "=========================================="
echo "数据流监控"
echo "=========================================="
echo ""

# 循环监控
for i in {1..10}; do
    echo "=== 检查 $i/10 ($(date '+%H:%M:%S')) ==="
    echo ""
    
    # 1. 数据采集器状态
    echo "1. 数据采集器:"
    echo "   最新 Kafka Offset: $(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell --broker-list localhost:9092 --topic crypto_ticker --time -1 2>/dev/null | grep ':2:' | cut -d':' -f3)"
    echo ""
    
    # 2. Kafka 消费者组
    echo "2. Flink 消费状态:"
    CONSUMER_INFO=$(docker exec kafka kafka-consumer-groups --bootstrap-server localhost:9092 --describe --group flink-ods-consumer 2>&1 | grep "crypto_ticker" | grep -v "GROUP")
    echo "$CONSUMER_INFO" | awk '{printf "   Partition %s: Current=%s, End=%s, LAG=%s\n", $3, $4, $5, $6}'
    echo ""
    
    # 3. Doris 数据
    echo "3. Doris ODS 表:"
    mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT COUNT(*) as total, FROM_UNIXTIME(MAX(timestamp)/1000) as latest_time FROM crypto_dw.ods_crypto_ticker_rt;" 2>/dev/null | tail -n +2 | awk '{printf "   总记录数: %s, 最新时间: %s\n", $1, $2" "$3}'
    echo ""
    
    # 4. Flink 作业状态
    echo "4. Flink 作业:"
    JOB_STATUS=$(curl -s http://localhost:8081/jobs 2>/dev/null | python -c "import sys, json; jobs = json.load(sys.stdin)['jobs']; print(f\"{len([j for j in jobs if j['status'] == 'RUNNING'])} running\") if jobs else print('0 running')" 2>/dev/null)
    echo "   运行中的作业: $JOB_STATUS"
    echo ""
    
    echo "----------------------------------------"
    echo ""
    
    # 等待 10 秒
    if [ $i -lt 10 ]; then
        sleep 10
    fi
done

echo "=========================================="
echo "监控完成"
echo "=========================================="
