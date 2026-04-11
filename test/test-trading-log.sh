#!/bin/bash

# 测试交易日志功能
# 验证 CSV 日志文件是否正确创建

echo "=========================================="
echo "测试交易日志功能"
echo "=========================================="

# 检查 logs 目录
if [ -d "logs" ]; then
    echo "✓ logs 目录存在"
    
    # 查找今天的日志文件
    TODAY=$(date +%Y%m%d)
    LOG_FILE="logs/trading_${TODAY}.csv"
    
    if [ -f "$LOG_FILE" ]; then
        echo "✓ 找到今天的日志文件: $LOG_FILE"
        echo ""
        echo "文件内容:"
        echo "----------------------------------------"
        cat "$LOG_FILE"
        echo "----------------------------------------"
        echo ""
        echo "文件行数: $(wc -l < "$LOG_FILE")"
    else
        echo "⚠ 未找到今天的日志文件: $LOG_FILE"
        echo ""
        echo "logs 目录下的文件:"
        ls -lh logs/
    fi
else
    echo "✗ logs 目录不存在"
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
