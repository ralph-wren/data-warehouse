#!/bin/bash

# Superset 管理脚本
# 用于启动、停止、重启 Superset BI 工具

COMPOSE_FILE="docker-compose-superset.yml"

case "$1" in
    start)
        echo "启动 Superset..."
        docker-compose -f $COMPOSE_FILE up -d
        echo ""
        echo "Superset 启动完成!"
        echo "访问地址: http://localhost:8088"
        echo "默认账号: admin / admin"
        echo ""
        echo "首次启动需要等待 1-2 分钟初始化..."
        ;;
    
    stop)
        echo "停止 Superset..."
        docker-compose -f $COMPOSE_FILE down
        echo "Superset 已停止"
        ;;
    
    restart)
        echo "重启 Superset..."
        docker-compose -f $COMPOSE_FILE restart
        echo "Superset 已重启"
        ;;
    
    logs)
        echo "查看 Superset 日志..."
        docker-compose -f $COMPOSE_FILE logs -f superset
        ;;
    
    status)
        echo "Superset 状态:"
        docker-compose -f $COMPOSE_FILE ps
        ;;
    
    clean)
        echo "清理 Superset (包括数据)..."
        read -p "确认删除所有数据? (y/N): " confirm
        if [ "$confirm" = "y" ]; then
            docker-compose -f $COMPOSE_FILE down -v
            rm -rf superset
            echo "Superset 数据已清理"
        else
            echo "取消清理"
        fi
        ;;
    
    *)
        echo "Superset 管理脚本"
        echo ""
        echo "使用方式: bash $0 {start|stop|restart|logs|status|clean}"
        echo ""
        echo "命令说明:"
        echo "  start   - 启动 Superset"
        echo "  stop    - 停止 Superset"
        echo "  restart - 重启 Superset"
        echo "  logs    - 查看日志"
        echo "  status  - 查看状态"
        echo "  clean   - 清理数据"
        exit 1
        ;;
esac
