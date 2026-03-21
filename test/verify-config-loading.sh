#!/bin/bash
# 配置加载验证脚本
# 用于验证不同环境的配置文件是否正确加载

echo "=========================================="
echo "配置加载验证"
echo "=========================================="
echo ""

# 检查 JAR 包是否存在
JAR_FILE="target/realtime-crypto-datawarehouse-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR 包不存在: $JAR_FILE"
    echo "请先编译项目: mvn clean package -DskipTests"
    exit 1
fi

echo "✅ JAR 包存在: $JAR_FILE"
echo ""

# 检查配置文件是否打包
echo "=========================================="
echo "检查配置文件是否打包到 JAR 中"
echo "=========================================="
jar -tf "$JAR_FILE" | grep "config/application" | while read -r file; do
    echo "✅ $file"
done
echo ""

# 提取配置文件到临时目录
TEMP_DIR="test/temp-config"
rm -rf "$TEMP_DIR"
mkdir -p "$TEMP_DIR"

echo "=========================================="
echo "提取配置文件内容"
echo "=========================================="
echo ""

# 提取 application.yml
echo "1. application.yml (基础配置)"
echo "---"
jar -xf "$JAR_FILE" config/application.yml
cat config/application.yml | grep -A 2 -E "(kafka:|doris:)" | head -20
echo ""

# 提取 application-dev.yml
echo "2. application-dev.yml (开发环境配置)"
echo "---"
jar -xf "$JAR_FILE" config/application-dev.yml
cat config/application-dev.yml | grep -A 5 -E "(kafka:|doris:)"
echo ""

# 提取 application-docker.yml
echo "3. application-docker.yml (Docker 环境配置)"
echo "---"
jar -xf "$JAR_FILE" config/application-docker.yml
cat config/application-docker.yml | grep -A 5 -E "(kafka:|doris:)"
echo ""

# 清理临时文件
rm -rf config

echo "=========================================="
echo "配置对比"
echo "=========================================="
echo ""
echo "| 配置项 | dev (本地) | docker (容器) |"
echo "|--------|-----------|--------------|"

# 提取并对比 Kafka 配置
jar -xf "$JAR_FILE" config/application-dev.yml config/application-docker.yml
DEV_KAFKA=$(grep "bootstrap-servers:" config/application-dev.yml | awk '{print $2}')
DOCKER_KAFKA=$(grep "bootstrap-servers:" config/application-docker.yml | awk '{print $2}')
echo "| Kafka | $DEV_KAFKA | $DOCKER_KAFKA |"

# 提取并对比 Doris FE HTTP 配置
DEV_DORIS_HTTP=$(grep "http-url:" config/application-dev.yml | awk '{print $2}')
DOCKER_DORIS_HTTP=$(grep "http-url:" config/application-docker.yml | awk '{print $2}')
echo "| Doris FE HTTP | $DEV_DORIS_HTTP | $DOCKER_DORIS_HTTP |"

# 提取并对比 Doris FE JDBC 配置
DEV_DORIS_JDBC=$(grep "jdbc-url:" config/application-dev.yml | awk '{print $2}')
DOCKER_DORIS_JDBC=$(grep "jdbc-url:" config/application-docker.yml | awk '{print $2}')
echo "| Doris FE JDBC | $DEV_DORIS_JDBC | $DOCKER_DORIS_JDBC |"

# 清理临时文件
rm -rf config

echo ""
echo "=========================================="
echo "使用指南"
echo "=========================================="
echo ""
echo "本地开发环境（IDE）："
echo "  export APP_ENV=dev"
echo "  bash run-flink-ods-datastream.sh"
echo ""
echo "Docker 环境（StreamPark）："
echo "  在 StreamPark Web UI 中设置："
echo "  Dynamic Properties: APP_ENV=docker"
echo ""
echo "=========================================="
echo "验证完成 ✅"
echo "=========================================="
