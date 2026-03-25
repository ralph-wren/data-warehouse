#!/bin/bash

# Doris 管理脚本
# 用法: ./manage-doris.sh [start|stop|restart]
# 默认: restart

set -e

ACTION=${1:-restart}

echo "=========================================="
echo "Doris 管理 - $ACTION"
echo "=========================================="

case "$ACTION" in
    start)
        echo "启动 Doris 集群..."
        docker-compose -f docker-compose-doris.yml up -d
        
        echo "等待 Doris 启动..."
        sleep 20
        
        echo "✅ Doris 启动完成"
        echo "FE Web UI: http://localhost:8030"
        echo "BE Web UI: http://localhost:8040"
        ;;
        
    stop)
        echo "停止 Doris 集群..."
        docker-compose -f docker-compose-doris.yml down
        echo "✅ Doris 已停止"
        ;;
        
    restart)
        echo "重启 Doris 集群..."
        docker-compose -f docker-compose-doris.yml down
        sleep 3
        docker-compose -f docker-compose-doris.yml up -d
        ;;
        
    *)
        echo "错误: 未知操作 '$ACTION'"
        echo "用法: $0 [start|stop|restart]"
        exit 1
        ;;
esac

echo ""
echo "Doris 状态:"
docker-compose -f docker-compose-doris.yml ps
