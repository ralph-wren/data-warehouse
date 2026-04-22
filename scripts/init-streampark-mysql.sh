#!/bin/bash

# StreamPark 元数据库初始化脚本
# 兼容首次启动自动初始化和已存在数据目录的手动补初始化场景。

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"
SCHEMA_SQL="${PROJECT_DIR}/config/streampark/mysql/init/01-streampark-schema.sql"
DATA_SQL="${PROJECT_DIR}/config/streampark/mysql/init/02-streampark-data.sql"
MYSQL_CONTAINER="mysql"
MYSQL_PASSWORD="Hg19951030"

if ! docker ps --format '{{.Names}}' | grep -q "^${MYSQL_CONTAINER}$"; then
    echo "❌ ${MYSQL_CONTAINER} 未启动"
    exit 1
fi

TABLE_COUNT=$(docker exec "${MYSQL_CONTAINER}" mysql -N -uroot -p"${MYSQL_PASSWORD}" -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='streampark';")

if [ "${TABLE_COUNT}" -gt 0 ]; then
    echo "✅ streampark 库已存在 ${TABLE_COUNT} 张表，跳过初始化"
    exit 0
fi

echo "开始初始化 StreamPark 元数据库..."
docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -p"${MYSQL_PASSWORD}" < "${SCHEMA_SQL}"
docker exec -i "${MYSQL_CONTAINER}" mysql -uroot -p"${MYSQL_PASSWORD}" < "${DATA_SQL}"

FINAL_TABLE_COUNT=$(docker exec "${MYSQL_CONTAINER}" mysql -N -uroot -p"${MYSQL_PASSWORD}" -e \
    "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='streampark';")

echo "✅ StreamPark 元数据库初始化完成，当前表数量: ${FINAL_TABLE_COUNT}"
