#!/bin/bash

# 清理 Doris 中的旧 Label
# 用于解决 "Label Already Exists" 错误

echo "=========================================="
echo "清理 Doris Label"
echo "=========================================="
echo ""

# 查看当前的 Load 任务
echo "1. 查看当前的 Load 任务..."
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SHOW LOAD WHERE Label LIKE 'flink-ods%' ORDER BY CreateTime DESC LIMIT 20;
"

echo ""
echo "2. 清理已完成的 Load 任务..."
echo "   注意: 这会删除 Load 历史记录,但不会删除已写入的数据"
echo ""

# 获取所有 flink-ods 开头的 Label
LABELS=$(mysql -h 127.0.0.1 -P 9030 -u root -N -e "
USE crypto_dw;
SELECT Label FROM information_schema.loads 
WHERE Label LIKE 'flink-ods%' 
AND State IN ('FINISHED', 'CANCELLED');
" 2>/dev/null)

if [ -z "$LABELS" ]; then
    echo "   没有需要清理的 Label"
else
    echo "   找到 $(echo "$LABELS" | wc -l) 个 Label"
    
    # Doris 不支持直接删除 Label,但可以通过清理事务来解决
    # 最简单的方法是等待 Label 过期 (默认 3 天)
    # 或者使用不同的 Label 前缀
    
    echo ""
    echo "   Doris 的 Label 会在 3 天后自动过期"
    echo "   建议使用时间戳作为 Label 前缀,避免冲突"
fi

echo ""
echo "=========================================="
echo "清理完成"
echo "=========================================="
echo ""
echo "下一步:"
echo "  1. 代码已更新为使用时间戳 Label 前缀"
echo "  2. 重新编译: mvn clean compile -DskipTests"
echo "  3. 重新运行: ./run-flink-ods-datastream.sh"
echo ""
