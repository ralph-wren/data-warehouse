#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
OKX 测试更大金额下单
根据官方文档,现货最小下单金额可能是 5-10 USDT
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

def test_order(symbol, usdt_amount, current_price, td_mode="cross"):
    """测试下单"""
    # 计算对应的币数量
    size = usdt_amount / current_price
    size_str = str(size.quantize(Decimal('0.00000001')))  # 保留8位小数
    
    print(f"\n测试 {td_mode} 模式下单: {usdt_amount} USDT (约 {size_str} BTC)")
    print("-" * 60)
    
    body = {
        "instId": symbol,
        "tdMode": td_mode,
        "side": "buy",
        "ordType": "market",
        "sz": size_str
    }
    
    print(f"请求参数: {json.dumps(body, ensure_ascii=False)}")
    
    endpoint = '/api/v5/trade/order'
    response = api_request('POST', endpoint, body)
    
    if response['code'] == '0':
        print(f"✓ 成功! 订单ID: {response['data'][0].get('ordId')}")
        return True, response
    else:
        s_code = response['data'][0].get('sCode') if response['data'] else 'N/A'
        s_msg = response['data'][0].get('sMsg') if response['data'] else response.get('msg')
        print(f"✗ 失败 [{s_code}]: {s_msg}")
        return False, response

def main():
    """主函数"""
    print("=" * 70)
    print("OKX 测试更大金额下单")
    print("=" * 70)
    
    symbol = "BTC-USDT"
    
    # 获取当前价格
    print(f"\n查询 {symbol} 当前价格...")
    current_price = get_current_price(symbol)
    if not current_price:
        print("❌ 无法获取当前价格")
        return
    
    print(f"当前价格: {current_price} USDT")
    
    # 测试不同金额和模式
    test_cases = [
        # (金额, 交易模式)
        (Decimal('5'), "cash"),    # 5 USDT 普通现货
        (Decimal('10'), "cash"),   # 10 USDT 普通现货
        (Decimal('5'), "cross"),   # 5 USDT 杠杆
        (Decimal('10'), "cross"),  # 10 USDT 杠杆
        (Decimal('20'), "cross"),  # 20 USDT 杠杆
        (Decimal('50'), "cross"),  # 50 USDT 杠杆
    ]
    
    print("\n" + "=" * 70)
    print("开始测试...")
    print("=" * 70)
    
    success_cases = []
    for amount, mode in test_cases:
        success, response = test_order(symbol, amount, current_price, mode)
        if success:
            success_cases.append((amount, mode))
            break  # 找到成功的就停止
    
    print("\n" + "=" * 70)
    print("测试结果")
    print("=" * 70)
    
    if success_cases:
        for amount, mode in success_cases:
            print(f"\n✓ 成功下单: {amount} USDT ({mode} 模式)")
        print(f"\n建议:")
        print(f"  1. 最小下单金额: >= {success_cases[0][0]} USDT")
        print(f"  2. 在代码中确保下单金额满足此要求")
        print(f"  3. 使用 tdMode={success_cases[0][1]} 模式")
    else:
        print("\n✗ 所有测试均失败")
        print("  建议:")
        print("  1. 检查账户余额是否充足")
        print("  2. 检查是否需要在网站上开通相关权限")
        print("  3. 联系 OKX 客服确认最小下单金额要求")
    
    print()

if __name__ == '__main__':
    main()
