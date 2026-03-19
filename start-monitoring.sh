#!/bin/bash

# 启动 Prometheus + Grafana 监控系统
# 用于监控 Flink 任务

echo "=========================================="
echo "启动监控系统"
echo "=========================================="
echo

# 检查 Docker
if ! command -v docker &> /dev/null; then
    echo "✗ Docker 未安装"
    exit 1
fi

if ! command -v docker-compose &> /dev/null; then
    echo "✗ Docker Compose 未安装"
    exit 1
fi

echo "✓ Docker 和 Docker Compose 已安装"
echo

# 创建必要的目录
echo "创建配置目录..."
mkdir -p monitoring/prometheus
mkdir -p monitoring/grafana/provisioning/datasources
mkdir -p monitoring/grafana/provisioning/dashboards
mkdir -p monitoring/grafana/dashboards
echo "✓ 配置目录已创建"
echo

# 启动监控服务
echo "启动监控服务..."
docker-compose -f docker-compose-monitoring.yml up -d

# 等待服务启动
echo
echo "等待服务启动..."
sleep 10

# 检查服务状态
echo
echo "检查服务状态..."
docker-compose -f docker-compose-monitoring.yml ps

echo
echo "=========================================="
echo "监控系统启动完成"
echo "=========================================="
echo
echo "访问地址:"
echo "  Prometheus: http://localhost:9090"
echo "  Grafana:    http://localhost:3000"
echo "  Pushgateway: http://localhost:9091"
echo
echo "Grafana 默认账号:"
echo "  用户名: admin"
echo "  密码:   admin"
echo
echo "提示:"
echo "  1. 首次登录 Grafana 后可以修改密码"
echo "  2. Dashboard 已自动加载到 Grafana"
echo "  3. 数据源已自动配置"
echo
