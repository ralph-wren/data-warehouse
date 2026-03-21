#!/bin/bash

# Flink Docker 集群停止脚本

echo "=========================================="
echo "停止 Flink Docker 集群"
echo "=========================================="

docker-compose -f docker-compose-flink.yml down

echo ""
echo "Flink 集群已停止"
echo ""
