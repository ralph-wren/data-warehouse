#!/bin/bash
# 生成增强版 Grafana Flink 监控面板
# 包含更多常用指标

cat > monitoring/grafana/dashboards/flink-monitoring.json << 'EOF'
{
  "annotations": {
    "list": [
      {
        "builtIn": 1,
        "datasource": "-- Grafana --",
        "enable": true,
        "hide": true,
        "iconColor": "rgba(0, 211, 255, 1)",
        "name": "Annotations & Alerts",
        "type": "dashboard"
      }
    ]
  },
  "editable": true,
  "gnetId": null,
  "graphTooltip": 1,
  "id": null,
  "links": [],
  "panels": [
EOF

echo "Dashboard 配置文件已创建"
