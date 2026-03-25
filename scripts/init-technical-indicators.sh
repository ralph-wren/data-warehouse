#!/bin/bash

# 初始化技术指标配置到Redis
# 这些配置会被Flink作业的广播流读取并动态更新

# 加载环境变量
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_PASSWORD=${REDIS_PASSWORD:-}

echo "=========================================="
echo "初始化技术指标配置到Redis"
echo "Redis地址: $REDIS_HOST:$REDIS_PORT"
echo "=========================================="

# 定义指标配置
# 格式: config_id|symbol|indicator_type|indicator_name|params|enabled

indicators=(
    # 移动平均线（MA）
    "ma_5|*|MA|MA5|{\"period\":\"5\"}|true"
    "ma_10|*|MA|MA10|{\"period\":\"10\"}|true"
    "ma_20|*|MA|MA20|{\"period\":\"20\"}|true"
    "ma_60|*|MA|MA60|{\"period\":\"60\"}|true"
    
    # 指数移动平均线（EMA）
    "ema_12|*|EMA|EMA12|{\"period\":\"12\"}|true"
    "ema_26|*|EMA|EMA26|{\"period\":\"26\"}|true"
    
    # 相对强弱指标（RSI）
    "rsi_6|*|RSI|RSI6|{\"period\":\"6\"}|true"
    "rsi_12|*|RSI|RSI12|{\"period\":\"12\"}|true"
    "rsi_24|*|RSI|RSI24|{\"period\":\"24\"}|true"
    
    # MACD指标
    "macd_default|*|MACD|MACD|{\"short_period\":\"12\",\"long_period\":\"26\",\"signal_period\":\"9\"}|true"
    
    # 布林带（BOLL）
    "boll_20|*|BOLL|BOLL20|{\"period\":\"20\",\"k\":\"2.0\"}|true"
    
    # KDJ指标
    "kdj_9|*|KDJ|KDJ9|{\"period\":\"9\"}|true"
    
    # 平均真实波幅（ATR）
    "atr_14|*|ATR|ATR14|{\"period\":\"14\"}|true"
    
    # 特定交易对的指标
    "btc_ma_30|BTC-USDT|MA|BTC_MA30|{\"period\":\"30\"}|true"
    "eth_ma_30|ETH-USDT|MA|ETH_MA30|{\"period\":\"30\"}|true"
)

# 写入Redis
for indicator in "${indicators[@]}"; do
    IFS='|' read -r config_id symbol indicator_type indicator_name params enabled <<< "$indicator"
    
    key="indicator:config:$config_id"
    value=$(cat <<EOF
{
    "config_id": "$config_id",
    "symbol": "$symbol",
    "indicator_type": "$indicator_type",
    "indicator_name": "$indicator_name",
    "params": $params,
    "enabled": $enabled,
    "update_time": $(date +%s)000
}
EOF
)
    
    if [ -n "$REDIS_PASSWORD" ]; then
        redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD SET "$key" "$value" > /dev/null
    else
        redis-cli -h $REDIS_HOST -p $REDIS_PORT SET "$key" "$value" > /dev/null
    fi
    
    echo "✓ 已设置指标: $indicator_name ($indicator_type, 交易对: $symbol)"
done

echo "=========================================="
echo "技术指标配置初始化完成！"
echo "共设置 ${#indicators[@]} 个指标配置"
echo "=========================================="

# 验证写入
echo ""
echo "验证Redis中的指标配置数量:"
if [ -n "$REDIS_PASSWORD" ]; then
    count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD KEYS "indicator:config:*" | wc -l)
else
    count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT KEYS "indicator:config:*" | wc -l)
fi
echo "Redis中共有 $count 个指标配置"

echo ""
echo "示例配置:"
if [ -n "$REDIS_PASSWORD" ]; then
    redis-cli -h $REDIS_HOST -p $REDIS_PORT -a $REDIS_PASSWORD GET "indicator:config:ma_20"
else
    redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "indicator:config:ma_20"
fi
