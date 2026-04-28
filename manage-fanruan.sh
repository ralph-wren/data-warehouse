#!/bin/bash

# 帆软开源替代 管理脚本（Reportico）
# 用法: ./manage-fanruan.sh [start|stop|restart|logs|status|down|clean]
# 默认: restart

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-fanruan.yml"
ACTION=${1:-restart}

if docker compose version &>/dev/null; then
    COMPOSE=(docker compose)
else
    COMPOSE=(docker-compose)
fi

compose() {
    "${COMPOSE[@]}" -f "${COMPOSE_FILE}" "$@"
}

prepare_dirs() {
    mkdir -p \
      "${SCRIPT_DIR}/volumes/fanruan/projects" \
      "${SCRIPT_DIR}/volumes/fanruan/logs"
}

echo "=========================================="
echo "帆软开源替代 管理 - ${ACTION}"
echo "=========================================="

case "${ACTION}" in
    start)
        prepare_dirs
        echo "启动帆软开源替代服务（构建镜像并启动）..."
        compose up -d --build
        echo ""
        echo "✅ 服务已启动"
        echo "镜像来源: Reportico ${REPORTICO_VERSION:-8.1.0} (MIT)"
        echo "访问地址: http://localhost:${FANRUAN_HTTP_PORT:-8075}"
        echo "默认入口: http://localhost:${FANRUAN_HTTP_PORT:-8075}/reportico"
        ;;

    stop)
        echo "停止服务..."
        compose stop
        echo "✅ 服务已停止"
        ;;

    restart)
        echo "重启服务..."
        "$0" stop
        sleep 2
        "$0" start
        ;;

    logs)
        compose logs -f fanruan
        ;;

    status)
        compose ps
        ;;

    down)
        echo "停止并删除容器..."
        compose down
        echo "✅ 容器已删除"
        ;;

    clean)
        echo "警告: 这将删除容器及 volumes/fanruan 下的数据!"
        read -r -p "确认删除? (yes/no): " confirm
        if [[ "${confirm}" == "yes" ]]; then
            compose down
            rm -rf "${SCRIPT_DIR}/volumes/fanruan"
            echo "✅ 数据已删除"
        else
            echo "取消操作"
        fi
        ;;

    *)
        echo "错误: 未知操作 '${ACTION}'"
        echo "用法: $0 [start|stop|restart|logs|status|down|clean]"
        exit 1
        ;;
esac

echo ""
echo "服务状态:"
compose ps
