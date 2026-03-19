#!/bin/bash

# Doris 部署脚本 - 确保 Flink 能够连通
# 使用桥接网络 + 自动修复 BE 地址

echo "=========================================="
echo "启动 Doris 3.0.7 (Flink 兼容模式)"
echo "=========================================="
echo ""

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "错误: 未检测到 Docker,请先安装 Docker"
    exit 1
fi

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

echo "使用命令: $COMPOSE_CMD"
echo ""

# 1. 停止旧容器
echo "1. 停止旧的 Doris 容器..."
docker stop doris-fe doris-be doris_fe 2>/dev/null
docker rm doris-fe doris-be doris_fe 2>/dev/null

# 2. 使用 docker-compose-doris.yml 启动
echo "2. 启动 Doris (桥接网络模式)..."
if [ ! -f "docker-compose-doris.yml" ]; then
    echo "错误: 找不到 docker-compose-doris.yml 文件"
    exit 1
fi

$COMPOSE_CMD -f docker-compose-doris.yml up -d

if [ $? -ne 0 ]; then
    echo "错误: Doris 启动失败"
    exit 1
fi

echo ""
echo "3. 等待 Doris 启动..."
echo "   FE 需要约 30-40 秒..."

# 等待 FE 启动
for i in {1..40}; do
    if mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" &>/dev/null; then
        echo "   ✓ FE 启动成功 (用时 ${i} 秒)"
        break
    fi
    sleep 1
done

if ! mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" &>/dev/null; then
    echo "   ✗ FE 启动超时,请检查日志: docker logs doris-fe"
    exit 1
fi

echo "   BE 需要约 20-30 秒..."
sleep 25

echo ""
echo "4. 检查 Doris 状态..."

# 检查容器状态
echo ""
echo "容器状态:"
docker ps | grep -E "CONTAINER|doris"

# 检查 FE 连接
echo ""
echo "FE 连接测试:"
if mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" &>/dev/null; then
    echo "✓ FE 连接成功 (127.0.0.1:9030)"
else
    echo "✗ FE 连接失败,请检查日志:"
    echo "  docker logs doris-fe"
    exit 1
fi

# 等待 BE 注册
echo ""
echo "5. 等待 BE 注册..."
for i in {1..30}; do
    BE_COUNT=$(mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep -c "BackendId:")
    if [ "$BE_COUNT" -gt 0 ]; then
        echo "   ✓ BE 已注册 (用时 ${i} 秒)"
        break
    fi
    sleep 1
done

# 检查 BE 状态
echo ""
echo "BE 节点状态:"
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep -E "Host|HttpPort|Alive|TabletNum"

# 提取 BE 地址
BE_HOST=$(mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep "Host:" | awk '{print $2}')
BE_PORT=$(mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep "HeartbeatPort:" | awk '{print $2}')

echo ""
if [[ $BE_HOST == "127.0.0.1" ]]; then
    echo "✓ BE 地址正确: $BE_HOST"
    
elif [[ $BE_HOST == "doris-be" ]] || [[ $BE_HOST == 172.* ]]; then
    echo "⚠ BE 地址需要修复: $BE_HOST"
    echo ""
    echo "6. 修复 BE 地址..."
    
    # 删除旧 BE
    echo "   删除旧 BE: ${BE_HOST}:${BE_PORT}"
    mysql -h 127.0.0.1 -P 9030 -u root -e "ALTER SYSTEM DROPP BACKEND '${BE_HOST}:${BE_PORT}' FORCE;" 2>/dev/null
    
    sleep 2
    
    # 添加新 BE
    echo "   添加新 BE: 127.0.0.1:9050"
    mysql -h 127.0.0.1 -P 9030 -u root -e "ALTER SYSTEM ADD BACKEND '127.0.0.1:9050';" 2>/dev/null
    
    sleep 5
    
    # 验证
    echo ""
    echo "   验证修复结果:"
    mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep -E "Host|Alive"
    
    BE_HOST=$(mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep "Host:" | awk '{print $2}')
    if [[ $BE_HOST == "127.0.0.1" ]]; then
        echo "   ✓ BE 地址修复成功"
    else
        echo "   ✗ BE 地址修复失败,当前地址: $BE_HOST"
    fi
    
else
    echo "⚠ 未检测到 BE 节点,可能还在启动中..."
    echo ""
    echo "请等待 1-2 分钟后检查:"
    echo "  mysql -h 127.0.0.1 -P 9030 -u root -e 'SHOW BACKENDS\\G'"
fi

# 检查 BE HTTP 端口
echo ""
if curl -s http://127.0.0.1:8040/api/health &>/dev/null; then
    echo "✓ BE HTTP 端口可访问 (127.0.0.1:8040)"
else
    echo "⚠ BE HTTP 端口暂时不可访问,请等待 BE 完全启动"
fi

echo ""
echo "=========================================="
echo "Doris 启动完成!"
echo "=========================================="
echo ""
echo "连接信息:"
echo "  MySQL: mysql -h 127.0.0.1 -P 9030 -u root"
echo "  FE Web UI: http://127.0.0.1:8030"
echo "  BE Web UI: http://127.0.0.1:8040"
echo ""
echo "管理命令:"
echo "  查看日志: $COMPOSE_CMD -f docker-compose-doris.yml logs -f"
echo "  停止服务: $COMPOSE_CMD -f docker-compose-doris.yml down"
echo "  重启服务: $COMPOSE_CMD -f docker-compose-doris.yml restart"
echo ""
echo "下一步:"
echo "  1. 验证连接: ./test/verify-doris-flink-connection.sh"
echo "  2. 创建数据库和表: mysql -h 127.0.0.1 -P 9030 -u root < sql/create_tables.sql"
echo "  3. 运行 Flink 作业: ./run-flink-ods-datastream.sh"
echo ""
