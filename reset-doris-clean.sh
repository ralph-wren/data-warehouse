#!/bin/bash

# Doris 完全重置脚本
# 清理所有数据和容器,重新开始

echo "=========================================="
echo "Doris 完全重置 (清理所有数据)"
echo "=========================================="
echo ""

# 检查 docker-compose
COMPOSE_CMD=""
if command -v docker-compose &> /dev/null; then
    COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null 2>&1; then
    COMPOSE_CMD="docker compose"
else
    echo "错误: 需要 docker-compose 或 docker compose 插件"
    exit 1
fi

echo "警告: 此操作将删除所有 Doris 数据!"
echo "按 Ctrl+C 取消,或按 Enter 继续..."
read

# 1. 停止并删除容器
echo "1. 停止并删除容器..."
$COMPOSE_CMD -f docker-compose-doris.yml down -v

# 2. 删除数据卷
echo "2. 删除数据卷..."
docker volume rm data-warehouse_doris-fe-data 2>/dev/null || true
docker volume rm data-warehouse_doris-fe-log 2>/dev/null || true
docker volume rm data-warehouse_doris-be-data 2>/dev/null || true
docker volume rm data-warehouse_doris-be-log 2>/dev/null || true

# 3. 删除网络
echo "3. 删除网络..."
docker network rm data-warehouse_doris-net 2>/dev/null || true

echo ""
echo "✓ Doris 已完全清理"
echo ""
echo "下一步: 运行 ./start-doris-for-flink.sh 重新部署"
echo ""
