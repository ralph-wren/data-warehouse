#!/bin/bash

# Flink K线数据采集作业启动脚本
# 从 Redis 动态获取订阅配置，订阅 OKX K线数据并发送到 Kafka

echo "========================================"
echo "Flink K-line Collector Job"
echo "========================================"

# 设置环境变量
export APP_ENV=dev

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
echo "Starting Flink K-line Collector Job..."
echo "Web UI: http://localhost:8086"
echo ""
echo "Configuration:"
echo "  Redis: localhost:6379"
echo "  Redis Key: kline:subscriptions"
echo "  Kafka Topic: okx-kline-data"
echo "  Refresh Interval: 5 minutes"
echo ""
echo "Redis 订阅管理命令:"
echo "  # 添加订阅"
echo "  redis-cli SADD kline:subscriptions \"BTC-USDT:4H\""
echo "  redis-cli SADD kline:subscriptions \"ETH-USDT:1H\""
echo ""
echo "  # 查看所有订阅"
echo "  redis-cli SMEMBERS kline:subscriptions"
echo ""
echo "  # 删除订阅"
echo "  redis-cli SREM kline:subscriptions \"BTC-USDT:4H\""
echo ""
echo "Press Ctrl+C to stop"
echo "========================================"

mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkKlineCollectorJob" \
    -Dexec.cleanupDaemonThreads=false

echo ""
echo "========================================"
echo "Flink K-line Collector Job stopped"
echo "========================================"
