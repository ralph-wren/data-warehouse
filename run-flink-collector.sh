#!/bin/bash

# Flink 数据采集作业启动脚本
# 从 OKX WebSocket 接收实时行情数据并发送到 Kafka

echo "========================================"
echo "Flink Data Collector Job"
echo "========================================"

# 设置环境变量
export APP_ENV=dev

# 检查是否提供了交易对参数
if [ $# -gt 0 ]; then
    echo "Subscribing to symbols: $@"
    SYMBOLS="$@"
else
    echo "Using default symbols from config"
    SYMBOLS=""
fi

# 编译项目
echo "Compiling project..."
mvn clean compile -DskipTests

if [ $? -ne 0 ]; then
    echo "Compilation failed!"
    exit 1
fi

echo "Compilation successful!"
echo ""

# 运行 Flink 作业
echo "Starting Flink Data Collector Job..."
echo "Web UI: http://localhost:8085"
echo "Press Ctrl+C to stop"
echo "========================================"

mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkDataCollectorJob" \
    -Dexec.args="$SYMBOLS" \
    -Dexec.cleanupDaemonThreads=false

echo ""
echo "========================================"
echo "Flink Data Collector Job stopped"
echo "========================================"
