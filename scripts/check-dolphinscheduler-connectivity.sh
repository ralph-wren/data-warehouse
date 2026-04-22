#!/bin/bash

# DolphinScheduler Worker 连通性检查脚本
# 用法: bash scripts/check-dolphinscheduler-connectivity.sh

set -euo pipefail

WORKER_CONTAINER="${WORKER_CONTAINER:-dolphinscheduler-worker}"
CHECK_FAILURES=0

run_check() {
    local check_name="$1"
    local check_command="$2"

    echo "检查: ${check_name}"
    if docker exec "${WORKER_CONTAINER}" bash -lc "${check_command}" >/tmp/ds-check.log 2>&1; then
        echo "✅ ${check_name}"
    else
        echo "❌ ${check_name}"
        cat /tmp/ds-check.log
        CHECK_FAILURES=$((CHECK_FAILURES + 1))
    fi
    echo ""
}

if ! docker ps --format '{{.Names}}' | grep -q "^${WORKER_CONTAINER}$"; then
    echo "错误: 容器未运行: ${WORKER_CONTAINER}"
    echo "请先执行: bash manage-dolphinscheduler.sh start"
    exit 1
fi

run_check "DolphinScheduler Worker 健康检查" \
    "curl -fsS http://localhost:1235/actuator/health >/dev/null"

run_check "Flink JobManager REST 联通" \
    "curl -fsS http://jobmanager:8081/overview >/dev/null"

run_check "Doris FE HTTP 联通" \
    "curl -fsS http://doris-fe:8030/api/bootstrap >/dev/null"

run_check "Doris FE MySQL 端口联通" \
    "cat < /dev/null > /dev/tcp/doris-fe/9030"

run_check "Doris BE HTTP 联通" \
    "curl -fsS http://doris-be:8040 >/dev/null"

run_check "Kafka Broker 端口联通" \
    "cat < /dev/null > /dev/tcp/kafka/9092"

if [[ "${CHECK_FAILURES}" -gt 0 ]]; then
    echo "检查完成: 失败 ${CHECK_FAILURES} 项"
    exit 1
fi

echo "检查完成: DolphinScheduler Worker 已可访问 Flink / Doris / Kafka"
