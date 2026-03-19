#!/bin/bash

# 测试 Checkpoint 卡住问题
# 检查 Doris BE 连接和 Stream Load 功能

echo "=========================================="
echo "测试 Checkpoint 卡住问题"
echo "=========================================="
echo

# 1. 检查 Doris BE 端口
echo "1. 检查 Doris BE 端口..."
if curl -s http://127.0.0.1:8040/api/health > /dev/null 2>&1; then
    echo "✓ BE 端口 8040 可访问"
else
    echo "✗ BE 端口 8040 不可访问"
    exit 1
fi
echo

# 2. 检查 BE 注册信息
echo "2. 检查 BE 注册信息..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" | grep -E "(Host|HeartbeatPort|BePort|HttpPort|Alive)"
echo

# 3. 测试简单的 Stream Load
echo "3. 测试简单的 Stream Load..."
cat > /tmp/test_streamload.json << 'EOF'
{"inst_id":"BTC-USDT","timestamp":1710892800000,"trade_date":"2024-03-20","trade_hour":0,"last_price":70000.12345678,"bid_price":69999.12345678,"ask_price":70001.12345678,"spread":2.00000000,"spread_rate":0.000029,"volume_24h":1234567890.12345678,"high_24h":71000.12345678,"low_24h":69000.12345678,"open_24h":69500.12345678,"price_change_24h":500.00000000,"price_change_rate_24h":0.007194,"amplitude_24h":0.028986,"data_source":"OKX","ingest_time":1710892800000,"process_time":1710892800000}
EOF

LABEL="test_checkpoint_$(date +%s)"
curl -v -X PUT \
    -H "label:$LABEL" \
    -H "format:json" \
    -H "read_json_by_line:true" \
    -H "strict_mode:false" \
    -H "max_filter_ratio:0.1" \
    -u root: \
    -T /tmp/test_streamload.json \
    http://127.0.0.1:8040/api/crypto_dw/dwd_crypto_ticker_detail/_stream_load 2>&1 | grep -E "(HTTP|Status|Message)"
echo

# 4. 检查 DWD 表数据量
echo "4. 检查 DWD 表当前数据量..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT COUNT(*) as total_rows FROM crypto_dw.dwd_crypto_ticker_detail"
echo

# 5. 检查最近的 Stream Load 任务
echo "5. 检查最近的 Stream Load 任务..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW LOAD FROM crypto_dw WHERE Label LIKE '%dwd%' ORDER BY CreateTime DESC LIMIT 5\G" | grep -E "(Label|State|ErrorMsg|LoadBytes|LoadRows)"
echo

echo "=========================================="
echo "测试完成"
echo "=========================================="
