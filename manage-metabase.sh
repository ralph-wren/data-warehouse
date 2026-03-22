#!/bin/bash
# Metabase 管理脚本

COMPOSE_FILE="docker-compose-metabase.yml"

case "$1" in
    start)
        echo "启动 Metabase..."
        docker-compose -f $COMPOSE_FILE up -d
        echo ""
        echo "Metabase 正在启动,请等待约 30-60 秒..."
        echo "访问地址: http://localhost:3001"
        echo ""
        echo "首次访问需要进行初始化设置:"
        echo "1. 创建管理员账号"
        echo "2. 添加 Doris 数据库连接"
        echo "   - 数据库类型: MySQL"
        echo "   - 主机: doris-fe"
        echo "   - 端口: 9030"
        echo "   - 数据库名: crypto_dw"
        echo "   - 用户名: root"
        echo "   - 密码: (留空)"
        ;;
    stop)
        echo "停止 Metabase..."
        docker-compose -f $COMPOSE_FILE stop
        ;;
    restart)
        echo "重启 Metabase..."
        docker-compose -f $COMPOSE_FILE restart
        ;;
    logs)
        docker-compose -f $COMPOSE_FILE logs -f metabase
        ;;
    status)
        docker-compose -f $COMPOSE_FILE ps
        ;;
    down)
        echo "停止并删除 Metabase 容器..."
        docker-compose -f $COMPOSE_FILE down
        ;;
    clean)
        echo "警告: 这将删除所有 Metabase 数据!"
        read -p "确认删除? (yes/no): " confirm
        if [ "$confirm" = "yes" ]; then
            docker-compose -f $COMPOSE_FILE down -v
            echo "Metabase 数据已删除"
        else
            echo "取消操作"
        fi
        ;;
    *)
        echo "Metabase 管理脚本"
        echo ""
        echo "用法: $0 {start|stop|restart|logs|status|down|clean}"
        echo ""
        echo "命令说明:"
        echo "  start   - 启动 Metabase"
        echo "  stop    - 停止 Metabase"
        echo "  restart - 重启 Metabase"
        echo "  logs    - 查看日志"
        echo "  status  - 查看状态"
        echo "  down    - 停止并删除容器"
        echo "  clean   - 删除所有数据(包括配置)"
        exit 1
        ;;
esac
