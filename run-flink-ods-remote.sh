#!/bin/bash

# Flink ODS 作业 - 提交到远程 Standalone 集群
# 使用方式: bash run-flink-ods-remote.sh

echo "=========================================="
echo "Flink ODS Job - 提交到远程集群"
echo "=========================================="

# 1. 打包项目
echo "步骤 1: 打包项目..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ 打包失败"
    exit 1
fi

echo "✅ 打包成功"

# 2. 检查 JAR 文件
JAR_FILE="target/realtime-crypto-datawarehouse-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "❌ JAR 文件不存在: $JAR_FILE"
    exit 1
fi

echo "✅ JAR 文件存在: $JAR_FILE"

# 3. 修改配置文件为远程模式
echo "步骤 2: 配置远程集群模式..."
echo "请确保 application-dev.yml 中配置了:"
echo "  flink.cluster.mode: remote"
echo "  flink.cluster.remote.host: localhost"
echo "  flink.cluster.remote.port: 8081"

# 4. 运行作业
echo "步骤 3: 提交作业到远程集群..."
java -cp "$JAR_FILE" \
    com.crypto.dw.flink.FlinkODSJobDataStream \
    --APP_ENV dev

echo "=========================================="
echo "作业已提交到远程集群"
echo "查看作业状态: http://localhost:8081"
echo "=========================================="
