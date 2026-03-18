#!/bin/bash

# 查询 Doris 数据的脚本

echo "=========================================="
echo "Querying Doris Data Warehouse"
echo "=========================================="
echo ""

# 查询 ODS 层数据
echo "1. ODS Layer - Latest 10 records:"
echo "-----------------------------------"
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT 
    inst_id,
    FROM_UNIXTIME(timestamp/1000) as time,
    last_price,
    bid_price,
    ask_price,
    volume_24h,
    high_24h,
    low_24h
FROM ods_crypto_ticker_rt 
ORDER BY timestamp DESC 
LIMIT 10;
"
echo ""

# 查询 ODS 层统计
echo "2. ODS Layer - Statistics:"
echo "-----------------------------------"
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT 
    inst_id,
    COUNT(*) as record_count,
    MIN(FROM_UNIXTIME(timestamp/1000)) as first_time,
    MAX(FROM_UNIXTIME(timestamp/1000)) as last_time
FROM ods_crypto_ticker_rt 
GROUP BY inst_id;
"
echo ""

# 查询 DWD 层数据
echo "3. DWD Layer - Latest 10 records:"
echo "-----------------------------------"
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT 
    inst_id,
    FROM_UNIXTIME(timestamp/1000) as time,
    last_price,
    spread,
    spread_rate,
    price_change_24h,
    price_change_rate_24h,
    amplitude_24h
FROM dwd_crypto_ticker_detail 
ORDER BY timestamp DESC 
LIMIT 10;
"
echo ""

# 查询 DWD 层统计
echo "4. DWD Layer - Statistics:"
echo "-----------------------------------"
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT 
    inst_id,
    COUNT(*) as record_count,
    MIN(FROM_UNIXTIME(timestamp/1000)) as first_time,
    MAX(FROM_UNIXTIME(timestamp/1000)) as last_time
FROM dwd_crypto_ticker_detail 
GROUP BY inst_id;
"
echo ""

# 查询 DWS 层 1分钟 K 线数据
echo "5. DWS Layer - 1 Minute K-Line (Latest 10):"
echo "-----------------------------------"
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT 
    inst_id,
    FROM_UNIXTIME(window_start/1000) as window_time,
    open_price,
    high_price,
    low_price,
    close_price,
    price_change,
    price_change_rate,
    tick_count
FROM dws_crypto_ticker_1min 
ORDER BY window_start DESC 
LIMIT 10;
"
echo ""

# 查询 DWS 层统计
echo "6. DWS Layer - Statistics:"
echo "-----------------------------------"
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT 
    inst_id,
    COUNT(*) as window_count,
    MIN(FROM_UNIXTIME(window_start/1000)) as first_window,
    MAX(FROM_UNIXTIME(window_start/1000)) as last_window
FROM dws_crypto_ticker_1min 
GROUP BY inst_id;
"
echo ""

echo "=========================================="
echo "Query completed"
echo "=========================================="
