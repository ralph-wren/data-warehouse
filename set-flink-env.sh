#!/bin/bash

# Flink 环境变量设置脚本
# 用于所有 Flink 作业启动脚本

# 设置 Java 临时目录为短路径,避免 Windows 260 字符路径长度限制
export JAVA_TOOL_OPTIONS="-Djava.io.tmpdir=/c/flink-temp"

# 创建临时目录
mkdir -p /c/flink-temp

echo "Flink 环境变量已设置:"
echo "  JAVA_TOOL_OPTIONS: $JAVA_TOOL_OPTIONS"
echo ""
