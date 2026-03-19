#!/bin/bash

# 停止 Prometheus + Grafana 监控系统

echo "=========================================="
echo "停止监控系统"
echo "=========================================="
echo

# 停止服务
docker-compose -f docker-compose-monitoring.yml down

echo
echo "✓ 监控系统已停止"
echo
echo "提示:"
echo "  数据已保留在 Docker volumes 中"
echo "  下次启动时数据会自动恢复"
echo
echo "如需完全清理数据，运行:"
echo "  docker-compose -f docker-compose-monitoring.yml down -v"
echo
