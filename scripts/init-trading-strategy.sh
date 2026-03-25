#!/bin/bash

# 初始化交易策略到Redis
# 这些策略会被Flink作业的广播流读取并动态更新

# 加载环境变量
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_PASSWORD=${REDIS_PASSWORD:-}

echo "=========================================="
echo "初始化交易策略到Redis"
echo "Redis地址: $REDIS_HOST:$REDIS_PORT"
echo "=========================================="

# 定义交易策略配置
# 格式: symbol|risk_threshold|volume_threshold|price_threshold|strategy_type

strategies=(
    # 主流币 - 保守策略
    "BTC-USDT|30|5000000|1.0|CONSERVATIVE"
    "ETH-USDT|35|3000000|1.2|CONSERVATIVE"
    
    # 主流币 - 中等策略
    "BNB-USDT|50|1000000|1.5|MODERATE"
    "SOL-USDT|50|1000000|1.5|MODERATE"
    "XRP-USDT|50|1000000|1.5|MODERATE"
    
    # 山寨币 - 激进策略
    "DOGE-USDT|70|500000|2.0|AGGRESSIVE"
    "SHIB-USDT|70|500000|2.0|AGGRESSIVE"
    "PEPE-USDT|75|300000|2.5|AGGRESSIVE"
    
    # DeFi代币 - 中等策略
    "UNI-USDT|50|800000|1.5|MODERATE"
    "AAVE-USDT|50|800000|1.5|MODERATE"
    "LINK-USDT|45|1000000|1.3|MODERATE"
    
    # Layer2代币 - 激进策略
    "ARB-USDT|65|600000|1.8|AGGRESSIVE"
    "OP-USDT|65|600000|1.8|AGGRESSIVE"
    
    # AI概念币 - 激进策略
    "FET-USDT|70|400000|2.0|AGGRESSIVE"
    "AGIX-USDT|70|400000|2.0|AGGRESSIVE"
)

# 写入Redis
for strategy in "${strategies[@]}"; do
    IFS='|' read -r symbol risk_threshold volume_threshold price_threshold strategy_type <<< "$strategy"
    
    key="trading:strategy:$symbol"
    value=$(cat <<EOF
{
    "symbol": "$symbol",
    "risk_threshold": "$risk_threshold",
    "volume_threshold": "$volume_threshold",
    "price_threshold": "$price_threshold",
    "strategy_type": "$strategy_type",
    "update_time": $(date +%s)000
}
EOF
)
    
    if [ -n "$REDIS_PASSWORD" ]; then
        redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD SET "$key" "$value" > /dev/null
    else
        redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$key" "$value" > /dev/null
    fi
    
    echo "✓ 已设置策略: $symbol ($strategy_type, 风险阈值: $risk_threshold)"
done

echo "=========================================="
echo "交易策略初始化完成！"
echo "共设置 ${#strategies[@]} 个交易对的策略"
echo "=========================================="

# 验证写入
echo ""
echo "验证Redis中的策略数量:"
if [ -n "$REDIS_PASSWORD" ]; then
    count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD KEYS "trading:strategy:*" | wc -l)
else
    count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT KEYS "trading:strategy:*" | wc -l)
fi
echo "Redis中共有 $count 个交易策略"
