#!/bin/bash

# Doris BE 地址修复脚本 - 远程集群模式
# 用于清理无效的 BE 节点并验证网络连通性

echo "=========================================="
echo "Doris BE 地址修复 - 远程集群模式"
echo "=========================================="

# 1. 检查 Doris 容器状态
echo "步骤 1: 检查 Doris 容器状态..."
docker ps --filter "name=doris" --format "table {{.Names}}\t{{.Status}}\t{{.Ports}}"

# 2. 查看当前 BE 节点
echo ""
echo "步骤 2: 查看当前 BE 节点..."
docker exec doris-fe mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" | grep -E "(BackendId|Host|Alive)"

# 3. 清理无效的 BE 节点
echo ""
echo "步骤 3: 清理无效的 BE 节点..."

# 删除 127.0.0.1 节点
echo "删除 127.0.0.1:9050 节点..."
docker exec doris-fe mysql -h 127.0.0.1 -P 9030 -u root -e "ALTER SYSTEM DROPP BACKEND '127.0.0.1:9050';" 2>/dev/null || echo "节点不存在或已删除"

# 删除 host.docker.internal 节点
echo "删除 host.docker.internal:9050 节点..."
docker exec doris-fe mysql -h 127.0.0.1 -P 9030 -u root -e "ALTER SYSTEM DROPP BACKEND 'host.docker.internal:9050';" 2>/dev/null || echo "节点不存在或已删除"

# 4. 验证只剩一个正常的 BE 节点
echo ""
echo "步骤 4: 验证 BE 节点..."
BE_COUNT=$(docker exec doris-fe mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS;" | grep -c "Alive")
ALIVE_COUNT=$(docker exec doris-fe mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS;" | grep -c "true")

echo "BE 节点总数: $BE_COUNT"
echo "存活节点数: $ALIVE_COUNT"

if [ "$ALIVE_COUNT" -eq 1 ]; then
    echo "✅ BE 节点正常"
else
    echo "⚠️ BE 节点异常,请检查"
fi

# 5. 验证 Flink TaskManager 网络连通性
echo ""
echo "步骤 5: 验证 Flink TaskManager 网络连通性..."

# 检查 TaskManager 是否在 doris-net 网络中
echo "检查 TaskManager 网络..."
docker network inspect data-warehouse_doris-net --format '{{range .Containers}}{{.Name}} {{end}}' | grep -q "flink-taskmanager"
if [ $? -eq 0 ]; then
    echo "✅ TaskManager 已连接到 doris-net 网络"
else
    echo "❌ TaskManager 未连接到 doris-net 网络"
    exit 1
fi

# 测试 TaskManager 访问 Doris BE
echo "测试 TaskManager 访问 Doris BE..."
docker exec flink-taskmanager-1 curl -s -o /dev/null -w "%{http_code}" http://doris-be:8040/api/health
if [ $? -eq 0 ]; then
    echo "✅ TaskManager 可以访问 Doris BE"
else
    echo "❌ TaskManager 无法访问 Doris BE"
    exit 1
fi

# 6. 显示当前配置
echo ""
echo "步骤 6: 当前配置..."
echo "BE 地址配置: doris-be:8040"
echo "FE 地址配置: 127.0.0.1:9030"

echo ""
echo "=========================================="
echo "修复完成!"
echo "现在可以重新提交 Flink 作业"
echo "=========================================="
