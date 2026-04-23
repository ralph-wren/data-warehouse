#!/bin/bash

# StreamPark 管理脚本（Kubernetes 模式）
# 用法: ./manage-streampark.sh [start|stop|restart|status|logs]

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SELF_SCRIPT="${SCRIPT_DIR}/manage-streampark.sh"
MYSQL_COMPOSE_FILE="${SCRIPT_DIR}/docker-compose-mysql.yml"
MYSQL_INIT_SCRIPT="${SCRIPT_DIR}/scripts/init-streampark-mysql.sh"
STREAMPARK_TEMPLATE="${SCRIPT_DIR}/k8s/streampark/streampark-k8s.template.yaml"
STREAMPARK_CONFIG_FILE="${SCRIPT_DIR}/streampark-config.yaml"

NAMESPACE="${STREAMPARK_NAMESPACE:-flink}"
SERVICE_ACCOUNT="${STREAMPARK_SERVICE_ACCOUNT:-flink}"
STREAMPARK_IMAGE="${STREAMPARK_IMAGE:-apache/streampark:v2.1.5}"
STREAMPARK_NODE_PORT="${STREAMPARK_NODE_PORT:-31000}"
FLINK_DIST_URL="${STREAMPARK_FLINK_DIST_URL:-https://archive.apache.org/dist/flink/flink-1.17.2/flink-1.17.2-bin-scala_2.12.tgz}"
MYSQL_HOST="${STREAMPARK_MYSQL_HOST:-host.docker.internal}"
MYSQL_PORT="${STREAMPARK_MYSQL_PORT:-3306}"
MYSQL_DB="${STREAMPARK_MYSQL_DB:-streampark}"
MYSQL_USER="${STREAMPARK_MYSQL_USER:-root}"
MYSQL_PASSWORD="${STREAMPARK_MYSQL_PASSWORD:-Hg19951030}"
SPRING_DATASOURCE_URL_DEFAULT="jdbc:mysql://${MYSQL_HOST}:${MYSQL_PORT}/${MYSQL_DB}?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai"
SPRING_DATASOURCE_URL="${STREAMPARK_SPRING_DATASOURCE_URL:-${SPRING_DATASOURCE_URL_DEFAULT}}"

if docker compose version &>/dev/null; then
    COMPOSE=(docker compose)
else
    COMPOSE=(docker-compose)
fi

mysql_compose() {
    "${COMPOSE[@]}" -f "${MYSQL_COMPOSE_FILE}" "$@"
}

wait_for_mysql() {
    echo "等待 Docker MySQL 就绪..."
    for _ in {1..30}; do
        if docker exec mysql mysqladmin ping -h 127.0.0.1 -u"${MYSQL_USER}" -p"${MYSQL_PASSWORD}" --silent &>/dev/null; then
            echo "✅ Docker MySQL 已就绪"
            return 0
        fi
        sleep 2
    done
    echo "❌ Docker MySQL 启动超时"
    return 1
}

render_template() {
    local ds_url_escaped
    ds_url_escaped="${SPRING_DATASOURCE_URL//&/\\&}"
    sed \
      -e "s#__NAMESPACE__#${NAMESPACE}#g" \
      -e "s#__SERVICE_ACCOUNT__#${SERVICE_ACCOUNT}#g" \
      -e "s#__IMAGE__#${STREAMPARK_IMAGE}#g" \
      -e "s#__NODE_PORT__#${STREAMPARK_NODE_PORT}#g" \
      -e "s#__FLINK_DIST_URL__#${FLINK_DIST_URL}#g" \
      -e "s#__SPRING_DATASOURCE_URL__#${ds_url_escaped}#g" \
      "${STREAMPARK_TEMPLATE}"
}

apply_streampark_resources() {
    echo "应用 Namespace/SA/RBAC（若已存在将跳过）..."
    kubectl apply -f "${SCRIPT_DIR}/k8s/common/shared-resources.yaml"

    echo "同步 StreamPark 配置 ConfigMap..."
    kubectl -n "${NAMESPACE}" create configmap streampark-config \
      --from-file=config.yaml="${STREAMPARK_CONFIG_FILE}" \
      --dry-run=client -o yaml | kubectl apply -f -

    echo "同步 StreamPark MySQL Secret..."
    kubectl -n "${NAMESPACE}" create secret generic streampark-db \
      --from-literal=username="${MYSQL_USER}" \
      --from-literal=password="${MYSQL_PASSWORD}" \
      --dry-run=client -o yaml | kubectl apply -f -

    echo "部署 StreamPark 到 Kubernetes..."
    render_template | kubectl apply -f -
}

wait_for_streampark() {
    echo "等待 StreamPark Deployment 就绪..."
    kubectl -n "${NAMESPACE}" rollout status deployment/streampark --timeout=3600s
}

print_access_hint() {
    local node_ip
    node_ip="$(kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}' 2>/dev/null || true)"
    echo "✅ StreamPark 已部署到 K8s（namespace=${NAMESPACE}）"
    echo "Service: streampark (NodePort ${STREAMPARK_NODE_PORT})"
    echo "重要：K8s 模式下本机默认没有进程监听 10000；直接打开 http://localhost:10000 会连不上。"
    echo "本机访问（二选一，推荐其一常驻一个终端）:"
    echo "  1) ${SELF_SCRIPT} web"
    echo "  2) kubectl -n ${NAMESPACE} port-forward svc/streampark 10000:10000"
    echo "  然后再访问: http://localhost:10000"
    echo "NodePort（部分环境宿主机无法直连，若不通请用上面端口转发）:"
    echo "  http://127.0.0.1:${STREAMPARK_NODE_PORT}"
    if [ -n "${node_ip}" ]; then
        echo "  或节点 IP: http://${node_ip}:${STREAMPARK_NODE_PORT}"
    fi
    echo "默认账号: admin / streampark"
}

ACTION="${1:-restart}"

echo "=========================================="
echo "StreamPark 管理（K8s）- ${ACTION}"
echo "=========================================="

case "${ACTION}" in
  start)
    echo "启动 Docker MySQL（StreamPark 元数据库）..."
    mysql_compose up -d
    wait_for_mysql

    if [ -x "${MYSQL_INIT_SCRIPT}" ]; then
      echo "校验 StreamPark 元数据库..."
      bash "${MYSQL_INIT_SCRIPT}"
    fi

    apply_streampark_resources
    wait_for_streampark
    print_access_hint
    ;;
  stop)
    echo "删除 StreamPark Deployment/Service..."
    kubectl -n "${NAMESPACE}" delete deployment streampark --ignore-not-found
    kubectl -n "${NAMESPACE}" delete service streampark --ignore-not-found
    echo "✅ StreamPark 已停止（K8s 资源已删除）"
    ;;
  restart)
    "${SELF_SCRIPT}" stop
    sleep 2
    "${SELF_SCRIPT}" start
    ;;
  status)
    kubectl -n "${NAMESPACE}" get deployment,svc,pod -l app=streampark
    ;;
  logs)
    kubectl -n "${NAMESPACE}" logs deployment/streampark --tail=200
    ;;
  web)
    echo "启动本地端口转发：localhost:10000 -> svc/streampark:10000"
    kubectl -n "${NAMESPACE}" port-forward svc/streampark 10000:10000
    ;;
  *)
    echo "错误: 未知操作 '${ACTION}'"
    echo "用法: $0 [start|stop|restart|status|logs|web]"
    exit 1
    ;;
esac
