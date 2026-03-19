#!/bin/bash

echo "=========================================="
echo "测试 DWD 数据流水线"
echo "=========================================="
echo

# 1. 检查 Kafka 中的数据
echo "1. 检查 Kafka Topic 中的消息数量..."
KAFKA_COUNT=$(kafka-run-class.sh kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic crypto_ticker \
    --time -1 | awk -F':' '{sum += $3} END {print sum}')
echo "   Kafka Topic 总消息数: $KAFKA_COUNT"
echo

# 2. 消费一条 Kafka 消息查看格式
echo "2. 查看 Kafka 消息格式..."
timeout 3 kafka-console-consumer.sh \
    --bootstrap-server localhost:9092 \
    --topic crypto_ticker \
    --max-messages 1 \
    --from-beginning 2>/dev/null || echo "   无法读取消息"
echo

# 3. 检查 Doris ODS 层数据
echo "3. 检查 Doris ODS 层数据..."
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT COUNT(*) as ods_count 
FROM crypto_dw.ods_crypto_ticker_rt;
" 2>/dev/null
echo

# 4. 检查 Doris DWD 层数据
echo "4. 检查 Doris DWD 层数据..."
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT COUNT(*) as dwd_count 
FROM crypto_dw.dwd_crypto_ticker_detail;
" 2>/dev/null
echo

# 5. 检查 Flink 作业状态
echo "5. 检查 Flink 作业进程..."
ps aux | grep -i "FlinkDWDJobSQL" | grep -v grep || echo "   DWD 作业未运行"
echo

echo "=========================================="
echo "测试完成"
echo "=========================================="
