#!/bin/bash

# 运行 Flink ODS Job (使用官方 Doris Connector)
# 从 Kafka 读取数据并写入 Doris ODS 层

echo "=========================================="
echo "运行 Flink ODS Job (官方 Doris Connector)"
echo "=========================================="
echo ""

# 使用 Maven exec 插件运行 (自动处理所有依赖)
echo "使用 Maven exec 插件运行..."
echo ""

# 设置 JVM 参数解决 Java 11+ 模块访问问题
export MAVEN_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"

mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkODSJobDataStream2" \
    -Dexec.cleanupDaemonThreads=false

echo ""
echo "=========================================="
echo "作业已完成"
echo "=========================================="

