#!/bin/bash

# 直接测试 Doris Stream Load
# 用于诊断连接重置问题

echo "=========================================="
echo "Doris Stream Load 直接测试"
echo "=========================================="
echo ""

# Doris 连接信息
DORIS_HOST="127.0.0.1"
DORIS_PORT="8030"
DORIS_USER="root"
DORIS_PASSWORD=""
DORIS_DB="crypto_dw"
DORIS_TABLE="ods_crypto_ticker_rt"

# 构建测试数据 (单条记录)
TEST_DATA='{"inst_id":"BTC-USDT","timestamp":1773840422738,"last_price":72499.6,"bid_price":72499.5,"ask_price":72499.6,"bid_size":0.3920086,"ask_size":0.66129571,"volume_24h":6063.20550026,"high_24h":74883,"low_24h":72122.1,"open_24h":73703.8,"data_source":"OKX","ingest_time":1773848406623}'

echo "测试 1: 单条记录"
echo "数据: $TEST_DATA"
echo ""

# 测试 1: 使用 PUT 方法
echo "方法 1: PUT 请求 (Java 代码使用的方法)"
curl -v -X PUT \
  -u "$DORIS_USER:$DORIS_PASSWORD" \
  -H "Expect:" \
  -H "Connection: close" \
  -H "format: json" \
  -H "read_json_by_line: true" \
  -H "strip_outer_array: false" \
  -H "max_filter_ratio: 0.1" \
  -d "$TEST_DATA" \
  "http://$DORIS_HOST:$DORIS_PORT/api/$DORIS_DB/$DORIS_TABLE/_stream_load"

echo ""
echo "=========================================="
echo ""

# 测试 2: 使用标准的 curl -T 方法
echo "方法 2: curl -T 方法 (标准方法)"
echo "$TEST_DATA" | curl -v \
  -u "$DORIS_USER:$DORIS_PASSWORD" \
  -H "format: json" \
  -H "read_json_by_line: true" \
  -H "strip_outer_array: false" \
  -H "max_filter_ratio: 0.1" \
  -T - \
  "http://$DORIS_HOST:$DORIS_PORT/api/$DORIS_DB/$DORIS_TABLE/_stream_load"

echo ""
echo "=========================================="
echo ""

# 测试 3: 批量数据 (3条记录)
echo "测试 2: 批量数据 (3条记录)"
BATCH_DATA='{"inst_id":"BTC-USDT","timestamp":1773840422738,"last_price":72499.6,"bid_price":72499.5,"ask_price":72499.6,"bid_size":0.3920086,"ask_size":0.66129571,"volume_24h":6063.20550026,"high_24h":74883,"low_24h":72122.1,"open_24h":73703.8,"data_source":"OKX","ingest_time":1773848406623}
{"inst_id":"BTC-USDT","timestamp":1773840422972,"last_price":72499.6,"bid_price":72499.5,"ask_price":72499.6,"bid_size":0.3920086,"ask_size":0.7715062,"volume_24h":6063.20550026,"high_24h":74883,"low_24h":72122.1,"open_24h":73703.8,"data_source":"OKX","ingest_time":1773848406623}
{"inst_id":"BTC-USDT","timestamp":1773840423082,"last_price":72499.6,"bid_price":72499.5,"ask_price":72499.6,"bid_size":0.39205044,"ask_size":0.66111673,"volume_24h":6063.20550026,"high_24h":74883,"low_24h":72122.1,"open_24h":73703.8,"data_source":"OKX","ingest_time":1773848406624}'

echo "数据大小: $(echo "$BATCH_DATA" | wc -c) bytes"
echo ""

echo "$BATCH_DATA" | curl -v \
  -u "$DORIS_USER:$DORIS_PASSWORD" \
  -H "format: json" \
  -H "read_json_by_line: true" \
  -H "strip_outer_array: false" \
  -H "max_filter_ratio: 0.1" \
  -T - \
  "http://$DORIS_HOST:$DORIS_PORT/api/$DORIS_DB/$DORIS_TABLE/_stream_load"

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
