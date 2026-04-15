#!/bin/bash

# Flink Ticker 采集作业启动脚本
# 说明：
# 1. 这个脚本专门启动 com.crypto.dw.jobs.FlinkTickerCollectorJob
# 2. 与 run-flink-collector.sh 不同，那个脚本用于另一个类
# 3. 支持传入币对参数；如果不传，则交给 Java 程序内部决定（价差计算 / 配置 / 默认值）

echo "========================================"
echo "Flink Ticker Collector Job"
echo "========================================"

# 设置默认环境（如果外部没传，则默认 dev）
if [ -z "$APP_ENV" ]; then
    export APP_ENV=dev
fi

echo "当前环境: $APP_ENV"

# 检查是否提供了币对参数
if [ $# -gt 0 ]; then
    echo "命令行传入币对: $@"
    JOB_ARGS="$*"
else
    echo "未传入币对参数，将由 Java 程序内部决定订阅列表"
    JOB_ARGS=""
fi

echo ""
echo "开始编译项目..."
mvn clean compile -DskipTests

if [ $? -ne 0 ]; then
    echo "❌ 编译失败，脚本退出"
    exit 1
fi

echo "✅ 编译成功"
echo ""
echo "开始启动 Flink Ticker Collector Job..."
echo "主类: com.crypto.dw.jobs.FlinkTickerCollectorJob"
echo "Web UI: http://localhost:8085"
echo "按 Ctrl+C 停止"
echo "========================================"

if [ -n "$JOB_ARGS" ]; then
    mvn exec:java \
        -Dexec.mainClass="com.crypto.dw.jobs.FlinkTickerCollectorJob" \
        -Dexec.args="$JOB_ARGS" \
        -Dexec.cleanupDaemonThreads=false
else
    mvn exec:java \
        -Dexec.mainClass="com.crypto.dw.jobs.FlinkTickerCollectorJob" \
        -Dexec.cleanupDaemonThreads=false
fi

echo ""
echo "========================================"
echo "Flink Ticker Collector Job 已停止"
echo "========================================"
