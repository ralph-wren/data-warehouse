#!/bin/bash

# StreamPark 管理脚本
# 用法: ./manage-streampark.sh [start|stop|restart]
# 默认: restart

set -e

ACTION=${1:-restart}

echo "=========================================="
echo "StreamPark 管理 - $ACTION"
echo "=========================================="

case "$ACTION" in
    start)
        echo "启动 StreamPark..."
        docker-compose -f docker-compose-streampark.yml up -d
        
        echo "等待 StreamPark 启动..."
        sleep 15
        
        echo "✅ StreamPark 启动完成"
        echo "Web UI: http://localhost:10000"
        echo "默认账号: admin / streampark"
        ;;
        
    stop)
        echo "停止 StreamPark..."
        docker-compose -f docker-compose-streampark.yml down
        echo "✅ StreamPark 已停止"
        ;;
        
    restart)
        echo "重启 StreamPark..."
        $0 stop
        sleep 3
        $0 start
        ;;
        
    *)
        echo "错误: 未知操作 '$ACTION'"
        echo "用法: $0 [start|stop|restart]"
        exit 1
        ;;
esac

echo ""
echo "StreamPark 状态:"
docker-compose -f docker-compose-streampark.yml ps
