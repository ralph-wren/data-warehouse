#!/bin/bash

# StreamPark 管理脚本
# 用法: ./manage-streampark.sh [start|stop|restart]
# 默认: restart

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-streampark.yml"
MYSQL_COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-mysql.yml"
MYSQL_INIT_SCRIPT="${SCRIPT_DIR}/scripts/init-streampark-mysql.sh"

if docker compose version &>/dev/null; then
    COMPOSE=(docker compose)
else
    COMPOSE=(docker-compose)
fi

compose() {
    "${COMPOSE[@]}" -f "${COMPOSE_FILE}" "$@"
}

mysql_compose() {
    "${COMPOSE[@]}" -f "${MYSQL_COMPOSE_FILE}" "$@"
}

wait_for_mysql() {
    echo "等待 Docker MySQL 就绪..."
    for i in {1..30}; do
        if docker exec mysql mysqladmin ping -h 127.0.0.1 -uroot -pHg19951030 --silent &>/dev/null; then
            echo "✅ Docker MySQL 已就绪"
            return 0
        fi
        sleep 2
    done

    echo "❌ Docker MySQL 启动超时"
    return 1
}

ACTION=${1:-restart}

echo "=========================================="
echo "StreamPark 管理 - $ACTION"
echo "=========================================="

case "$ACTION" in
    start)
        echo "启动 Docker MySQL..."
        mysql_compose up -d
        wait_for_mysql

        if [ -x "${MYSQL_INIT_SCRIPT}" ]; then
            echo "校验 StreamPark 元数据库..."
            bash "${MYSQL_INIT_SCRIPT}"
        fi

        echo "启动 StreamPark（官方 Java 8 镜像）..."
        compose up -d
        
        echo "等待 StreamPark 启动..."
        sleep 15
        
        echo "✅ StreamPark 启动完成"
        echo "Web UI: http://localhost:10000"
        echo "默认账号: admin / streampark"
        echo "说明: 如需在 UI 中配置 Flink Home，请填写容器内实际可访问路径"
        ;;
        
    stop)
        echo "停止 StreamPark..."
        compose down
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
compose ps
