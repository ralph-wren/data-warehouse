#!/bin/bash

# 监控 DWD 作业运行情况
# 检查 Checkpoint 和数据写入

echo "=========================================="
echo "监控 Flink DWD 作业"
echo "=========================================="
echo

# 1. 检查作业进程
echo "1. 检查作业进程..."
if ps aux | grep -i "FlinkDWDJobSQL" | grep -v grep > /dev/null; then
    echo "✓ DWD 作业正在运行"
    ps aux | grep -i "FlinkDWDJobSQL" | grep -v grep | awk '{print "  PID: " $2 ", 运行时间: " $10}'
else
    echo "✗ DWD 作业未运行"
fi
echo

# 2. 检查 DWD 表数据量
echo "2. 检查 DWD 表数据量..."
CURRENT_COUNT=$(mysql -h 127.0.0.1 -P 9030 -u root -s -N -e "SELECT COUNT(*) FROM crypto_dw.dwd_crypto_ticker_detail")
echo "  当前数据量: $CURRENT_COUNT 条"
echo

# 3. 等待 10 秒后再次检查
echo "3. 等待 10 秒后再次检查..."
sleep 10
NEW_COUNT=$(mysql -h 127.0.0.1 -P 9030 -u root -s -N -e "SELECT COUNT(*) FROM crypto_dw.dwd_crypto_ticker_detail")
DIFF=$((NEW_COUNT - CURRENT_COUNT))
echo "  新数据量: $NEW_COUNT 条"
echo "  增加: $DIFF 条"

if [ $DIFF -gt 0 ]; then
    echo "  ✓ 数据正在写入"
else
    echo "  ✗ 数据未增加"
fi
echo

# 4. 检查最近的 Stream Load 任务
echo "4. 检查最近的 Stream Load 任务..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW LOAD FROM crypto_dw WHERE Label LIKE '%dwd%' ORDER BY CreateTime DESC LIMIT 3\G" | grep -E "(Label|State|ErrorMsg|LoadRows|CreateTime)"
echo

# 5. 检查 Flink 日志中的 Checkpoint 信息
echo "5. 检查 Flink 日志中的 Checkpoint 信息..."
if [ -f logs/flink/flink.log ]; then
    tail -100 logs/flink/flink.log | grep -i "checkpoint" | tail -5
else
    echo "  Flink 日志文件不存在"
fi
echo

echo "=========================================="
echo "监控完成"
echo "=========================================="
