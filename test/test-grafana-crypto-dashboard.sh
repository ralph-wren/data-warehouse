#!/bin/bash
# Grafana 加密货币数据面板测试脚本
# 用于验证 Grafana 是否成功连接 Doris 并显示数据

echo "========================================="
echo "Grafana 加密货币数据面板测试"
echo "========================================="
echo ""

# 1. 检查 Grafana 容器状态
echo "1. 检查 Grafana 容器状态..."
docker ps --filter "name=grafana" --format "table {{.Names}}\t{{.Status}}" || {
    echo "❌ Grafana 容器未运行"
    exit 1
}
echo ""

# 2. 检查 Doris 容器状态
echo "2. 检查 Doris 容器状态..."
docker ps --filter "name=doris" --format "table {{.Names}}\t{{.Status}}" || {
    echo "❌ Doris 容器未运行"
    exit 1
}
echo ""

# 3. 测试 Grafana 到 Doris 的网络连通性
echo "3. 测试 Grafana 到 Doris 的网络连通性..."
docker exec grafana ping -c 3 doris-fe || {
    echo "❌ Grafana 无法连接到 Doris FE"
    exit 1
}
echo ""

# 4. 测试 Doris MySQL 端口
echo "4. 测试 Doris MySQL 端口 (9030)..."
docker exec grafana nc -zv doris-fe 9030 || {
    echo "❌ Doris MySQL 端口不可访问"
    exit 1
}
echo ""

# 5. 验证数据源配置文件
echo "5. 验证数据源配置文件..."
if [ -f "volumes/monitoring/grafana/provisioning/datasources/doris-mysql.yml" ]; then
    echo "✅ Doris 数据源配置文件存在"
    cat volumes/monitoring/grafana/provisioning/datasources/doris-mysql.yml
else
    echo "❌ Doris 数据源配置文件不存在"
    exit 1
fi
echo ""

# 6. 验证面板配置文件
echo "6. 验证面板配置文件..."
if [ -f "volumes/monitoring/grafana/dashboards/crypto-data-dashboard.json" ]; then
    echo "✅ 加密货币数据面板配置文件存在"
    echo "面板标题: $(cat volumes/monitoring/grafana/dashboards/crypto-data-dashboard.json | grep -o '"title":"[^"]*"' | head -1)"
else
    echo "❌ 加密货币数据面板配置文件不存在"
    exit 1
fi
echo ""

# 7. 测试 Doris 数据查询
echo "7. 测试 Doris 数据查询..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "
USE crypto_dw;
SELECT 
    '数据验证' AS 类型,
    COUNT(*) AS 总数,
    COUNT(DISTINCT inst_id) AS 币种数
FROM dwd_crypto_ticker_detail;
" || {
    echo "❌ Doris 数据查询失败"
    exit 1
}
echo ""

echo "========================================="
echo "✅ 测试完成!"
echo "========================================="
echo ""
echo "访问 Grafana 查看加密货币数据面板:"
echo "  URL: http://localhost:3000"
echo "  用户名: admin"
echo "  密码: admin"
echo ""
echo "面板路径:"
echo "  Dashboards → 加密货币实时数据监控"
echo ""
echo "面板包含以下内容:"
echo "  1. 总数据量统计"
echo "  2. 监控币种数量"
echo "  3. BTC 最新价格"
echo "  4. ETH 最新价格"
echo "  5. 各币种最新价格排行表"
echo "  6. 主流币种价格趋势图 (最近1小时)"
echo "  7. 24小时成交量分布饼图"
echo "  8. 数据更新频率柱状图"
echo ""
echo "提示:"
echo "  - 面板每 10 秒自动刷新"
echo "  - 时区已设置为 Asia/Shanghai (东八区)"
echo "  - 可以调整时间范围查看历史数据"
echo ""
