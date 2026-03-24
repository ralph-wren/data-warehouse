#!/bin/bash

# Flink ADS 跨市场套利机会计算作业启动脚本
# 功能：计算现货和期货之间的价差套利机会
# 技术特性：双流 Join + 广播流 + Redis 维度关联

echo "=========================================="
echo "Flink ADS Arbitrage Job"
echo "跨市场套利机会计算（复杂流处理）"
echo "=========================================="

# 设置环境变量
export JAVA_HOME=${JAVA_HOME:-/usr/lib/jvm/java-8-openjdk-amd64}
export PATH=$JAVA_HOME/bin:$PATH

# 项目根目录
PROJECT_DIR=$(cd "$(dirname "$0")" && pwd)
cd "$PROJECT_DIR"

# 检查 JAR 文件
JAR_FILE="target/realtime-crypto-datawarehouse-1.0.0.jar"
if [ ! -f "$JAR_FILE" ]; then
    echo "错误: JAR 文件不存在: $JAR_FILE"
    echo "请先运行: mvn clean package -DskipTests"
    exit 1
fi

# 检查 Redis 是否运行
echo "检查 Redis 连接..."
if ! redis-cli -h localhost -p 6379 ping > /dev/null 2>&1; then
    echo "警告: Redis 未运行或无法连接"
    echo "请确保 Redis 已启动: docker-compose -f docker-compose-redis.yml up -d"
    echo "或者使用本地 Redis: redis-server"
fi

# 检查 Kafka 是否运行
echo "检查 Kafka 连接..."
if ! nc -z localhost 9092 2>/dev/null; then
    echo "警告: Kafka 未运行或无法连接"
    echo "请确保 Kafka 已启动"
fi

# 检查 Doris 是否运行
echo "检查 Doris 连接..."
if ! nc -z localhost 9030 2>/dev/null; then
    echo "警告: Doris 未运行或无法连接"
    echo "请确保 Doris 已启动"
fi

# 创建日志目录
mkdir -p logs

# 运行 Flink 作业
echo "=========================================="
echo "启动 Flink ADS Arbitrage Job..."
echo "=========================================="
echo "主类: com.crypto.dw.flink.FlinkADSArbitrageJob"
echo "JAR 文件: $JAR_FILE"
echo "Web UI: http://localhost:8086"
echo "=========================================="

# 使用 Maven Exec 插件运行（包含所有依赖）
mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkADSArbitrageJob" \
    -Dexec.classpathScope=test \
    -Dlog4j.configuration=file:src/main/resources/log4j2.properties

# 检查退出状态
if [ $? -eq 0 ]; then
    echo "=========================================="
    echo "Flink ADS Arbitrage Job 已停止"
    echo "=========================================="
else
    echo "=========================================="
    echo "Flink ADS Arbitrage Job 运行失败"
    echo "请查看日志: logs/app/flink-app.log"
    echo "=========================================="
    exit 1
fi
