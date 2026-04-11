#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
OKX 最小下单金额测试
测试不同金额的下单,找出最小下单金额要求
"""

import os
import sys
import hmac
import base64
import hashlib
import json
import requests
from datetime import datetime
from decimal import Decimal

# API 配置
API_KEY = os.getenv('OKX_API_KEY')
SECRET_KEY = os.getenv('OKX_SECRET_KEY')
PASSPHRASE = os.getenv('OKX_PASSPHRASE')
BASE_URL = 'https://www.okx.com'

def generate_signature(timestamp, method, request_path, body=''):
    """生成签名"""
    message = timestamp + method + request_path + body
    mac = hmac.new(
        SECRET_KEY.encode('utf-8'),
        message.encode('utf-8'),
        hashlib.sha256
    )
    return base64.b64encode(mac.digest()).decode('utf-8')

def get_timestamp():
    """获取 ISO 格式时间戳"""
    return datetime.utcnow().strftime('%Y-%m-%dT%H:%M:%S.%f')[:-3] + 'Z'

def api_request(method, endpoint, body=None):
    """发送 API 请求"""
    timestamp = get_timestamp()
    request_path = endpoint
    body_str = json.dumps(body) if body else ''
    
    signature = generate_signature(timestamp, method, request_path, body_str)
    
    headers = {
        'OK-ACCESS-KEY': API_KEY,
        'OK-ACCESS-SIGN': signature,
        'OK-ACCESS-TIMESTAMP': timestamp,
        'OK-ACCESS-PASSPHRASE': PASSPHRASE,
        'Content-Type': 'application/json'
    }
    
    url = BASE_URL + request_path
    
    if method == 'GET':
        response = requests.get(url, headers=headers)
    elif method == 'POST':
        response = requests.post(url, headers=headers, data=body_str)
    else:
        raise ValueError(f"不支持的方法: {method}")
    
    return response.json()

def get_current_price(symbol):
    """获取当前价格"""
    endpoint = f'/api/v5/market/ticker?instId={symbol}'
    response = api_request('GET', endpoint)
    
    if response['code'] == '0' and response['data']:
        return Decimal(response['data'][0]['last'])
    return None

def test_order_with_amount(symbol, usdt_amount, current_price):
    """测试指定 USDT 金额的下单"""
    # 计算对应的币数量
    size = usdt_amount / current_price
    size_str = str(size.quantize(Decimal('0.00000001')))  # 保留8位小数
    
    print(f"\n测试下单金额: {usdt_amount} USDT (约 {size_str} BTC)")
    print("-" * 50)
    
    body = {
        "instId": symbol,
        "tdMode": "cross",
        "side": "buy",
        "ordType": "market",
        "sz": size_str
    }
    
    endpoint = '/api/v5/trade/order'
    response = api_request('POST', endpoint, body)
    
    if response['code'] == '0':
        print(f"✓ 成功! 订单ID: {response['data'][0].get('ordId')}")
        return True
    else:
        s_code = response['data'][0].get('sCode') if response['data'] else 'N/A'
        s_msg = response['data'][0].get('sMsg') if response['data'] else response.get('msg')
        print(f"✗ 失败 [{s_code}]: {s_msg}")
        return False

def main():
    """主函数"""
    print("=" * 60)
    print("OKX 最小下单金额测试")
    print("=" * 60)
    
    symbol = "BTC-USDT"
    
    # 获取当前价格
    print(f"\n查询 {symbol} 当前价格...")
    current_price = get_current_price(symbol)
    if not current_price:
        print("❌ 无法获取当前价格")
        return
    
    print(f"当前价格: {current_price} USDT")
    
    # 测试不同金额
    test_amounts = [
        Decimal('0.5'),   # 0.5 USDT
        Decimal('1'),     # 1 USDT
        Decimal('2'),     # 2 USDT
        Decimal('5'),     # 5 USDT
        Decimal('10'),    # 10 USDT
        Decimal('15'),    # 15 USDT
        Decimal('20'),    # 20 USDT
    ]
    
    print("\n" + "=" * 60)
    print("开始测试不同金额...")
    print("=" * 60)
    
    success_amount = None
    for amount in test_amounts:
        if test_order_with_amount(symbol, amount, current_price):
            success_amount = amount
            break
    
    print("\n" + "=" * 60)
    print("测试结果")
    print("=" * 60)
    
    if success_amount:
        print(f"\n✓ 最小成功下单金额: {success_amount} USDT")
        print(f"  建议在代码中使用 >= {success_amount} USDT 的金额下单")
    else:
        print("\n✗ 所有测试金额均失败")
        print("  可能需要更大的金额或检查账户配置")
    
    print()

if __name__ == '__main__':
    main()
