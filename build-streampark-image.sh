#!/bin/bash
# StreamPark 镜像准备脚本
# 当前默认使用官方 Java 8 镜像，无需本地自定义构建

set -e

echo "=========================================="
echo "准备 StreamPark 官方镜像"
echo "=========================================="

# 官方镜像名称和标签
IMAGE_NAME="apache/streampark"
IMAGE_TAG="v2.1.5"
FULL_IMAGE_NAME="${IMAGE_NAME}:${IMAGE_TAG}"

echo ""
echo "镜像信息:"
echo "  名称: ${IMAGE_NAME}"
echo "  标签: ${IMAGE_TAG}"
echo "  完整名称: ${FULL_IMAGE_NAME}"
echo ""

echo "开始拉取官方镜像..."
echo ""

docker pull ${FULL_IMAGE_NAME}

echo ""
echo "=========================================="
echo "镜像准备完成！"
echo "=========================================="
echo ""
echo "镜像信息:"
docker images ${IMAGE_NAME} --format 'table {{.Repository}}\t{{.Tag}}\t{{.Size}}'

echo ""
echo "验证 Java 版本:"
docker run --rm ${FULL_IMAGE_NAME} java -version

echo ""
echo "=========================================="
echo "镜像可用！"
echo "=========================================="
echo ""
echo "下一步:"
echo "1. 启动 StreamPark: bash manage-streampark.sh start"
echo "2. 访问 Web UI: http://localhost:10000"
echo ""
