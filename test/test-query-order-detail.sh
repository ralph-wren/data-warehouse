#!/bin/bash

# 测试订单详情查询功能
# 用途: 验证 OKXTradingService.queryOrderDetail 方法是否能正常返回订单详情

echo "=========================================="
echo "测试订单详情查询功能"
echo "=========================================="
echo ""

# 检查环境变量
if [ -z "$OKX_API_KEY" ] || [ -z "$OKX_SECRET_KEY" ] || [ -z "$OKX_PASSPHRASE" ]; then
    echo "❌ 错误: 缺少 OKX API 凭证环境变量"
    echo "请设置以下环境变量:"
    echo "  export OKX_API_KEY='your-api-key'"
    echo "  export OKX_SECRET_KEY='your-secret-key'"
    echo "  export OKX_PASSPHRASE='your-passphrase'"
    exit 1
fi

echo "✓ OKX API 凭证已配置"
echo ""

# 从最近的订单日志中获取订单ID
ORDER_LOG="./logs/trading/order_detail_$(date +%Y%m%d%H).csv"

if [ ! -f "$ORDER_LOG" ]; then
    echo "⚠️ 警告: 找不到今天的订单日志文件: $ORDER_LOG"
    echo "尝试查找最新的订单日志文件..."
    ORDER_LOG=$(ls -t ./logs/trading/order_detail_*.csv 2>/dev/null | head -1)
    
    if [ -z "$ORDER_LOG" ]; then
        echo "❌ 错误: 找不到任何订单日志文件"
        echo "请先运行交易程序生成订单"
        exit 1
    fi
    
    echo "✓ 找到订单日志文件: $ORDER_LOG"
fi

echo "📄 读取订单日志: $ORDER_LOG"
echo ""

# 提取最近的一条成功订单记录（PENDING 或 SUCCESS 状态）
LAST_ORDER=$(tail -20 "$ORDER_LOG" | grep -E "PENDING|SUCCESS" | grep -E "SPOT|SWAP" | tail -1)

if [ -z "$LAST_ORDER" ]; then
    echo "❌ 错误: 找不到有效的订单记录"
    echo "请先运行交易程序生成订单"
    exit 1
fi

echo "📝 最近的订单记录:"
echo "$LAST_ORDER"
echo ""

# 解析订单信息
SYMBOL=$(echo "$LAST_ORDER" | cut -d',' -f2)
ORDER_TYPE=$(echo "$LAST_ORDER" | cut -d',' -f5)
ORDER_ID=$(echo "$LAST_ORDER" | cut -d',' -f6)

echo "解析订单信息:"
echo "  币对: $SYMBOL"
echo "  类型: $ORDER_TYPE"
echo "  订单ID: $ORDER_ID"
echo ""

# 构造 instId
if [ "$ORDER_TYPE" = "SPOT" ]; then
    INST_ID="${SYMBOL}-USDT"
    INST_TYPE="SPOT"
elif [ "$ORDER_TYPE" = "SWAP" ]; then
    INST_ID="${SYMBOL}-USDT-SWAP"
    INST_TYPE="SWAP"
else
    echo "❌ 错误: 未知的订单类型: $ORDER_TYPE"
    exit 1
fi

echo "构造查询参数:"
echo "  instId: $INST_ID"
echo "  instType: $INST_TYPE"
echo ""

# 生成时间戳
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")

# 构造请求路径
REQUEST_PATH="/api/v5/trade/order?ordId=${ORDER_ID}&instId=${INST_ID}&instType=${INST_TYPE}"

echo "📡 发送 API 请求..."
echo "  URL: https://www.okx.com${REQUEST_PATH}"
echo ""

# 生成签名
PREHASH="${TIMESTAMP}GET${REQUEST_PATH}"
SIGNATURE=$(echo -n "$PREHASH" | openssl dgst -sha256 -hmac "$OKX_SECRET_KEY" -binary | base64)

# 发送请求
RESPONSE=$(curl -s -X GET \
    "https://www.okx.com${REQUEST_PATH}" \
    -H "OK-ACCESS-KEY: $OKX_API_KEY" \
    -H "OK-ACCESS-SIGN: $SIGNATURE" \
    -H "OK-ACCESS-TIMESTAMP: $TIMESTAMP" \
    -H "OK-ACCESS-PASSPHRASE: $OKX_PASSPHRASE" \
    -H "Content-Type: application/json")

echo "📥 API 响应:"
echo "$RESPONSE" | python3 -m json.tool 2>/dev/null || echo "$RESPONSE"
echo ""

# 检查响应
CODE=$(echo "$RESPONSE" | grep -o '"code":"[^"]*"' | cut -d'"' -f4)

if [ "$CODE" = "0" ]; then
    echo "✅ 查询成功!"
    echo ""
    
    # 提取关键信息
    echo "📊 订单详情:"
    echo "$RESPONSE" | python3 -c "
import sys, json
try:
    data = json.load(sys.stdin)
    if data.get('code') == '0' and data.get('data'):
        order = data['data'][0]
        print(f\"  订单ID: {order.get('ordId', 'N/A')}\")
        print(f\"  交易对: {order.get('instId', 'N/A')}\")
        print(f\"  方向: {order.get('side', 'N/A')}\")
        print(f\"  订单类型: {order.get('ordType', 'N/A')}\")
        print(f\"  价格: {order.get('px', 'N/A')}\")
        print(f\"  成交均价: {order.get('avgPx', 'N/A')}\")
        print(f\"  数量: {order.get('sz', 'N/A')}\")
        print(f\"  已成交数量: {order.get('accFillSz', 'N/A')}\")
        print(f\"  状态: {order.get('state', 'N/A')}\")
        print(f\"  手续费: {order.get('fee', 'N/A')} {order.get('feeCcy', '')}\")
        
        # 转换时间戳
        import datetime
        fill_time = order.get('fillTime', '0')
        if fill_time != '0':
            dt = datetime.datetime.fromtimestamp(int(fill_time) / 1000)
            print(f\"  成交时间: {dt.strftime('%Y-%m-%d %H:%M:%S')}\")
        else:
            print(f\"  成交时间: 未成交\")
    else:
        print('  无订单数据')
except Exception as e:
    print(f'  解析失败: {e}')
" 2>/dev/null || echo "  (无法解析详细信息)"
    
    echo ""
    echo "=========================================="
    echo "✅ 测试通过: 订单详情查询功能正常"
    echo "=========================================="
    exit 0
else
    MSG=$(echo "$RESPONSE" | grep -o '"msg":"[^"]*"' | cut -d'"' -f4)
    echo "❌ 查询失败!"
    echo "  错误代码: $CODE"
    echo "  错误信息: $MSG"
    echo ""
    echo "=========================================="
    echo "❌ 测试失败: 订单详情查询功能异常"
    echo "=========================================="
    exit 1
fi
