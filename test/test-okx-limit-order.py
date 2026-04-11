#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
OKX 测试限价单
市价单可能有特殊限制,尝试使用限价单
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

def test_limit_order(symbol, usdt_amount, current_price, td_mode="cross"):
    """测试限价单"""
    # 计算对应的币数量
    size = usdt_amount / current_price
    size_str = str(size.quantize(Decimal('0.00000001')))  # 保留8位小数
    
    # 限价单价格设置为当前价格的 1.01 倍(确保不会立即成交,可以取消)
    limit_price = (current_price * Decimal('1.01')).quantize(Decimal('0.1'))
    
    print(f"\n测试 {td_mode} 模式限价单: {usdt_amount} USDT (约 {size_str} BTC)")
    print(f"限价: {limit_price} USDT")
    print("-" * 60)
    
    body = {
        "instId": symbol,
        "tdMode": td_mode,
        "side": "buy",
        "ordType": "limit",  # 限价单
        "px": str(limit_price),  # 限价
        "sz": size_str
    }
    
    print(f"请求参数: {json.dumps(body, ensure_ascii=False)}")
    
    endpoint = '/api/v5/trade/order'
    response = api_request('POST', endpoint, body)
    
    if response['code'] == '0':
        order_id = response['data'][0].get('ordId')
        print(f"✓ 成功! 订单ID: {order_id}")
        
        # 立即取消订单(避免成交)
        print("  取消订单...")
        cancel_body = {
            "instId": symbol,
            "ordId": order_id
        }
        cancel_response = api_request('POST', '/api/v5/trade/cancel-order', cancel_body)
        if cancel_response['code'] == '0':
            print("  ✓ 订单已取消")
        else:
            print(f"  ✗ 取消失败: {cancel_response.get('msg')}")
        
        return True, response
    else:
        s_code = response['data'][0].get('sCode') if response['data'] else 'N/A'
        s_msg = response['data'][0].get('sMsg') if response['data'] else response.get('msg')
        print(f"✗ 失败 [{s_code}]: {s_msg}")
        return False, response

def main():
    """主函数"""
    print("=" * 70)
    print("OKX 测试限价单")
    print("=" * 70)
    
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
        Decimal('1'),
        Decimal('5'),
        Decimal('10'),
    ]
    
    print("\n" + "=" * 70)
    print("开始测试限价单...")
    print("=" * 70)
    
    success_amount = None
    for amount in test_amounts:
        success, response = test_limit_order(symbol, amount, current_price, "cross")
        if success:
            success_amount = amount
            break
    
    print("\n" + "=" * 70)
    print("测试结果")
    print("=" * 70)
    
    if success_amount:
        print(f"\n✓ 最小成功下单金额: {success_amount} USDT")
        print(f"\n建议:")
        print(f"  1. 限价单可以成功,市价单可能有额外限制")
        print(f"  2. 考虑在代码中使用限价单代替市价单")
        print(f"  3. 或者检查市价单的特殊要求")
    else:
        print("\n✗ 所有测试均失败")
        print("  这可能是 API 权限问题")
        print("  请检查 API Key 是否有交易权限")
    
    print()

if __name__ == '__main__':
    main()
