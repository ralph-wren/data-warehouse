#!/bin/bash

# Kafka 分区分布检查脚本
# 用于检查数据是否均匀分布到不同分区

echo "=========================================="
echo "Kafka 分区分布检查"
echo "=========================================="
echo ""

# 检查 Topic 信息
echo "=== Topic 信息 ==="
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --describe --topic crypto_ticker 2>/dev/null
echo ""

# 检查每个分区的数据量
echo "=== 分区数据量 ==="
docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic crypto_ticker \
    --time -1 2>/dev/null | sort -t: -k2 -n | while IFS=: read topic partition offset; do
    echo "分区 $partition: $offset 条消息"
done
echo ""

# 检查消费者组的分区分配
echo "=== Flink 消费者组分区分配 ==="
docker exec kafka kafka-consumer-groups \
    --bootstrap-server localhost:9092 \
    --describe \
    --group flink-ods-consumer 2>&1 | grep "crypto_ticker" | grep -v "GROUP" | \
    awk '{printf "分区 %s: Current=%s, End=%s, LAG=%s\n", $3, $4, $5, $6}'
echo ""

# 计算分区分布均衡度
echo "=== 分区均衡度分析 ==="
OFFSETS=$(docker exec kafka kafka-run-class kafka.tools.GetOffsetShell \
    --broker-list localhost:9092 \
    --topic crypto_ticker \
    --time -1 2>/dev/null | cut -d: -f3)

TOTAL=0
COUNT=0
MIN=999999999
MAX=0

for offset in $OFFSETS; do
    TOTAL=$((TOTAL + offset))
    COUNT=$((COUNT + 1))
    if [ $offset -lt $MIN ]; then
        MIN=$offset
    fi
    if [ $offset -gt $MAX ]; then
        MAX=$offset
    fi
done

if [ $COUNT -gt 0 ]; then
    AVG=$((TOTAL / COUNT))
    echo "总消息数: $TOTAL"
    echo "分区数: $COUNT"
    echo "平均每分区: $AVG"
    echo "最小分区: $MIN"
    echo "最大分区: $MAX"
    echo ""
    
    # 计算均衡度（最大值与最小值的比率）
    if [ $MIN -gt 0 ]; then
        RATIO=$((MAX * 100 / MIN))
        echo "均衡度: $RATIO%"
        if [ $RATIO -lt 150 ]; then
            echo "✅ 分区分布均衡（差异 < 50%）"
        elif [ $RATIO -lt 200 ]; then
            echo "⚠️  分区分布一般（差异 50-100%）"
        else
            echo "❌ 分区分布不均衡（差异 > 100%）"
        fi
    else
        echo "⚠️  有空分区，无法计算均衡度"
    fi
else
    echo "❌ 没有找到分区数据"
fi

echo ""
echo "=========================================="
