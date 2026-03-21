#!/bin/bash

# 监控系统管理脚本
# 用法: ./manage-monitoring.sh [start|stop|restart]
# 默认: restart

set -e

ACTION=${1:-restart}

echo "=========================================="
echo "监控系统管理 - $ACTION"
echo "=========================================="

case "$ACTION" in
    start)
        echo "启动监控系统..."
        docker-compose -f docker-compose-monitoring.yml up -d
        
        echo "等待监控系统启动..."
        sleep 10
        
        echo "✅ 监控系统启动完成"
        echo "Prometheus: http://localhost:9090"
        echo "Grafana: http://localhost:3000 (admin/admin)"
        echo "Pushgateway: http://localhost:9091"
        ;;
        
    stop)
        echo "停止监控系统..."
        docker-compose -f docker-compose-monitoring.yml down
        echo "✅ 监控系统已停止"
        ;;
        
    restart)
        echo "重启监控系统..."
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
echo "监控系统状态:"
docker-compose -f docker-compose-monitoring.yml ps
