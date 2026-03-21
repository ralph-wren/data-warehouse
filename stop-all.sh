#!/bin/bash

# 停止所有服务的脚本

echo "=========================================="
echo "Stopping Crypto Data Warehouse Services"
echo "=========================================="
echo ""

# 1. 停止监控系统
echo "Step 1: Stopping monitoring system..."
bash manage-monitoring.sh stop
echo ""

# 2. 停止 Flink 集群
echo "Step 2: Stopping Flink cluster..."
bash manage-flink.sh stop
echo ""

# 3. 停止 Doris
echo "Step 3: Stopping Doris..."
bash manage-doris.sh stop
echo ""

# 4. 停止 Kafka
echo "Step 4: Stopping Kafka..."
bash manage-kafka.sh stop
echo ""

# 5. 停止 StreamPark（如果运行）
echo "Step 5: Stopping StreamPark..."
bash manage-streampark.sh stop
echo ""

echo "=========================================="
echo "All services stopped!"
echo "=========================================="
