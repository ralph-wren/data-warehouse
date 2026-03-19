#!/bin/bash

# Flink DWS 1分钟窗口聚合 Job (Flink SQL) 运行脚本

echo "=========================================="
echo "Running Flink DWS 1Min Job (Flink SQL)"
echo "=========================================="
echo ""

# 加载环境变量
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
    echo "Environment variables loaded from .env"
else
    echo "Warning: .env file not found"
fi

# 设置应用环境
export APP_ENV=${APP_ENV:-dev}
echo "APP_ENV: $APP_ENV"
echo ""

# 编译项目
echo "Compiling project..."
mvn clean package -DskipTests

if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi

echo ""
echo "Starting Flink DWS 1Min SQL Job..."
echo ""

# 运行 Flink 作业 (使用 Maven exec 插件,自动加载所有依赖)
mvn exec:java -Dexec.mainClass="com.crypto.dw.flink.FlinkDWSJob1MinSQL"

echo ""
echo "Flink DWS 1Min SQL Job stopped."
