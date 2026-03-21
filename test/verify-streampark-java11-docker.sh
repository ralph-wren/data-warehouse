#!/bin/bash
# 验证 StreamPark Docker 镜像中的 Java 11 配置

set -e

echo "=========================================="
echo "验证 StreamPark Java 11 Docker 镜像"
echo "=========================================="

# 检查容器是否运行
echo ""
echo "1. 检查 StreamPark 容器状态..."
if ! docker ps | grep -q streampark; then
    echo "错误: StreamPark 容器未运行"
    exit 1
fi
echo "✓ StreamPark 容器正在运行"

# 检查 Java 版本
echo ""
echo "2. 检查容器内 Java 版本..."
JAVA_VERSION=$(docker exec streampark java -version 2>&1 | grep "openjdk version" | awk '{print $3}' | tr -d '"')
echo "Java 版本: ${JAVA_VERSION}"

if [[ "${JAVA_VERSION}" == 11.* ]]; then
    echo "✓ Java 11 已正确安装"
else
    echo "✗ Java 版本不是 11，当前版本: ${JAVA_VERSION}"
    exit 1
fi

# 检查 javac 编译器
echo ""
echo "3. 检查 Java 编译器..."
if docker exec streampark which javac > /dev/null 2>&1; then
    JAVAC_VERSION=$(docker exec streampark javac -version 2>&1 | awk '{print $2}')
    echo "javac 版本: ${JAVAC_VERSION}"
    echo "✓ Java 编译器可用"
else
    echo "✗ Java 编译器不可用"
    exit 1
fi

# 检查 StreamPark 服务
echo ""
echo "4. 检查 StreamPark 服务..."
if curl -s -o /dev/null -w "%{http_code}" http://localhost:10000 | grep -q "200\|302"; then
    echo "✓ StreamPark Web UI 可访问 (http://localhost:10000)"
else
    echo "⚠ StreamPark Web UI 可能还在启动中..."
fi

# 检查容器健康状态
echo ""
echo "5. 检查容器健康状态..."
HEALTH_STATUS=$(docker inspect --format='{{.State.Health.Status}}' streampark 2>/dev/null || echo "unknown")
echo "健康状态: ${HEALTH_STATUS}"

if [ "${HEALTH_STATUS}" = "healthy" ]; then
    echo "✓ 容器健康状态正常"
else
    echo "⚠ 容器健康状态: ${HEALTH_STATUS}"
fi

# 显示镜像信息
echo ""
echo "6. 镜像信息..."
docker images streampark-java11:v2.1.5 --format "table {{.Repository}}\t{{.Tag}}\t{{.Size}}"

echo ""
echo "=========================================="
echo "验证完成！"
echo "=========================================="
echo ""
echo "StreamPark 信息:"
echo "  - Web UI: http://localhost:10000"
echo "  - 默认账号: admin / streampark"
echo "  - Java 版本: ${JAVA_VERSION}"
echo "  - 镜像: streampark-java11:v2.1.5"
echo ""
echo "下一步:"
echo "1. 访问 http://localhost:10000 登录 StreamPark"
echo "2. 在 StreamPark 中配置 Flink 集群"
echo "3. 创建和部署 Flink 作业"
echo ""
