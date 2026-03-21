#!/bin/bash

# Flink 集群管理脚本
# 用法: ./manage-flink.sh [start|stop|restart]
# 默认: restart

set -e

ACTION=${1:-restart}

echo "=========================================="
echo "Flink 集群管理 - $ACTION"
echo "=========================================="

case "$ACTION" in
    start)
        echo "启动 Flink 集群..."
        docker-compose -f docker-compose-flink.yml up -d
        
        echo "等待 Flink 启动..."
        sleep 10
        
        # 验证 Java 版本
        echo "验证 Java 版本..."
        echo "JobManager:"
        docker exec flink-jobmanager java -version 2>&1 | head -1
        echo "TaskManager-1:"
        docker exec flink-taskmanager-1 java -version 2>&1 | head -1
        echo "TaskManager-2:"
        docker exec flink-taskmanager-2 java -version 2>&1 | head -1
        
        echo "✅ Flink 启动完成"
        echo "Web UI: http://localhost:8081"
        ;;
        
    stop)
        echo "停止 Flink 集群..."
        docker-compose -f docker-compose-flink.yml down
        echo "✅ Flink 已停止"
        ;;
        
    restart)
        echo "重启 Flink 集群..."
        bash manage-flink.sh stop
        sleep 3
        bash manage-flink.sh start
        ;;
        
    *)
        echo "错误: 未知操作 '$ACTION'"
        echo "用法: $0 [start|stop|restart]"
        exit 1
        ;;
esac

echo ""
echo "Flink 状态:"
docker-compose -f docker-compose-flink.yml ps
