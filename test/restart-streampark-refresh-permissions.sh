#!/bin/bash

# StreamPark 权限刷新脚本
# 用于重启容器并刷新 Shiro 权限缓存

echo "=========================================="
echo "StreamPark 权限刷新"
echo "=========================================="

# 1. 停止 StreamPark 容器
echo ""
echo "1. 停止 StreamPark 容器..."
docker-compose -f docker-compose-streampark.yml down

# 2. 等待容器完全停止
echo ""
echo "2. 等待容器完全停止..."
sleep 3

# 3. 启动 StreamPark 容器
echo ""
echo "3. 启动 StreamPark 容器..."
docker-compose -f docker-compose-streampark.yml up -d

# 4. 等待容器启动
echo ""
echo "4. 等待容器启动（约 30 秒）..."
sleep 30

# 5. 检查容器状态
echo ""
echo "5. 检查容器状态..."
docker-compose -f docker-compose-streampark.yml ps

echo ""
echo "=========================================="
echo "✅ StreamPark 已重启"
echo "=========================================="
echo ""
echo "📌 下一步操作："
echo "1. 清除浏览器缓存（Ctrl+Shift+Delete）"
echo "2. 或者使用无痕模式访问 http://localhost:10000"
echo "3. 使用账号密码登录："
echo "   - 用户名: admin"
echo "   - 密码: streampark"
echo "4. 登录后检查系统设置页面是否可以访问"
echo ""
