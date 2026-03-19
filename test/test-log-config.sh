#!/bin/bash

# 测试日志配置
# 验证日志目录和滚动配置

echo "=========================================="
echo "测试日志配置"
echo "=========================================="
echo

# 1. 检查日志目录
echo "1. 检查日志目录结构..."
for dir in app flink doris kafka error; do
    if [ -d "logs/$dir" ]; then
        echo "  ✓ logs/$dir 存在"
    else
        echo "  ✗ logs/$dir 不存在"
        mkdir -p "logs/$dir"
        echo "    已创建 logs/$dir"
    fi
done

echo
echo "2. 查看日志目录大小..."
du -sh logs/*/

echo
echo "3. 查看日志文件..."
find logs/ -name "*.log" -o -name "*.log.gz" | head -20

echo
echo "4. 测试日志写入..."
echo "Test log entry at $(date)" >> logs/app/test.log
if [ -f "logs/app/test.log" ]; then
    echo "  ✓ 日志写入成功"
    rm logs/app/test.log
else
    echo "  ✗ 日志写入失败"
fi

echo
echo "5. Log4j2 配置检查..."
if [ -f "src/main/resources/log4j2.properties" ]; then
    echo "  ✓ log4j2.properties 存在"
    echo "  滚动间隔: $(grep 'policies.time.interval' src/main/resources/log4j2.properties | head -1)"
    echo "  保留时间: $(grep 'ifLastModified.age' src/main/resources/log4j2.properties | head -1)"
else
    echo "  ✗ log4j2.properties 不存在"
fi

echo
echo "=========================================="
echo "测试完成"
echo "=========================================="
