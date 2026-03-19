#!/bin/bash

# 测试 DWD 表写入
# 用于验证数据质量问题

echo "=========================================="
echo "测试 DWD 表写入"
echo "=========================================="
echo

# 1. 检查分区
echo "1. 检查分区..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW PARTITIONS FROM crypto_dw.dwd_crypto_ticker_detail;" | grep "p202603"

echo
echo "2. 检查表属性..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW CREATE TABLE crypto_dw.dwd_crypto_ticker_detail\G" | grep -E "strict_mode|max_filter_ratio"

echo
echo "3. 测试插入一条数据..."
mysql -h 127.0.0.1 -P 9030 -u root <<EOF
INSERT INTO crypto_dw.dwd_crypto_ticker_detail VALUES (
    'BTC-USDT',
    1773934800000,
    '2026-03-19',
    23,
    69250.12345678,
    69249.12345678,
    69251.12345678,
    2.00000000,
    0.000029,
    1000000.12345678,
    70000.12345678,
    68000.12345678,
    69000.12345678,
    250.12345678,
    0.003625,
    0.029412,
    'OKX',
    1773934800000,
    1773934800000
);
EOF

if [ $? -eq 0 ]; then
    echo "✓ 插入成功"
else
    echo "✗ 插入失败"
fi

echo
echo "4. 查询刚插入的数据..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT * FROM crypto_dw.dwd_crypto_ticker_detail WHERE timestamp = 1773934800000;"

echo
echo "=========================================="
echo "测试完成"
echo "=========================================="
