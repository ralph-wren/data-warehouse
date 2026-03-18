#!/bin/bash

# 测试 Doris Stream Load 功能
# 用于验证自定义 Sink 是否能正常工作

echo "=========================================="
echo "测试 Doris Stream Load 连接"
echo "=========================================="
echo ""

# 从环境变量或配置文件读取 Doris 连接信息
DORIS_FE_HOST=${DORIS_FE_HOST:-"localhost"}
DORIS_FE_PORT=${DORIS_FE_PORT:-"8030"}
DORIS_USER=${DORIS_USER:-"root"}
DORIS_PASSWORD=${DORIS_PASSWORD:-""}
DORIS_DB=${DORIS_DB:-"crypto_dw"}
DORIS_TABLE=${DORIS_TABLE:-"ods_crypto_ticker_rt"}

echo "Doris 连接信息:"
echo "  FE Host: $DORIS_FE_HOST"
echo "  FE Port: $DORIS_FE_PORT"
echo "  Database: $DORIS_DB"
echo "  Table: $DORIS_TABLE"
echo ""

# 构建测试数据
TEST_DATA='{"inst_id":"BTC-USDT","timestamp":1710777600000,"last_price":65000.12,"bid_price":64999.50,"ask_price":65000.50,"bid_size":1.5,"ask_size":2.0,"volume_24h":12345.67,"high_24h":66000.00,"low_24h":64000.00,"open_24h":65500.00,"data_source":"OKX","ingest_time":1710777600000}'

echo "测试数据:"
echo "$TEST_DATA"
echo ""

# 发送 Stream Load 请求
echo "发送 Stream Load 请求..."
RESPONSE=$(curl -s -w "\nHTTP_CODE:%{http_code}" \
  -u "$DORIS_USER:$DORIS_PASSWORD" \
  -H "format: json" \
  -H "read_json_by_line: true" \
  -H "strip_outer_array: false" \
  -T - \
  "http://$DORIS_FE_HOST:$DORIS_FE_PORT/api/$DORIS_DB/$DORIS_TABLE/_stream_load" \
  <<< "$TEST_DATA")

# 提取 HTTP 状态码
HTTP_CODE=$(echo "$RESPONSE" | grep "HTTP_CODE:" | cut -d: -f2)
RESPONSE_BODY=$(echo "$RESPONSE" | grep -v "HTTP_CODE:")

echo ""
echo "响应状态码: $HTTP_CODE"
echo "响应内容:"
echo "$RESPONSE_BODY" | python -m json.tool 2>/dev/null || echo "$RESPONSE_BODY"
echo ""

# 检查结果
if [ "$HTTP_CODE" = "200" ]; then
    echo "✓ Stream Load 测试成功!"
    
    # 检查导入状态
    STATUS=$(echo "$RESPONSE_BODY" | grep -o '"Status":"[^"]*"' | cut -d'"' -f4)
    if [ "$STATUS" = "Success" ]; then
        echo "✓ 数据导入成功!"
    else
        echo "✗ 数据导入失败: $STATUS"
        exit 1
    fi
else
    echo "✗ Stream Load 测试失败!"
    echo "HTTP 状态码: $HTTP_CODE"
    exit 1
fi

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
