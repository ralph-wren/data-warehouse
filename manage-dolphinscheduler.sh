#!/bin/bash

# DolphinScheduler 管理脚本
# 用法: ./manage-dolphinscheduler.sh [start|stop|restart|status|logs|verify|init|start-full]
# 默认: restart

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-dolphinscheduler.yml"
ENV_FILE="${SCRIPT_DIR}/config/dolphinscheduler/dolphinscheduler.env"
ACTION="${1:-restart}"

if docker compose version &>/dev/null; then
    COMPOSE=(docker compose)
else
    COMPOSE=(docker-compose)
fi

compose() {
    "${COMPOSE[@]}" -f "${COMPOSE_FILE}" --env-file "${ENV_FILE}" "$@"
}

ensure_network() {
    local network_name="$1"

    if docker network inspect "${network_name}" >/dev/null 2>&1; then
        echo "网络已存在: ${network_name}"
        return 0
    fi

    echo "创建网络: ${network_name}"
    docker network create "${network_name}" >/dev/null
}

prepare_directories() {
    # 统一准备持久化目录，避免首次启动挂载失败
    mkdir -p \
        "${SCRIPT_DIR}/volumes/dolphinscheduler/postgresql" \
        "${SCRIPT_DIR}/volumes/dolphinscheduler/zookeeper" \
        "${SCRIPT_DIR}/volumes/dolphinscheduler/logs" \
        "${SCRIPT_DIR}/volumes/dolphinscheduler/shared" \
        "${SCRIPT_DIR}/volumes/dolphinscheduler/resources" \
        "${SCRIPT_DIR}/volumes/dolphinscheduler/worker-data"
}

wait_for_healthy() {
    local container_name="$1"
    local max_retries="${2:-60}"
    local retry_count=0

    echo "等待容器健康: ${container_name}"
    while true; do
        local container_status
        container_status="$(docker inspect --format '{{if .State.Health}}{{.State.Health.Status}}{{else}}{{.State.Status}}{{end}}' "${container_name}" 2>/dev/null || true)"

        if [[ "${container_status}" == "healthy" || "${container_status}" == "running" ]]; then
            echo "容器已就绪: ${container_name} (${container_status})"
            return 0
        fi

        retry_count=$((retry_count + 1))
        if [[ "${retry_count}" -ge "${max_retries}" ]]; then
            echo "错误: 容器长时间未就绪: ${container_name}"
            docker logs "${container_name}" --tail 100 || true
            return 1
        fi

        sleep 3
    done
}

prepare_environment() {
    prepare_directories

    # 这些网络用于接入现有 Kafka / Flink / Doris 容器
    ensure_network "data-warehouse_default"
    ensure_network "data-warehouse_flink-net"
    ensure_network "data-warehouse_doris-net"
}

warn_dependency_status() {
    # 这里只做提示，不阻塞启动，便于先把 DolphinScheduler 部署起来
    local dependency_name="$1"
    local container_name="$2"

    if docker ps --format '{{.Names}}' | grep -q "^${container_name}$"; then
        echo "外部依赖已运行: ${dependency_name} (${container_name})"
    else
        echo "提示: 外部依赖未运行: ${dependency_name} (${container_name})"
    fi
}

init_schema() {
    echo "初始化 DolphinScheduler 元数据库..."
    compose run --rm dolphinscheduler-schema-initializer
    echo "✅ 元数据库初始化完成"
}

start_stack() {
    prepare_environment

    warn_dependency_status "Doris FE" "doris-fe"
    warn_dependency_status "Doris BE" "doris-be"
    warn_dependency_status "Kafka" "kafka"
    warn_dependency_status "Flink JobManager" "flink-jobmanager"

    echo "启动基础依赖(PostgreSQL / ZooKeeper)..."
    compose up -d dolphinscheduler-postgresql dolphinscheduler-zookeeper

    wait_for_healthy "dolphinscheduler-postgresql" 80
    wait_for_healthy "dolphinscheduler-zookeeper" 80

    init_schema

    echo "启动 DolphinScheduler 核心服务..."
    compose up -d \
        dolphinscheduler-api \
        dolphinscheduler-master \
        dolphinscheduler-worker

    wait_for_healthy "dolphinscheduler-api" 80
    wait_for_healthy "dolphinscheduler-master" 80
    wait_for_healthy "dolphinscheduler-worker" 80

    echo "✅ DolphinScheduler 启动完成"
    echo "运行模式: 轻量模式（未启动 alert，可用 start-full 启动完整版）"
    echo "Web UI: http://localhost:12345/dolphinscheduler/ui"
    echo "API Health: http://localhost:12345/dolphinscheduler/actuator/health"
    echo "建议继续执行: bash scripts/check-dolphinscheduler-connectivity.sh"
}

start_full_stack() {
    start_stack

    echo "补充启动 alert 服务..."
    compose --profile full up -d dolphinscheduler-alert
    wait_for_healthy "dolphinscheduler-alert" 80
    echo "✅ DolphinScheduler 完整模式已启动"
}

case "${ACTION}" in
    start)
        echo "=========================================="
        echo "DolphinScheduler 管理 - start"
        echo "=========================================="
        start_stack
        ;;

    stop)
        echo "=========================================="
        echo "DolphinScheduler 管理 - stop"
        echo "=========================================="
        compose down
        echo "✅ DolphinScheduler 已停止"
        ;;

    restart)
        echo "=========================================="
        echo "DolphinScheduler 管理 - restart"
        echo "=========================================="
        "$0" stop || true
        sleep 3
        "$0" start
        ;;

    start-full)
        echo "=========================================="
        echo "DolphinScheduler 管理 - start-full"
        echo "=========================================="
        start_full_stack
        ;;

    status)
        compose ps
        ;;

    logs)
        compose logs -f
        ;;

    verify)
        bash "${SCRIPT_DIR}/scripts/check-dolphinscheduler-connectivity.sh"
        ;;

    init)
        echo "=========================================="
        echo "DolphinScheduler 管理 - init"
        echo "=========================================="
        prepare_environment
        compose up -d dolphinscheduler-postgresql dolphinscheduler-zookeeper
        wait_for_healthy "dolphinscheduler-postgresql" 80
        wait_for_healthy "dolphinscheduler-zookeeper" 80
        init_schema
        ;;

    *)
        echo "错误: 未知操作 '${ACTION}'"
        echo "用法: $0 [start|stop|restart|status|logs|verify|init|start-full]"
        exit 1
        ;;
esac

if [[ "${ACTION}" != "logs" ]]; then
    echo ""
    echo "DolphinScheduler 状态:"
    compose ps
fi
