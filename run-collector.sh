#!/bin/bash

# 数据采集器运行脚本

echo "=========================================="
echo "启动加密货币数据采集器"
echo "=========================================="
echo ""

# 检查 .env 文件
if [ ! -f .env ]; then
    echo "⚠ .env 文件不存在"
    echo "正在从 .env.example 创建 .env 文件..."
    cp .env.example .env
    echo "✓ .env 文件已创建"
    echo ""
    echo "注意: OKX 公开 WebSocket 不需要 API 凭证"
    echo "如果需要访问私有数据，请编辑 .env 文件并填入 API 凭证"
    echo ""
fi

# 加载环境变量
if [ -f .env ]; then
    echo "加载环境变量..."
    export $(cat .env | grep -v '^#' | xargs)
fi

# 设置默认环境
if [ -z "$APP_ENV" ]; then
    export APP_ENV=dev
fi

echo "当前环境: $APP_ENV"
echo ""

# 检查 Kafka 是否运行
echo "检查 Kafka 状态..."
docker ps | grep kafka > /dev/null
if [ $? -ne 0 ]; then
    echo "❌ Kafka 容器未运行"
    echo "请先启动 Kafka: docker-compose up -d"
    exit 1
fi
echo "✓ Kafka 运行正常"
echo ""

# 编译项目
echo "编译项目..."
mvn clean compile -q
if [ $? -ne 0 ]; then
    echo "❌ 编译失败"
    exit 1
fi
echo "✓ 编译成功"
echo ""

# 运行数据采集器
echo "启动数据采集器..."
echo "订阅交易对: ${1:-BTC-USDT}"
echo ""
echo "按 Ctrl+C 停止"
echo "=========================================="
echo ""

# 运行主程序
mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.collector.DataCollectorMain" \
    -Dexec.args="${1:-BTC-USDT}" \
    -Dexec.cleanupDaemonThreads=false
