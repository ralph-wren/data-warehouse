#!/bin/bash
# 构建 StreamPark 自定义镜像脚本
# 包含 Java 11 支持

set -e

echo "=========================================="
echo "构建 StreamPark 自定义镜像"
echo "=========================================="

# 镜像名称和标签
IMAGE_NAME="streampark-java11"
IMAGE_TAG="v2.1.5"
FULL_IMAGE_NAME="${IMAGE_NAME}:${IMAGE_TAG}"

echo ""
echo "镜像信息:"
echo "  名称: ${IMAGE_NAME}"
echo "  标签: ${IMAGE_TAG}"
echo "  完整名称: ${FULL_IMAGE_NAME}"
echo ""

# 检查 Dockerfile 是否存在
if [ ! -f "Dockerfile.streampark" ]; then
    echo "错误: Dockerfile.streampark 不存在"
    exit 1
fi

echo "开始构建镜像..."
echo ""

# 构建镜像
docker build \
    -f Dockerfile.streampark \
    -t ${FULL_IMAGE_NAME} \
    --progress=plain \
    .

echo ""
echo "=========================================="
echo "镜像构建完成！"
echo "=========================================="
echo ""
echo "镜像信息:"
docker images ${IMAGE_NAME}

echo ""
echo "验证 Java 版本:"
docker run --rm ${FULL_IMAGE_NAME} java -version

echo ""
echo "=========================================="
echo "构建成功！"
echo "=========================================="
echo ""
echo "下一步:"
echo "1. 更新 docker-compose-streampark.yml 使用新镜像: ${FULL_IMAGE_NAME}"
echo "2. 启动 StreamPark: docker-compose -f docker-compose-streampark.yml up -d"
echo ""
