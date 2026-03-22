#!/bin/bash
# 测试 Grafana 到 Doris 的连接

echo "========================================="
echo "测试 Grafana 到 Doris 的连接"
echo "========================================="
echo ""

# 1. 测试从 Grafana 容器连接 Doris
echo "1. 从 Grafana 容器测试 MySQL 连接..."
docker exec grafana sh -c "apk add --no-cache mysql-client 2>/dev/null || true"
docker exec grafana sh -c "mysql -h doris-fe -P 9030 -u root -e 'SELECT COUNT(*) as total FROM crypto_dw.dwd_crypto_ticker_detail;'" 2>&1

if [ $? -eq 0 ]; then
    echo "✅ Grafana 可以连接到 Doris"
else
    echo "❌ Grafana 无法连接到 Doris"
    echo ""
    echo "尝试安装 MySQL 客户端并重试..."
    docker exec grafana sh -c "apk add mysql-client"
    docker exec grafana sh -c "mysql -h doris-fe -P 9030 -u root -e 'SELECT COUNT(*) as total FROM crypto_dw.dwd_crypto_ticker_detail;'"
fi

echo ""
echo "========================================="
echo "请在 Grafana Web UI 中手动操作:"
echo "========================================="
echo ""
echo "1. 访问 http://localhost:3000"
echo "2. 登录 (admin/admin)"
echo "3. 进入 Configuration → Data sources"
echo "4. 查找 'Doris' 数据源"
echo "5. 点击 'Test' 按钮测试连接"
echo ""
echo "如果数据源不存在,请手动添加:"
echo "  - Type: MySQL"
echo "  - Host: doris-fe:9030"
echo "  - Database: crypto_dw"
echo "  - User: root"
echo "  - Password: (留空)"
echo ""
