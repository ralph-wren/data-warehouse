#!/bin/bash

# Flink Docker 集群启动脚本

echo "=========================================="
echo "启动 Flink Docker 集群"
echo "=========================================="

# 启动 Flink 集群
docker-compose -f docker-compose-flink.yml up -d

# 等待启动
echo "等待 Flink 集群启动..."
sleep 10

# 检查状态
if docker ps | grep -q flink-jobmanager; then
    echo ""
    echo "=========================================="
    echo "Flink 集群启动成功！"
    echo "=========================================="
    echo ""
    echo "Flink Web UI: http://localhost:8081"
    echo "JobManager RPC: localhost:6123"
    echo ""
    echo "StreamPark 配置:"
    echo "  - 集群类型: Standalone"
    echo "  - JobManager: jobmanager:6123"
    echo "  - Web UI: http://jobmanager:8081"
    echo ""
    echo "查看日志:"
    echo "  - JobManager: docker logs -f flink-jobmanager"
    echo "  - TaskManager: docker logs -f flink-taskmanager"
    echo ""
    echo "停止集群: ./stop-flink-docker.sh"
    echo ""
else
    echo ""
    echo "错误: Flink 集群启动失败"
    echo "查看日志: docker logs flink-jobmanager"
    exit 1
fi
