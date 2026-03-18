#!/bin/bash

# 环境设置脚本
# 用于设置环境变量并验证配置

echo "=========================================="
echo "环境设置"
echo "=========================================="
echo ""

# 检查 .env 文件
if [ ! -f .env ]; then
    echo "⚠ .env 文件不存在"
    echo "正在从 .env.example 创建 .env 文件..."
    cp .env.example .env
    echo "✓ .env 文件已创建"
    echo ""
    echo "请编辑 .env 文件并填入实际的 OKX API 凭证:"
    echo "  - OKX_API_KEY"
    echo "  - OKX_SECRET_KEY"
    echo "  - OKX_PASSPHRASE"
    echo ""
    echo "然后重新运行此脚本"
    exit 1
fi

# 加载环境变量
echo "加载环境变量..."
export $(cat .env | grep -v '^#' | xargs)

# 验证必需的环境变量
echo ""
echo "验证环境变量..."
MISSING_VARS=0

if [ -z "$OKX_API_KEY" ]; then
    echo "  ❌ OKX_API_KEY 未设置"
    MISSING_VARS=1
else
    echo "  ✓ OKX_API_KEY 已设置"
fi

if [ -z "$OKX_SECRET_KEY" ]; then
    echo "  ❌ OKX_SECRET_KEY 未设置"
    MISSING_VARS=1
else
    echo "  ✓ OKX_SECRET_KEY 已设置"
fi

if [ -z "$OKX_PASSPHRASE" ]; then
    echo "  ❌ OKX_PASSPHRASE 未设置"
    MISSING_VARS=1
else
    echo "  ✓ OKX_PASSPHRASE 已设置"
fi

if [ $MISSING_VARS -eq 1 ]; then
    echo ""
    echo "❌ 缺少必需的环境变量"
    echo "请编辑 .env 文件并填入实际值"
    exit 1
fi

echo ""
echo "✓ 所有必需的环境变量已设置"
echo ""
echo "当前环境: ${APP_ENV:-dev}"
echo ""

# 测试 Doris 连接
echo "测试 Doris 连接..."
bash test-doris-connection.sh > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✓ Doris 连接正常"
else
    echo "❌ Doris 连接失败"
    exit 1
fi

echo ""

# 测试 Kafka 连接
echo "测试 Kafka 连接..."
docker exec kafka kafka-broker-api-versions --bootstrap-server localhost:9092 > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "✓ Kafka 连接正常"
else
    echo "❌ Kafka 连接失败"
    echo "请确保 Kafka 容器正在运行: docker-compose up -d"
    exit 1
fi

echo ""
echo "=========================================="
echo "环境设置完成"
echo "=========================================="
echo ""
echo "下一步:"
echo "  1. 运行配置测试: bash test-config.sh"
echo "  2. 编译项目: mvn clean package"
echo "  3. 运行数据采集器: java -jar target/realtime-crypto-datawarehouse-1.0.0.jar"
echo ""
