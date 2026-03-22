#!/bin/bash
# Doris 时区验证脚本
# 用于验证 Doris 时区配置是否正确

echo "========================================="
echo "Doris 时区配置验证"
echo "========================================="
echo ""

# 1. 检查 Doris 容器状态
echo "1. 检查 Doris 容器状态..."
docker ps --filter "name=doris" --format "table {{.Names}}\t{{.Status}}" || {
    echo "❌ Doris 容器未运行"
    exit 1
}
echo ""

# 2. 验证时区设置
echo "2. 验证时区设置..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW VARIABLES LIKE 'time_zone';" || {
    echo "❌ 无法连接到 Doris"
    exit 1
}
echo ""

# 3. 验证当前时间
echo "3. 验证当前时间(应该显示东八区时间)..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SELECT NOW() AS '当前时间', UNIX_TIMESTAMP(NOW()) AS '时间戳';" || {
    echo "❌ 查询失败"
    exit 1
}
echo ""

# 4. 验证数据时间显示
echo "4. 验证数据时间显示(应该显示东八区时间)..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "
USE crypto_dw;
SELECT 
    inst_id AS 币种,
    last_price AS 最新价格,
    FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
    SELECT 
        inst_id,
        last_price,
        timestamp,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
    FROM dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY last_price DESC
LIMIT 3;
" || {
    echo "❌ 查询失败"
    exit 1
}
echo ""

# 5. 验证数据统计
echo "5. 验证数据统计..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "
USE crypto_dw;
SELECT 
    '数据验证' AS 类型,
    COUNT(*) AS 总数,
    COUNT(DISTINCT inst_id) AS 币种数,
    MIN(FROM_UNIXTIME(timestamp / 1000)) AS 最早时间,
    MAX(FROM_UNIXTIME(timestamp / 1000)) AS 最新时间
FROM dwd_crypto_ticker_detail;
" || {
    echo "❌ 查询失败"
    exit 1
}
echo ""

echo "========================================="
echo "✅ 验证完成!"
echo "========================================="
echo ""
echo "说明:"
echo "- 时区应该显示: Asia/Shanghai"
echo "- 当前时间应该是东八区时间(北京时间)"
echo "- 数据时间应该是东八区时间"
echo ""
echo "如果时间显示不正确,请执行以下命令:"
echo "  docker-compose -f docker-compose-doris.yml restart"
echo ""
