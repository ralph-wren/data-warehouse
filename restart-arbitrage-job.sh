#!/bin/bash

# 重启套利交易作业脚本

echo "========================================="
echo "重启套利交易作业"
echo "========================================="

# 1. 停止当前运行的作业（如果有）
echo ""
echo "1. 停止当前运行的作业..."
# 查找正在运行的 FlinkADSArbitrageJob 进程
PID=$(ps aux | grep FlinkADSArbitrageJob | grep -v grep | awk '{print $2}')

if [ -n "$PID" ]; then
    echo "   找到运行中的作业，PID: $PID"
    kill $PID
    echo "   等待作业停止..."
    sleep 3
    echo "   ✓ 作业已停止"
else
    echo "   没有找到运行中的作业"
fi

# 2. 重新编译打包
echo ""
echo "2. 重新编译打包..."
mvn clean package -DskipTests
if [ $? -ne 0 ]; then
    echo "   ✗ 编译失败！"
    exit 1
fi
echo "   ✓ 编译成功"

# 3. 启动新的作业
echo ""
echo "3. 启动新的作业..."
bash run-flink-ads-arbitrage.sh

echo ""
echo "========================================="
echo "✓ 作业重启完成"
echo "========================================="
echo ""
echo "提示："
echo "  - 查看日志: tail -f logs/trading/order_detail_*.csv"
echo "  - 查看持仓: tail -f logs/trading/position_summary_*.csv"
