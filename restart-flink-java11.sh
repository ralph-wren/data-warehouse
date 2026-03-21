#!/bin/bash

# Flink 集群重启脚本 - 升级到 Java 11
# 用于解决 Java 版本不兼容问题

echo "=========================================="
echo "Flink 集群重启 - 升级到 Java 11"
echo "=========================================="

# 停止现有的 Flink 集群
echo ""
echo "1. 停止现有的 Flink 集群..."
docker-compose -f docker-compose-flink.yml down

# 等待容器完全停止
echo ""
echo "2. 等待容器完全停止..."
sleep 3

# 拉取 Java 11 镜像
echo ""
echo "3. 拉取 Flink Java 11 镜像..."
docker pull flink:1.17.0-scala_2.12-java11

# 启动新的 Flink 集群
echo ""
echo "4. 启动 Flink 集群（Java 11）..."
docker-compose -f docker-compose-flink.yml up -d

# 等待服务启动
echo ""
echo "5. 等待服务启动..."
sleep 10

# 检查服务状态
echo ""
echo "6. 检查服务状态..."
docker-compose -f docker-compose-flink.yml ps

# 检查 Java 版本
echo ""
echo "7. 验证 Java 版本..."
echo "JobManager Java 版本:"
docker exec flink-jobmanager java -version

echo ""
echo "TaskManager Java 版本:"
docker exec flink-taskmanager java -version

# 检查 Flink Web UI
echo ""
echo "=========================================="
echo "✅ Flink 集群重启完成！"
echo "=========================================="
echo ""
echo "Flink Web UI: http://localhost:8081"
echo ""
echo "下一步："
echo "1. 访问 Flink Web UI 确认集群正常运行"
echo "2. 在 StreamPark 中重新提交作业"
echo "3. 验证作业是否成功运行"
echo ""
