#!/bin/bash

# Flink 启动测试脚本

echo "=========================================="
echo "测试 Flink 启动"
echo "=========================================="

# 设置 Java 8
export JAVA_HOME="/e/DataFiles/Java/jdk1.8.0_321"
export PATH="$JAVA_HOME/bin:$PATH"

echo "1. Java 版本:"
java -version
echo ""

# 进入 Flink 目录
cd /e/DataFiles/flink-1.17.0

echo "2. 检查 Flink 文件:"
ls -lh lib/flink-dist-1.17.0.jar
echo ""

echo "3. 测试 Flink 命令:"
./bin/flink --version
echo ""

echo "4. 检查现有进程:"
jps | grep -E "Standalone|TaskManager"
echo ""

echo "5. 检查端口占用:"
netstat -ano | grep -E "6123|8081" | head -5
echo ""

echo "6. 尝试启动 Flink (使用默认配置):"
./bin/start-cluster.sh

echo ""
echo "7. 等待 5 秒..."
sleep 5

echo ""
echo "8. 检查进程:"
jps | grep -E "Standalone|TaskManager"

echo ""
echo "9. 检查 Web UI:"
curl -s http://localhost:8081 > /dev/null && echo "✓ Web UI 可访问" || echo "✗ Web UI 无法访问"

echo ""
echo "10. 查看最新日志:"
tail -20 log/flink-*-standalonesession-*.out 2>/dev/null || echo "没有找到日志文件"
