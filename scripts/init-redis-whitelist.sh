#!/bin/bash

# Redis 白名单初始化脚本
# 功能：初始化交易对白名单到 Redis
# 用途：广播流过滤，只处理白名单中的交易对

echo "=========================================="
echo "Redis 白名单初始化"
echo "=========================================="

# Redis 连接配置
REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
REDIS_KEY="crypto:whitelist"

# 检查 Redis 是否运行
echo "检查 Redis 连接..."
if ! redis-cli -h $REDIS_HOST -p $REDIS_PORT ping > /dev/null 2>&1; then
    echo "错误: Redis 未运行或无法连接"
    echo "请确保 Redis 已启动:"
    echo "  docker-compose -f docker-compose-redis.yml up -d"
    echo "  或者: redis-server"
    exit 1
fi

echo "✓ Redis 连接成功 ($REDIS_HOST:$REDIS_PORT)"

# 清空旧的白名单
echo "清空旧的白名单..."
redis-cli -h $REDIS_HOST -p $REDIS_PORT DEL $REDIS_KEY

# 添加交易对到白名单
echo "添加交易对到白名单..."

# 主流币种
SYMBOLS=(
    "BTC-USDT"
    "ETH-USDT"
    "SOL-USDT"
    "BNB-USDT"
    "XRP-USDT"
    "ADA-USDT"
    "DOGE-USDT"
    "AVAX-USDT"
    "DOT-USDT"
    "MATIC-USDT"
)

# 添加到 Redis Set
for symbol in "${SYMBOLS[@]}"; do
    redis-cli -h $REDIS_HOST -p $REDIS_PORT SADD $REDIS_KEY "$symbol"
    echo "  ✓ $symbol"
done

# 验证白名单
echo ""
echo "=========================================="
echo "白名单验证"
echo "=========================================="
echo "Redis Key: $REDIS_KEY"
echo "交易对数量: $(redis-cli -h $REDIS_HOST -p $REDIS_PORT SCARD $REDIS_KEY)"
echo ""
echo "白名单内容:"
redis-cli -h $REDIS_HOST -p $REDIS_PORT SMEMBERS $REDIS_KEY

echo ""
echo "=========================================="
echo "✓ Redis 白名单初始化完成"
echo "=========================================="
echo ""
echo "使用方法:"
echo "  1. 查看白名单: redis-cli SMEMBERS $REDIS_KEY"
echo "  2. 添加交易对: redis-cli SADD $REDIS_KEY \"LINK-USDT\""
echo "  3. 删除交易对: redis-cli SREM $REDIS_KEY \"LINK-USDT\""
echo "  4. 清空白名单: redis-cli DEL $REDIS_KEY"
echo ""
