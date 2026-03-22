#!/bin/bash

# Flink ADS 实时指标作业启动脚本
# 功能: 从 DWS 层读取 1分钟 K 线数据,计算实时指标并写入 ADS 层

# 设置环境变量
export APP_ENV=dev

# 设置 Java 选项
export JAVA_OPTS="-Xms512m -Xmx1024m"

echo "=========================================="
echo "Starting Flink ADS Realtime Metrics Job"
echo "=========================================="
echo "Environment: $APP_ENV"
echo "Java Options: $JAVA_OPTS"
echo "Web UI: http://localhost:8086"
echo "=========================================="

# 编译项目
echo "Compiling project..."
mvn clean compile -DskipTests

# 检查编译是否成功
if [ $? -ne 0 ]; then
    echo "❌ Compilation failed!"
    exit 1
fi

echo "✅ Compilation successful!"
echo ""

# 运行 Flink 作业
echo "Starting Flink ADS Realtime Metrics Job..."
mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkADSRealtimeMetricsJob" \
    -Dexec.args="--env $APP_ENV" \
    -Dexec.cleanupDaemonThreads=false

echo ""
echo "=========================================="
echo "Flink ADS Realtime Metrics Job Stopped"
echo "=========================================="
