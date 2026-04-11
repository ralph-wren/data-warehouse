#!/bin/bash

# 监控持仓限制功能
# 每 5 秒检查一次 Redis 持仓计数和最新的交易日志

echo "=========================================="
echo "持仓限制监控"
echo "=========================================="
echo "配置: max-positions = 1"
echo "Redis Key: okx:arbitrage:position:count"
echo "=========================================="
echo ""

for i in {1..20}; do
    echo "[$i] $(date '+%Y-%m-%d %H:%M:%S')"
    
    # 查看 Redis 持仓计数
    COUNT=$(redis-cli GET okx:arbitrage:position:count 2>/dev/null)
    if [ -z "$COUNT" ] || [ "$COUNT" = "(nil)" ]; then
        COUNT=0
    fi
    echo "  Redis 持仓计数: $COUNT"
    
    # 查看最近 10 秒的交易日志
    echo "  最近交易:"
    tail -50 logs/trading_$(date +%Y%m%d).csv 2>/dev/null | \
        awk -F',' -v cutoff="$(date -d '10 seconds ago' '+%Y-%m-%d %H:%M:%S' 2>/dev/null || date -v-10S '+%Y-%m-%d %H:%M:%S' 2>/dev/null)" \
        '$1 > cutoff {print "    " $1 " | " $2 " | " $3 " | " $10}' | tail -5
    
    # 查看最近的持仓限制日志
    echo "  持仓限制日志:"
    tail -100 logs/all/all.log 2>/dev/null | \
        grep -E "(预留持仓|回退持仓|已达到最大|开仓成功|⏸)" | \
        tail -3 | \
        sed 's/^/    /'
    
    echo ""
    
    if [ $i -lt 20 ]; then
        sleep 5
    fi
done

echo "=========================================="
echo "监控完成"
echo "=========================================="
