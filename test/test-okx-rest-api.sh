#!/bin/bash

# 测试 OKX REST API 连接
# 验证添加请求头后是否能成功访问

echo "=========================================="
echo "测试 OKX REST API 连接"
echo "=========================================="

# 测试现货 Ticker API
echo ""
echo "1. 测试现货 Ticker API..."
curl -s -w "\nHTTP状态码: %{http_code}\n" \
  -H "Content-Type: application/json" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  -H "Accept: application/json" \
  -H "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8" \
  -H "Accept-Encoding: gzip, deflate, br" \
  -H "Connection: keep-alive" \
  "https://www.okx.com/api/v5/market/tickers?instType=SPOT" | head -20

echo ""
echo "2. 测试合约 Ticker API..."
curl -s -w "\nHTTP状态码: %{http_code}\n" \
  -H "Content-Type: application/json" \
  -H "User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36" \
  -H "Accept: application/json" \
  -H "Accept-Language: zh-CN,zh;q=0.9,en;q=0.8" \
  -H "Accept-Encoding: gzip, deflate, br" \
  -H "Connection: keep-alive" \
  "https://www.okx.com/api/v5/market/tickers?instType=SWAP" | head -20

echo ""
echo "=========================================="
echo "测试完成"
echo "=========================================="
