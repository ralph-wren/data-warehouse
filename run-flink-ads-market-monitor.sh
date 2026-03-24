#!/bin/bash

# Flink ADS Market Monitor Job 启动脚本
# 功能：启动实时市场监控作业
# 日期：2026-03-24

echo "=========================================="
echo "启动 Flink ADS Market Monitor Job"
echo "=========================================="

# 设置环境变量
export APP_ENV=dev

# 编译项目
echo "编译项目..."
mvn clean compile -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ 编译失败"
    exit 1
fi

echo "✓ 编译成功"

# 运行 Flink 作业
echo "启动 Flink 作业..."
mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkADSMarketMonitorJob" \
    -Dexec.cleanupDaemonThreads=false

echo "=========================================="
echo "Flink ADS Market Monitor Job 已停止"
echo "=========================================="
