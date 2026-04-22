#!/bin/bash
# 重新导入 Grafana 面板
# 解决 Prometheus 有数据但 Grafana 没有数据的问题

echo "=========================================="
echo "重新导入 Grafana 面板"
echo "=========================================="
echo ""

# Grafana 配置
GRAFANA_URL="http://localhost:3000"
GRAFANA_USER="admin"
GRAFANA_PASS="admin"

# 1. 等待 Grafana 启动
echo "1. 等待 Grafana 启动..."
for i in {1..30}; do
    if curl -s ${GRAFANA_URL}/api/health > /dev/null 2>&1; then
        echo "✓ Grafana 已启动"
        break
    fi
    echo "  等待中... ($i/30)"
    sleep 2
done

echo ""

# 2. 检查数据源
echo "2. 检查 Prometheus 数据源..."
DATASOURCE_CHECK=$(curl -s -u ${GRAFANA_USER}:${GRAFANA_PASS} \
    ${GRAFANA_URL}/api/datasources/name/Prometheus 2>&1)

if echo "$DATASOURCE_CHECK" | grep -q "\"name\":\"Prometheus\""; then
    echo "✓ Prometheus 数据源已配置"
elif echo "$DATASOURCE_CHECK" | grep -q "401"; then
    echo "⚠ 需要登录 Grafana"
    echo ""
    echo "请按以下步骤操作:"
    echo "1. 打开浏览器访问: ${GRAFANA_URL}"
    echo "2. 使用默认密码登录: admin/admin"
    echo "3. 如果提示修改密码,可以跳过或设置新密码"
    echo "4. 然后重新运行此脚本"
    exit 1
else
    echo "✗ Prometheus 数据源未配置"
    echo "  响应: $DATASOURCE_CHECK"
fi

echo ""

# 3. 测试数据源连接
echo "3. 测试 Prometheus 连接..."
TEST_RESULT=$(curl -s -u ${GRAFANA_USER}:${GRAFANA_PASS} \
    -X POST ${GRAFANA_URL}/api/datasources/proxy/1/api/v1/query \
    -H "Content-Type: application/x-www-form-urlencoded" \
    -d "query=up" 2>&1)

if echo "$TEST_RESULT" | grep -q "\"status\":\"success\""; then
    echo "✓ Prometheus 连接正常"
else
    echo "⚠ Prometheus 连接测试失败"
    echo "  响应: $TEST_RESULT"
fi

echo ""

# 4. 导入面板
echo "4. 导入监控面板..."

# 导入 Kafka 面板
if [ -f "volumes/monitoring/grafana/dashboards/kafka-monitoring.json" ]; then
    echo "  导入 Kafka 监控面板..."
    KAFKA_RESULT=$(curl -s -u ${GRAFANA_USER}:${GRAFANA_PASS} \
        -X POST ${GRAFANA_URL}/api/dashboards/db \
        -H "Content-Type: application/json" \
        -d @volumes/monitoring/grafana/dashboards/kafka-monitoring.json 2>&1)
    
    if echo "$KAFKA_RESULT" | grep -q "\"status\":\"success\""; then
        echo "  ✓ Kafka 面板导入成功"
    elif echo "$KAFKA_RESULT" | grep -q "401"; then
        echo "  ⚠ 需要登录"
    else
        echo "  ⚠ Kafka 面板导入失败"
        echo "  响应: $KAFKA_RESULT"
    fi
else
    echo "  ✗ Kafka 面板文件不存在"
fi

# 导入 Doris 面板
if [ -f "volumes/monitoring/grafana/dashboards/doris-monitoring.json" ]; then
    echo "  导入 Doris 监控面板..."
    DORIS_RESULT=$(curl -s -u ${GRAFANA_USER}:${GRAFANA_PASS} \
        -X POST ${GRAFANA_URL}/api/dashboards/db \
        -H "Content-Type: application/json" \
        -d @volumes/monitoring/grafana/dashboards/doris-monitoring.json 2>&1)
    
    if echo "$DORIS_RESULT" | grep -q "\"status\":\"success\""; then
        echo "  ✓ Doris 面板导入成功"
    elif echo "$DORIS_RESULT" | grep -q "401"; then
        echo "  ⚠ 需要登录"
    else
        echo "  ⚠ Doris 面板导入失败"
        echo "  响应: $DORIS_RESULT"
    fi
else
    echo "  ✗ Doris 面板文件不存在"
fi

echo ""

# 5. 访问说明
echo "=========================================="
echo "✅ 完成!"
echo "=========================================="
echo ""
echo "现在请访问 Grafana 查看面板:"
echo ""
echo "1. 打开浏览器: ${GRAFANA_URL}"
echo ""
echo "2. 登录 (如果还没登录):"
echo "   用户名: ${GRAFANA_USER}"
echo "   密码: ${GRAFANA_PASS}"
echo ""
echo "3. 访问面板:"
echo "   - 点击左侧菜单 'Dashboards'"
echo "   - 或直接访问:"
echo "     Kafka: ${GRAFANA_URL}/d/kafka-monitoring"
echo "     Doris: ${GRAFANA_URL}/d/doris-monitoring"
echo ""
echo "4. 如果面板显示 'No data':"
echo "   - 点击右上角时间选择器"
echo "   - 选择 'Last 5 minutes'"
echo "   - 点击刷新按钮"
echo ""
echo "=========================================="
echo "故障排查"
echo "=========================================="
echo ""
echo "如果还是没有数据:"
echo ""
echo "1. 在 Grafana 中手动测试查询:"
echo "   - 点击 'Explore' (左侧菜单)"
echo "   - 选择 'Prometheus' 数据源"
echo "   - 输入查询: kafka_brokers"
echo "   - 点击 'Run query'"
echo "   - 应该能看到值为 1"
echo ""
echo "2. 检查面板的数据源设置:"
echo "   - 打开面板"
echo "   - 点击面板标题 -> Edit"
echo "   - 检查右侧 'Data source' 是否选择了 'Prometheus'"
echo ""
echo "3. 检查 Prometheus 是否有数据:"
echo "   访问: http://localhost:9090/graph"
echo "   查询: kafka_brokers"
echo ""
