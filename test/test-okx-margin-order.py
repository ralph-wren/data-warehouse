#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
OKX 现货杠杆下单测试脚本
用于测试现货杠杆模式下单,诊断最小下单金额问题
"""

import os
import sys
import time
import hmac
import base64
import hashlib
import json
import requests
from datetime import datetime

# API 配置
API_KEY = os.getenv('OKX_API_KEY')
SECRET_KEY = os.getenv('OKX_SECRET_KEY')
PASSPHRASE = os.getenv('OKX_PASSPHRASE')
BASE_URL = 'https://www.okx.com'

def check_env():
    """检查环境变量"""
    if not all([API_KEY, SECRET_KEY, PASSPHRASE]):
        print("❌ 错误: 请设置 OKX API 环境变量")
        print("   OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE")
        sys.exit(1)
    print("✓ API 配置已加载\n")

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

def test_instrument_info(symbol):
    """查询交易对信息"""
    print("1. 查询交易对信息...")
    print("-" * 40)
    
    endpoint = f'/api/v5/public/instruments?instType=SPOT&instId={symbol}'
    response = api_request('GET', endpoint)
    
    print(f"响应: {json.dumps(response, indent=2, ensure_ascii=False)}\n")
    
    if response['code'] == '0' and response['data']:
        data = response['data'][0]
        min_size = float(data['minSz'])
        lot_size = float(data['lotSz'])
        
        print(f"最小下单数量 (minSz): {min_size}")
        print(f"下单数量精度 (lotSz): {lot_size}\n")
        
        return min_size, lot_size
    else:
        print(f"❌ 查询失败: {response.get('msg', 'Unknown error')}\n")
        return None, None

def test_ticker(symbol):
    """查询当前价格"""
    print("2. 查询当前价格...")
    print("-" * 40)
    
    endpoint = f'/api/v5/market/ticker?instId={symbol}'
    response = api_request('GET', endpoint)
    
    if response['code'] == '0' and response['data']:
        last_price = float(response['data'][0]['last'])
        print(f"当前价格: {last_price} USDT\n")
        return last_price
    else:
        print(f"❌ 查询失败: {response.get('msg', 'Unknown error')}\n")
        return None

def test_account_balance():
    """查询账户余额"""
    print("3. 查询账户余额...")
    print("-" * 40)
    
    endpoint = '/api/v5/account/balance'
    response = api_request('GET', endpoint)
    
    print(f"账户余额响应: {json.dumps(response, indent=2, ensure_ascii=False)}\n")
    
    return response

def test_margin_order(symbol, size, description):
    """测试现货杠杆下单"""
    print(f"测试现货杠杆买入 ({description}: {size} BTC)...")
    print("-" * 40)
    
    body = {
        "instId": symbol,
        "tdMode": "cross",  # 全仓杠杆模式
        "side": "buy",
        "ordType": "market",
        "sz": str(size),
        "tgtCcy": "base_ccy"  # 按币数量下单
    }
    
    print(f"请求参数:")
    print(json.dumps(body, indent=2, ensure_ascii=False))
    print()
    
    endpoint = '/api/v5/trade/order'
    response = api_request('POST', endpoint, body)
    
    print(f"响应:")
    print(json.dumps(response, indent=2, ensure_ascii=False))
    print()
    
    if response['code'] == '0':
        print("✓ 下单成功!")
        if response['data']:
            order_id = response['data'][0].get('ordId')
            print(f"订单ID: {order_id}")
    else:
        print("✗ 下单失败")
        if response['data'] and len(response['data']) > 0:
            s_code = response['data'][0].get('sCode')
            s_msg = response['data'][0].get('sMsg')
            print(f"错误代码: {s_code}")
            print(f"错误信息: {s_msg}")
    
    print()
    return response

def main():
    """主函数"""
    print("=" * 50)
    print("OKX 现货杠杆下单测试")
    print("=" * 50)
    print()
    
    # 检查环境变量
    check_env()
    
    # 测试交易对
    symbol = "BTC-USDT"
    print(f"测试交易对: {symbol}\n")
    
    # 1. 查询交易对信息
    min_size, lot_size = test_instrument_info(symbol)
    
    # 2. 查询当前价格
    last_price = test_ticker(symbol)
    
    # 计算最小下单金额
    if min_size and last_price:
        min_amount = min_size * last_price
        print(f"最小下单金额: {min_amount:.2f} USDT\n")
    
    # 3. 查询账户余额
    test_account_balance()
    
    # 4. 测试小金额下单
    print("4. " + "=" * 40)
    test_margin_order(symbol, 0.0001, "小金额")
    
    # 5. 测试推荐金额下单
    if min_size:
        recommended_size = min_size * 2
        print("5. " + "=" * 40)
        test_margin_order(symbol, recommended_size, f"推荐金额")
    
    # 总结
    print("=" * 50)
    print("测试完成")
    print("=" * 50)
    print()
    print("建议:")
    if min_size and last_price:
        print(f"1. 确保下单数量 >= minSz ({min_size} BTC)")
        print(f"2. 确保下单金额 >= 最小下单金额 (约 {min_amount:.2f} USDT)")
    print("3. 使用 tgtCcy=base_ccy 指定按币数量下单")
    print("4. 使用 tdMode=cross 启用全仓杠杆模式")
    print()

if __name__ == '__main__':
    main()
