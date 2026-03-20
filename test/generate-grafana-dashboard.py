#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
生成增强版 Grafana Flink 监控面板
添加更多常用指标，包括：
- 数据流入/流出速率
- Checkpoint 指标
- JVM 内存和 CPU
- 任务状态
- 延迟指标
- 背压指标
"""

import json

# 创建增强版 Dashboard 配置
dashboard = {
    "annotations": {
        "list": [
            {
                "builtIn": 1,
                "datasource": "-- Grafana --",
                "enable": True,
                "hide": True,
                "iconColor": "rgba(0, 211, 255, 1)",
                "name": "Annotations & Alerts",
                "type": "dashboard"
            }
        ]
    },
    "editable": True,
    "gnetId": None,
    "graphTooltip": 0,
    "id": None,
    "links": [],
    "panels": [],
    "refresh": "5s",
    "schemaVersion": 27,
    "style": "dark",
    "tags": ["flink", "monitoring", "crypto-dw"],
    "templating": {
        "list": [
            {
                "allValue": None,
                "current": {},
                "datasource": "Prometheus",
