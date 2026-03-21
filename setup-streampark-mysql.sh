#!/bin/bash
#
# StreamPark MySQL 驱动安装脚本
# 下载 MySQL Connector/J 并复制到 StreamPark 容器的 lib 目录
#

set -e

echo "=========================================="
echo "StreamPark MySQL 驱动安装"
echo "=========================================="
echo ""

# MySQL Connector/J 版本
MYSQL_CONNECTOR_VERSION="8.0.33"
MYSQL_CONNECTOR_JAR="mysql-connector-j-${MYSQL_CONNECTOR_VERSION}.jar"
DOWNLOAD_URL="https://repo1.maven.org/maven2/com/mysql/mysql-connector-j/${MYSQL_CONNECTOR_VERSION}/${MYSQL_CONNECTOR_JAR}"

# 临时目录
TEMP_DIR="./temp"
mkdir -p "$TEMP_DIR"

echo "1. 下载 MySQL Connector/J ${MYSQL_CONNECTOR_VERSION}..."
if [ ! -f "$TEMP_DIR/$MYSQL_CONNECTOR_JAR" ]; then
    curl -L -o "$TEMP_DIR/$MYSQL_CONNECTOR_JAR" "$DOWNLOAD_URL"
    echo "   下载完成: $MYSQL_CONNECTOR_JAR"
else
    echo "   文件已存在，跳过下载"
fi

echo ""
echo "2. 复制 MySQL 驱动到 StreamPark 容器..."
docker cp "$TEMP_DIR/$MYSQL_CONNECTOR_JAR" streampark:/streampark/lib/
echo "   复制完成"

echo ""
echo "3. 验证驱动文件..."
docker exec streampark ls -lh /streampark/lib/$MYSQL_CONNECTOR_JAR

echo ""
echo "4. 重启 StreamPark 容器..."
docker restart streampark

echo ""
echo "5. 等待 StreamPark 启动..."
sleep 30

echo ""
echo "6. 检查 StreamPark 状态..."
docker ps --filter "name=streampark" --format "{{.Names}}\t{{.Status}}"

echo ""
echo "=========================================="
echo "安装完成！"
echo "=========================================="
echo ""
echo "访问 StreamPark: http://localhost:10000"
echo "用户名: admin"
echo "密码: streampark"
echo ""
echo "数据库: MySQL (Doris)"
echo "连接: doris-fe:9030/streampark"
echo ""
