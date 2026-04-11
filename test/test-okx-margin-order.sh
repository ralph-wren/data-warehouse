#!/bin/bash

# OKX 现货杠杆下单测试脚本
# 用于测试现货杠杆模式下单,诊断最小下单金额问题

echo "=========================================="
echo "OKX 现货杠杆下单测试"
echo "=========================================="
echo ""

# 检查环境变量
if [ -z "$OKX_API_KEY" ] || [ -z "$OKX_SECRET_KEY" ] || [ -z "$OKX_PASSPHRASE" ]; then
    echo "❌ 错误: 请设置 OKX API 环境变量"
    echo "   OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE"
    exit 1
fi

echo "✓ API 配置已加载"
echo ""

# 测试交易对
SYMBOL="BTC-USDT"
echo "测试交易对: $SYMBOL"
echo ""

# 1. 查询交易对信息
echo "1. 查询交易对信息..."
echo "----------------------------------------"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
REQUEST_PATH="/api/v5/public/instruments?instType=SPOT&instId=$SYMBOL"
SIGN_STRING="$TIMESTAMP"GET"$REQUEST_PATH"
SIGNATURE=$(echo -n "$SIGN_STRING" | openssl dgst -sha256 -hmac "$OKX_SECRET_KEY" -binary | base64)

RESPONSE=$(curl -s -X GET "https://www.okx.com$REQUEST_PATH" \
  -H "OK-ACCESS-KEY: $OKX_API_KEY" \
  -H "OK-ACCESS-SIGN: $SIGNATURE" \
  -H "OK-ACCESS-TIMESTAMP: $TIMESTAMP" \
  -H "OK-ACCESS-PASSPHRASE: $OKX_PASSPHRASE" \
  -H "Content-Type: application/json")

echo "响应: $RESPONSE"
echo ""

# 提取最小下单数量
MIN_SIZE=$(echo "$RESPONSE" | grep -oP '"minSz":"[^"]*"' | head -1 | cut -d'"' -f4)
LOT_SIZE=$(echo "$RESPONSE" | grep -oP '"lotSz":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "最小下单数量 (minSz): $MIN_SIZE"
echo "下单数量精度 (lotSz): $LOT_SIZE"
echo ""

# 2. 查询当前价格
echo "2. 查询当前价格..."
echo "----------------------------------------"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
REQUEST_PATH="/api/v5/market/ticker?instId=$SYMBOL"
SIGN_STRING="$TIMESTAMP"GET"$REQUEST_PATH"
SIGNATURE=$(echo -n "$SIGN_STRING" | openssl dgst -sha256 -hmac "$OKX_SECRET_KEY" -binary | base64)

RESPONSE=$(curl -s -X GET "https://www.okx.com$REQUEST_PATH" \
  -H "OK-ACCESS-KEY: $OKX_API_KEY" \
  -H "OK-ACCESS-SIGN: $SIGNATURE" \
  -H "OK-ACCESS-TIMESTAMP: $TIMESTAMP" \
  -H "OK-ACCESS-PASSPHRASE: $OKX_PASSPHRASE" \
  -H "Content-Type: application/json")

LAST_PRICE=$(echo "$RESPONSE" | grep -oP '"last":"[^"]*"' | head -1 | cut -d'"' -f4)

echo "当前价格: $LAST_PRICE USDT"
echo ""

# 计算最小下单金额
if [ -n "$MIN_SIZE" ] && [ -n "$LAST_PRICE" ]; then
    MIN_AMOUNT=$(echo "$MIN_SIZE * $LAST_PRICE" | bc -l)
    echo "最小下单金额: $MIN_AMOUNT USDT"
    echo ""
fi

# 3. 查询账户余额
echo "3. 查询账户余额..."
echo "----------------------------------------"

TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
REQUEST_PATH="/api/v5/account/balance"
SIGN_STRING="$TIMESTAMP"GET"$REQUEST_PATH"
SIGNATURE=$(echo -n "$SIGN_STRING" | openssl dgst -sha256 -hmac "$OKX_SECRET_KEY" -binary | base64)

RESPONSE=$(curl -s -X GET "https://www.okx.com$REQUEST_PATH" \
  -H "OK-ACCESS-KEY: $OKX_API_KEY" \
  -H "OK-ACCESS-SIGN: $SIGNATURE" \
  -H "OK-ACCESS-TIMESTAMP: $TIMESTAMP" \
  -H "OK-ACCESS-PASSPHRASE: $OKX_PASSPHRASE" \
  -H "Content-Type: application/json")

echo "账户余额响应: $RESPONSE"
echo ""

# 4. 测试现货杠杆买入 (小金额)
echo "4. 测试现货杠杆买入 (小金额: 0.0001 BTC)..."
echo "----------------------------------------"

TEST_SIZE="0.0001"
TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
REQUEST_PATH="/api/v5/trade/order"
BODY="{\"instId\":\"$SYMBOL\",\"tdMode\":\"cross\",\"side\":\"buy\",\"ordType\":\"market\",\"sz\":\"$TEST_SIZE\",\"tgtCcy\":\"base_ccy\"}"
SIGN_STRING="$TIMESTAMP"POST"$REQUEST_PATH$BODY"
SIGNATURE=$(echo -n "$SIGN_STRING" | openssl dgst -sha256 -hmac "$OKX_SECRET_KEY" -binary | base64)

echo "请求参数:"
echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
echo ""

RESPONSE=$(curl -s -X POST "https://www.okx.com$REQUEST_PATH" \
  -H "OK-ACCESS-KEY: $OKX_API_KEY" \
  -H "OK-ACCESS-SIGN: $SIGNATURE" \
  -H "OK-ACCESS-TIMESTAMP: $TIMESTAMP" \
  -H "OK-ACCESS-PASSPHRASE: $OKX_PASSPHRASE" \
  -H "Content-Type: application/json" \
  -d "$BODY")

echo "响应:"
echo "$RESPONSE" | jq '.' 2>/dev/null || echo "$RESPONSE"
echo ""

# 检查是否成功
CODE=$(echo "$RESPONSE" | grep -oP '"code":"[^"]*"' | head -1 | cut -d'"' -f4)
if [ "$CODE" = "0" ]; then
    echo "✓ 下单成功!"
else
    echo "✗ 下单失败"
    SMSG=$(echo "$RESPONSE" | grep -oP '"sMsg":"[^"]*"' | head -1 | cut -d'"' -f4)
    echo "错误信息: $SMSG"
fi
echo ""

# 5. 测试现货杠杆买入 (推荐金额)
if [ -n "$MIN_SIZE" ]; then
    # 使用最小数量的 2 倍
    RECOMMENDED_SIZE=$(echo "$MIN_SIZE * 2" | bc -l)
    
    echo "5. 测试现货杠杆买入 (推荐金额: $RECOMMENDED_SIZE BTC)..."
    echo "----------------------------------------"
    
    TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%S.%3NZ")
    REQUEST_PATH="/api/v5/trade/order"
    BODY="{\"instId\":\"$SYMBOL\",\"tdMode\":\"cross\",\"side\":\"buy\",\"ordType\":\"market\",\"sz\":\"$RECOMMENDED_SIZE\",\"tgtCcy\":\"base_ccy\"}"
    SIGN_STRING="$TIMESTAMP"POST"$REQUEST_PATH$BODY"
    SIGNATURE=$(echo -n "$SIGN_STRING" | openssl dgst -sha256 -hmac "$OKX_SECRET_KEY" -binary | base64)
    
    echo "请求参数:"
    echo "$BODY" | jq '.' 2>/dev/null || echo "$BODY"
    echo ""
    
    RESPONSE=$(curl -s -X POST "https://www.okx.com$REQUEST_PATH" \
      -H "OK-ACCESS-KEY: $OKX_API_KEY" \
      -H "OK-ACCESS-SIGN: $SIGNATURE" \
      -H "OK-ACCESS-TIMESTAMP: $TIMESTAMP" \
      -H "OK-ACCESS-PASSPHRASE: $OKX_PASSPHRASE" \
      -H "Content-Type: application/json" \
      -d "$BODY")
    
    echo "响应:"
    echo "$RESPONSE" | jq '.' 2>/dev/null || echo "$RESPONSE"
    echo ""
    
    # 检查是否成功
    CODE=$(echo "$RESPONSE" | grep -oP '"code":"[^"]*"' | head -1 | cut -d'"' -f4)
    if [ "$CODE" = "0" ]; then
        echo "✓ 下单成功!"
    else
        echo "✗ 下单失败"
        SMSG=$(echo "$RESPONSE" | grep -oP '"sMsg":"[^"]*"' | head -1 | cut -d'"' -f4)
        echo "错误信息: $SMSG"
    fi
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
echo ""
echo "建议:"
echo "1. 确保下单数量 >= minSz ($MIN_SIZE BTC)"
echo "2. 确保下单金额 >= 最小下单金额 (约 $MIN_AMOUNT USDT)"
echo "3. 使用 tgtCcy=base_ccy 指定按币数量下单"
echo "4. 使用 tdMode=cross 启用全仓杠杆模式"
