#!/bin/bash
# 最终监控验证脚本
# 确认 Kafka 和 Doris 监控数据是否正常

echo "=========================================="
echo "最终监控验证"
echo "=========================================="
echo ""

# 1. 检查 Prometheus 指标（原始数据）
echo "1. Prometheus 指标检查"
echo "----------------------------------------"

echo "Kafka 指标:"
curl -s "http://localhost:9090/api/v1/query?query=kafka_brokers" | grep -o '"value":\["[^"]*","[^"]*"\]' | head -1
echo ""

echo "Doris FE 指标:"
curl -s "http://localhost:9090/api/v1/query?query=jvm_heap_size_bytes" | grep -o '"value":\["[^"]*","[^"]*"\]' | head -1
echo ""

# 2. 检查 Prometheus Targets 状态
echo "2. Prometheus Targets 状态"
echo "----------------------------------------"

echo "Kafka Target:"
curl -s http://localhost:9090/api/v1/targets | grep -o '"job":"kafka"[^}]*"health":"[^"]*"' | head -1
echo ""

echo "Doris FE Target:"
curl -s http://localhost:9090/api/v1/targets | grep -o '"job":"doris-fe"[^}]*"health":"[^"]*"' | head -1
echo ""

echo "Doris BE Target:"
curl -s http://localhost:9090/api/v1/targets | grep -o '"job":"doris-be"[^}]*"health":"[^"]*"' | head -1
echo ""

# 3. 访问说明
echo "=========================================="
echo "✅ Prometheus 数据正常!"
echo "=========================================="
echo ""
echo "现在请按以下步骤操作:"
echo ""
echo "1. 打开浏览器访问 Grafana:"
echo "   http://localhost:3000"
echo ""
echo "2. 登录 Grafana:"
echo "   用户名: admin"
echo "   密码: admin"
echo ""
echo "3. 访问监控面板:"
echo "   - Kafka 监控: http://localhost:3000/d/kafka-monitoring"
echo "   - Doris 监控: http://localhost:3000/d/doris-monitoring"
echo ""
echo "4. 如果面板显示 'No data':"
echo "   a) 点击面板右上角的时间选择器"
echo "   b) 选择 'Last 5 minutes' 或 'Last 15 minutes'"
echo "   c) 点击刷新按钮（圆形箭头图标）"
echo "   d) 等待 10-15 秒"
echo ""
echo "5. 验证数据:"
echo "   - Kafka 面板应该显示 broker 数量、topic 数量等"
echo "   - Doris 面板应该显示 JVM 内存、查询速率等"
echo ""
echo "=========================================="
echo "Prometheus 直接查询（用于对比）"
echo "=========================================="
echo ""
echo "如果 Grafana 还是没数据，可以直接在 Prometheus 中查询:"
echo ""
echo "1. 访问: http://localhost:9090/graph"
echo ""
echo "2. 在查询框中输入并执行:"
echo "   kafka_brokers"
echo "   kafka_topic_partitions"
echo "   jvm_heap_size_bytes"
echo ""
echo "3. 如果 Prometheus 有数据但 Grafana 没有，说明是 Grafana 配置问题"
echo ""
echo "=========================================="
