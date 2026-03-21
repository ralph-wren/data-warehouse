#!/bin/bash

# StreamPark 停止脚本

echo "=========================================="
echo "停止 StreamPark 服务"
echo "=========================================="

docker-compose -f docker-compose-streampark.yml down

echo ""
echo "StreamPark 已停止"
echo ""
echo "如需删除数据卷，执行: docker-compose -f docker-compose-streampark.yml down -v"
