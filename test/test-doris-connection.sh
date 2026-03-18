#!/bin/bash

# Doris 连接测试脚本

echo "=========================================="
echo "Doris 连接测试"
echo "=========================================="
echo ""

# 测试 MySQL 连接
echo "测试 Doris MySQL 端口 (9030)..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT VERSION();" 2>/dev/null
if [ $? -eq 0 ]; then
    echo "✓ MySQL 连接成功"
else
    echo "❌ MySQL 连接失败"
    exit 1
fi

echo ""

# 测试 HTTP 连接
echo "测试 Doris HTTP 端口 (8030)..."
curl -s http://127.0.0.1:8030/api/bootstrap > /dev/null
if [ $? -eq 0 ]; then
    echo "✓ HTTP 连接成功"
else
    echo "❌ HTTP 连接失败"
    exit 1
fi

echo ""

# 检查数据库
echo "检查 crypto_dw 数据库..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW DATABASES LIKE 'crypto_dw';" 2>/dev/null | grep crypto_dw > /dev/null
if [ $? -eq 0 ]; then
    echo "✓ crypto_dw 数据库存在"
else
    echo "⚠ crypto_dw 数据库不存在，正在创建..."
    mysql -h 127.0.0.1 -P 9030 -u root -e "CREATE DATABASE IF NOT EXISTS crypto_dw;"
    echo "✓ crypto_dw 数据库已创建"
fi

echo ""

# 显示数据库列表
echo "当前数据库列表:"
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW DATABASES;"

echo ""
echo "=========================================="
echo "Doris 连接测试完成"
echo "=========================================="
