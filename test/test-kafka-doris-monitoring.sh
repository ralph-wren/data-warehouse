#!/bin/bash
# 测试 Kafka 和 Doris 监控功能
# 验证 Exporter 和 Prometheus 抓取是否正常

echo "=========================================="
echo "Kafka 和 Doris 监控测试"
echo "=========================================="
echo ""

# 检查监控服务是否运行
echo "检查监控服务状态..."
echo ""

# 1. 检查 Prometheus
if curl -s http://localhost:9090 > /dev/null; then
    echo "✓ Prometheus 正在运行 (http://localhost:9090)"
else
    echo "✗ Prometheus 未运行"
    echo "请先启动监控服务: bash start-monitoring.sh"
    exit 1
fi

# 2. 检查 Grafana
if curl -s http://localhost:3000 > /dev/null; then
    echo "✓ Grafana 正在运行 (http://localhost:3000)"
else
    echo "✗ Grafana 未运行"
    exit 1
fi

# 3. 检查 Kafka Exporter
if curl -s http://localhost:9308/metrics > /dev/null; then
    echo "✓ Kafka Exporter 正在运行 (http://localhost:9308)"
else
    echo "✗ Kafka Exporter 未运行"
    exit 1
fi

# 4. 检查 MySQL Exporter
if curl -s http://localhost:9104/metrics > /dev/null; then
    echo "✓ MySQL Exporter 正在运行 (http://localhost:9104)"
else
    echo "✗ MySQL Exporter 未运行"
    exit 1
fi

echo ""
echo "=========================================="
echo "检查 Exporter 指标"
echo "=========================================="
echo ""

# 检查 Kafka Exporter 指标
echo "1. Kafka Exporter 指标:"
KAFKA_BROKERS=$(curl -s http://localhost:9308/metrics | grep "^kafka_brokers " | awk '{print $2}')
KAFKA_TOPICS=$(curl -s http://localhost:9308/metrics | grep "kafka_topic_partitions" | wc -l)

if [ -n "$KAFKA_BROKERS" ]; then
    echo "   ✓ Kafka Brokers: $KAFKA_BROKERS"
    echo "   ✓ Kafka Topics: $KAFKA_TOPICS"
else
    echo "   ✗ 无法获取 Kafka 指标"
    echo "   提示: 检查 Kafka 是否运行在 localhost:9092"
fi

echo ""
echo "2. MySQL Exporter 指标:"
MYSQL_UP=$(curl -s http://localhost:9104/metrics | grep "^mysql_up " | awk '{print $2}')
MYSQL_CONN=$(curl -s http://localhost:9104/metrics | grep "^mysql_global_status_threads_connected " | awk '{print $2}')

if [ "$MYSQL_UP" = "1" ]; then
    echo "   ✓ Doris 连接成功"
    echo "   ✓ 当前连接数: $MYSQL_CONN"
else
    echo "   ✗ 无法连接 Doris"
    echo "   提示: 检查 Doris 是否运行在 localhost:9030"
fi

echo ""
echo "=========================================="
echo "检查 Prometheus Targets"
echo "=========================================="
echo ""

# 检查 Prometheus targets 状态
KAFKA_TARGET=$(curl -s http://localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | select(.labels.job=="kafka") | .health')
DORIS_TARGET=$(curl -s http://localhost:9090/api/v1/targets | jq -r '.data.activeTargets[] | select(.labels.job=="doris-mysql") | .health')

echo "Prometheus Targets 状态:"
if [ "$KAFKA_TARGET" = "up" ]; then
    echo "   ✓ Kafka target: UP"
else
    echo "   ✗ Kafka target: $KAFKA_TARGET"
fi

if [ "$DORIS_TARGET" = "up" ]; then
    echo "   ✓ Doris target: UP"
else
    echo "   ✗ Doris target: $DORIS_TARGET"
fi

echo ""
echo "=========================================="
echo "查询 Prometheus 指标"
echo "=========================================="
echo ""

# 查询 Kafka 指标
echo "1. Kafka 指标:"
KAFKA_PARTITIONS=$(curl -s "http://localhost:9090/api/v1/query?query=kafka_topic_partitions" | jq -r '.data.result | length')
if [ "$KAFKA_PARTITIONS" -gt 0 ]; then
    echo "   ✓ 查询到 $KAFKA_PARTITIONS 个 Topic"
    curl -s "http://localhost:9090/api/v1/query?query=kafka_topic_partitions" | \
        jq -r '.data.result[] | "     - \(.metric.topic): \(.value[1]) 个分区"' | head -5
else
    echo "   ✗ 未查询到 Kafka 指标"
fi

echo ""
echo "2. Doris 指标:"
DORIS_QPS=$(curl -s "http://localhost:9090/api/v1/query?query=rate(mysql_global_status_queries[1m])" | jq -r '.data.result[0].value[1]')
DORIS_CONN_PROM=$(curl -s "http://localhost:9090/api/v1/query?query=mysql_global_status_threads_connected" | jq -r '.data.result[0].value[1]')

if [ -n "$DORIS_QPS" ] && [ "$DORIS_QPS" != "null" ]; then
    echo "   ✓ QPS: $(printf "%.2f" $DORIS_QPS) 查询/秒"
    echo "   ✓ 连接数: $DORIS_CONN_PROM"
else
    echo "   ✗ 未查询到 Doris 指标"
fi

echo ""
echo "=========================================="
echo "测试总结"
echo "=========================================="
echo ""

# 统计结果
PASS=0
FAIL=0

[ -n "$KAFKA_BROKERS" ] && ((PASS++)) || ((FAIL++))
[ "$MYSQL_UP" = "1" ] && ((PASS++)) || ((FAIL++))
[ "$KAFKA_TARGET" = "up" ] && ((PASS++)) || ((FAIL++))
[ "$DORIS_TARGET" = "up" ] && ((PASS++)) || ((FAIL++))
[ "$KAFKA_PARTITIONS" -gt 0 ] && ((PASS++)) || ((FAIL++))
[ -n "$DORIS_QPS" ] && [ "$DORIS_QPS" != "null" ] && ((PASS++)) || ((FAIL++))

echo "测试结果: $PASS 通过, $FAIL 失败"
echo ""

if [ $FAIL -eq 0 ]; then
    echo "✓ 所有测试通过！"
    echo ""
    echo "查看监控面板:"
    echo "  1. 访问 Grafana: http://localhost:3000"
    echo "  2. 登录（admin/admin）"
    echo "  3. 查看面板:"
    echo "     - Flink 实时监控（增强版）"
    echo "     - Kafka 实时监控"
    echo "     - Doris 实时监控"
else
    echo "✗ 部分测试失败"
    echo ""
    echo "故障排查:"
    echo "  1. 检查 Kafka 是否运行: netstat -an | grep 9092"
    echo "  2. 检查 Doris 是否运行: mysql -h 127.0.0.1 -P 9030 -u root"
    echo "  3. 查看 Exporter 日志:"
    echo "     docker logs kafka-exporter"
    echo "     docker logs mysql-exporter"
    echo "  4. 重启监控服务:"
    echo "     bash stop-monitoring.sh"
    echo "     bash start-monitoring.sh"
fi

echo ""
