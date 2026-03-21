#!/bin/bash

# StreamPark 启动脚本
# 用于启动 StreamPark 服务

echo "=========================================="
echo "启动 StreamPark 服务"
echo "=========================================="

# 检查 Docker 是否运行
if ! docker info > /dev/null 2>&1; then
    echo "错误: Docker 未运行，请先启动 Docker"
    exit 1
fi

# 检查必要的网络是否存在
echo "检查 Docker 网络..."
if ! docker network inspect data-warehouse_doris-net > /dev/null 2>&1; then
    echo "警告: Doris 网络不存在，StreamPark 将无法直接连接 Doris"
    echo "请先启动 Doris: docker-compose -f docker-compose-doris.yml up -d"
fi

if ! docker network inspect data-warehouse_default > /dev/null 2>&1; then
    echo "警告: 默认网络不存在，StreamPark 将无法连接 Kafka"
fi

# 启动 StreamPark
echo "启动 StreamPark 容器..."
docker-compose -f docker-compose-streampark.yml up -d

# 等待服务启动
echo "等待 StreamPark 服务启动..."
sleep 10

# 检查服务状态
if docker ps | grep -q streampark; then
    echo ""
    echo "=========================================="
    echo "StreamPark 启动成功！"
    echo "=========================================="
    echo ""
    echo "访问地址: http://localhost:10000"
    echo "默认账号: admin"
    echo "默认密码: streampark"
    echo ""
    echo "查看日志: docker logs -f streampark"
    echo "停止服务: docker-compose -f docker-compose-streampark.yml down"
    echo ""
else
    echo ""
    echo "错误: StreamPark 启动失败"
    echo "查看日志: docker logs streampark"
    exit 1
fi
