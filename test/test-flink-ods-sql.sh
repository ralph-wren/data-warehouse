#!/bin/bash

# 测试 FlinkODSJobSQL 作业
# 用于诊断 SQL 作业的问题

echo "=========================================="
echo "测试 Flink ODS SQL 作业"
echo "=========================================="
echo ""

# 设置环境变量
export APP_ENV=dev
export MAVEN_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"

echo "环境配置:"
echo "  APP_ENV: $APP_ENV"
echo "  MAVEN_OPTS: $MAVEN_OPTS"
echo ""

# 检查依赖服务
echo "检查依赖服务..."
echo ""

# 1. 检查 Kafka
echo "1. 检查 Kafka (localhost:9093)..."
timeout 3 bash -c "</dev/tcp/localhost/9093" 2>/dev/null
if [ $? -eq 0 ]; then
    echo "   ✅ Kafka 连接成功"
else
    echo "   ❌ Kafka 连接失败"
    echo "   请确保 Kafka 正在运行: docker ps | grep kafka"
    exit 1
fi

# 2. 检查 Doris FE
echo "2. 检查 Doris FE (localhost:8030)..."
curl -s -o /dev/null -w "%{http_code}" http://localhost:8030/api/bootstrap > /tmp/doris_status.txt 2>&1
doris_status=$(cat /tmp/doris_status.txt)
if [ "$doris_status" = "200" ] || [ "$doris_status" = "000" ]; then
    echo "   ✅ Doris FE 连接成功"
else
    echo "   ❌ Doris FE 连接失败 (HTTP $doris_status)"
    echo "   请确保 Doris 正在运行: docker ps | grep doris"
    exit 1
fi

# 3. 检查 Kafka 是否有数据
echo "3. 检查 Kafka 数据..."
kafka_data=$(docker exec kafka kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic crypto_ticker \
    --from-beginning \
    --max-messages 1 \
    --timeout-ms 3000 2>&1 | grep -v "Processed")

if [ -n "$kafka_data" ]; then
    echo "   ✅ Kafka 有数据"
    echo "   示例: ${kafka_data:0:100}..."
else
    echo "   ⚠️  Kafka 暂无数据"
    echo "   请确保数据采集器正在运行: bash run-collector.sh"
fi

echo ""
echo "=========================================="
echo "开始运行 Flink ODS SQL 作业"
echo "=========================================="
echo ""

# 运行作业,捕获输出
mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkODSJobSQL" \
    -Dexec.cleanupDaemonThreads=false \
    2>&1 | tee test/flink-ods-sql-test.log

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
echo ""
echo "日志已保存到: test/flink-ods-sql-test.log"
echo ""

# 检查是否有错误
if grep -q "Exception" test/flink-ods-sql-test.log; then
    echo "⚠️  发现异常,请查看日志文件"
    echo ""
    echo "常见错误:"
    grep -A 5 "Exception\|Error" test/flink-ods-sql-test.log | head -n 20
else
    echo "✅ 未发现明显错误"
fi
