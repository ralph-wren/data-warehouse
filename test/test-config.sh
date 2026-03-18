#!/bin/bash

# 配置测试脚本

echo "=========================================="
echo "配置加载测试"
echo "=========================================="
echo ""

# 检查环境变量
echo "检查环境变量..."
if [ -z "$OKX_API_KEY" ]; then
    echo "  ❌ OKX_API_KEY 未设置"
else
    echo "  ✓ OKX_API_KEY 已设置"
fi

if [ -z "$OKX_SECRET_KEY" ]; then
    echo "  ❌ OKX_SECRET_KEY 未设置"
else
    echo "  ✓ OKX_SECRET_KEY 已设置"
fi

if [ -z "$OKX_PASSPHRASE" ]; then
    echo "  ❌ OKX_PASSPHRASE 未设置"
else
    echo "  ✓ OKX_PASSPHRASE 已设置"
fi

echo ""
echo "当前环境: ${APP_ENV:-dev}"
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

# 运行配置测试
echo "运行配置测试..."
mvn exec:java -Dexec.mainClass="com.crypto.dw.ConfigTest" -q

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
