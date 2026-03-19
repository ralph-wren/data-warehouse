#!/bin/bash

# 测试 Flink Web UI
# 快速启动一个简单的 Flink 作业来验证 Web UI

echo "=========================================="
echo "测试 Flink Web UI"
echo "=========================================="
echo

# 1. 检查端口是否被占用
echo "1. 检查端口 8081..."
if netstat -an | grep -q ":8081"; then
    echo "  ⚠ 端口 8081 已被占用"
    echo "  正在使用端口 8081 的进程："
    netstat -ano | grep ":8081" | head -5
else
    echo "  ✓ 端口 8081 可用"
fi

echo
echo "2. 编译项目..."
mvn compile -DskipTests -q
if [ $? -eq 0 ]; then
    echo "  ✓ 编译成功"
else
    echo "  ✗ 编译失败"
    exit 1
fi

echo
echo "3. 启动 Flink DWD SQL 作业（后台运行）..."
echo "  作业将在后台启动，Web UI 将在 http://localhost:8081 可用"
echo

# 启动作业（后台运行）
nohup bash run-flink-dwd-sql.sh > logs/test-webui.log 2>&1 &
PID=$!

echo "  ✓ 作业已启动，PID: $PID"
echo

echo "4. 等待 Web UI 启动（最多等待 30 秒）..."
for i in {1..30}; do
    if curl -s http://localhost:8081 > /dev/null 2>&1; then
        echo "  ✓ Web UI 已启动"
        break
    fi
    echo -n "."
    sleep 1
done
echo

echo
echo "=========================================="
echo "测试结果"
echo "=========================================="
echo
echo "✓ Flink 作业 PID: $PID"
echo "✓ Web UI 地址: http://localhost:8081"
echo
echo "访问 Web UI："
echo "  Windows: start http://localhost:8081"
echo "  Mac:     open http://localhost:8081"
echo "  Linux:   xdg-open http://localhost:8081"
echo
echo "查看日志："
echo "  tail -f logs/test-webui.log"
echo "  tail -f logs/app/flink-app.log"
echo
echo "停止作业："
echo "  kill $PID"
echo
echo "=========================================="
