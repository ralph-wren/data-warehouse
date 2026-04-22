#!/bin/bash

# StreamPark MySQL 管理脚本
# 统一管理 Docker MySQL，避免与宿主机 MySQL 冲突。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-mysql.yml"
DATA_DIR="${SCRIPT_DIR}/volumes/mysql/data"

if docker compose version &>/dev/null; then
    COMPOSE=(docker compose)
else
    COMPOSE=(docker-compose)
fi

compose() {
    "${COMPOSE[@]}" -f "${COMPOSE_FILE}" "$@"
}

wait_for_mysql() {
    echo "等待 MySQL 就绪..."
    for i in {1..30}; do
        if docker exec mysql mysqladmin ping -h 127.0.0.1 -uroot -pHg19951030 --silent &>/dev/null; then
            echo "✅ MySQL 已就绪"
            return 0
        fi
        sleep 2
    done

    echo "❌ MySQL 启动超时"
    return 1
}

ACTION=${1:-status}

case "${ACTION}" in
    start)
        echo "启动 StreamPark MySQL..."
        compose up -d
        wait_for_mysql
        echo "Root 密码: Hg19951030"
        ;;
    stop)
        echo "停止 StreamPark MySQL..."
        compose down
        ;;
    restart)
        "$0" stop
        "$0" start
        ;;
    status)
        compose ps
        ;;
    logs)
        docker logs --tail 200 streampark-mysql
        ;;
    clean)
        echo "清理 Docker MySQL（会删除 streampark 元数据）..."
        compose down -v || true
        rm -rf "${DATA_DIR}"
        echo "✅ 已删除 ${DATA_DIR}"
        ;;
    *)
        echo "用法: $0 [start|stop|restart|status|logs|clean]"
        exit 1
        ;;
esac
