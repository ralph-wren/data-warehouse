#!/bin/bash

# Doris 管理脚本
# 用法: ./manage-doris.sh [start|stop|restart]
# 默认: restart
# 请在 data-warehouse 目录执行，或任意目录: /path/to/manage-doris.sh start

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-doris.yml"

if docker compose version &>/dev/null; then
    COMPOSE=(docker compose)
else
    COMPOSE=(docker-compose)
fi

compose() {
    "${COMPOSE[@]}" -f "${COMPOSE_FILE}" "$@"
}

ACTION=${1:-restart}

echo "=========================================="
echo "Doris 管理 - $ACTION"
echo "=========================================="

if [[ "$(uname -s)" == "Darwin" && "$(uname -m)" == "arm64" ]]; then
    echo ""
    echo "⚠️  Apple Silicon：Doris 镜像仅 amd64，请用 Colima x86_64（勿用 Docker Desktop 直接跑 amd64 BE）。"
    echo "    若 docker compose pull 报 registry EOF：检查 ~/.colima/default/colima.yaml 里 docker.registry-mirrors，"
    echo "    并避免 VM 内错误代理（可设 env 清空 HTTP_PROXY 后 colima restart）。"
    echo ""
fi

case "$ACTION" in
    start)
        echo "启动 Doris 集群..."
        compose up -d

        echo "等待 FE/BE 就绪（BE 首次拉镜像或 amd64 模拟会较慢）..."
        sleep 25

        echo "✅ Doris 已拉起（若查询仍报 no backend，见下方自检）"
        echo "FE Web UI: http://localhost:8030"
        echo "BE Web UI: http://localhost:8040"
        echo "自检: docker logs doris-be --tail 80"
        echo "     docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e 'SHOW BACKENDS\\G'"
        ;;

    stop)
        echo "停止 Doris 集群..."
        compose down
        echo "✅ Doris 已停止"
        ;;

    restart)
        echo "重启 Doris 集群..."
        "$0" stop
        sleep 3
        "$0" start
        ;;

    *)
        echo "错误: 未知操作 '$ACTION'"
        echo "用法: $0 [start|stop|restart]"
        exit 1
        ;;
esac

echo ""
echo "Doris 状态:"
compose ps
