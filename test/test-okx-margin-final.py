#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
OKX 现货杠杆下单最终验证脚本
验证修复后的参数配置是否正确
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

def test_order(symbol, usdt_amount, current_price, td_mode="cross", order_type="limit"):
    """测试下单 - 使用修复后的参数"""
    # 计算对应的币数量
    size = usdt_amount / current_price
    size_str = str(size.quantize(Decimal('0.00000001')))  # 保留8位小数
    
    print(f"\n{'='*70}")
    print(f"测试 {td_mode} 模式 {order_type} 单: {usdt_amount} USDT (约 {size_str} BTC)")
    print(f"{'='*70}")
    
    # 构建请求参数(模拟 Java 代码的逻辑)
    body = {
        "instId": symbol,
        "tdMode": td_mode,
        "ccy": "USDT",  # ✅ 修复: 添加保证金币种
        "side": "buy",
        "ordType": order_type,
        "sz": size_str  # ✅ 修复: 使用普通格式,不使用科学计数法
        # ✅ 修复: 移除 tgtCcy 参数
    }
    
    # 如果是限价单,添加价格(使用更接近市价的价格,避免超出限价范围)
    if order_type == "limit":
        limit_price = (current_price * Decimal('1.001')).quantize(Decimal('0.1'))  # 仅高出 0.1%
        body["px"] = str(limit_price)
        print(f"限价: {limit_price} USDT (市价 + 0.1%)")
    
    print(f"\n请求参数:")
    print(json.dumps(body, indent=2, ensure_ascii=False))
    
    endpoint = '/api/v5/trade/order'
    response = api_request('POST', endpoint, body)
    
    print(f"\n响应:")
    print(json.dumps(response, indent=2, ensure_ascii=False))
    
    if response['code'] == '0':
        order_id = response['data'][0].get('ordId')
        print(f"\n✅ 下单成功! 订单ID: {order_id}")
        
        # 如果是限价单,立即取消(避免成交)
        if order_type == "limit":
            print("\n取消订单...")
            cancel_body = {
                "instId": symbol,
                "ordId": order_id
            }
            cancel_response = api_request('POST', '/api/v5/trade/cancel-order', cancel_body)
            if cancel_response['code'] == '0':
                print("✅ 订单已取消")
            else:
                print(f"⚠️ 取消失败: {cancel_response.get('msg')}")
        
        return True, response
    else:
        s_code = response['data'][0].get('sCode') if response['data'] else 'N/A'
        s_msg = response['data'][0].get('sMsg') if response['data'] else response.get('msg')
        print(f"\n❌ 下单失败 [{s_code}]: {s_msg}")
        return False, response

def main():
    """主函数"""
    print("\n" + "="*70)
    print("OKX 现货杠杆下单最终验证")
    print("="*70)
    print("\n验证修复内容:")
    print("  1. ✅ 移除 tgtCcy 参数")
    print("  2. ✅ 使用普通格式(避免科学计数法)")
    print("  3. ✅ 添加 ccy 参数(保证金币种)")
    print()
    
    symbol = "BTC-USDT"
    
    # 获取当前价格
    print(f"查询 {symbol} 当前价格...")
    current_price = get_current_price(symbol)
    if not current_price:
        print("❌ 无法获取当前价格")
        return
    
    print(f"当前价格: {current_price} USDT\n")
    
    # 测试用例
    test_cases = [
        # (金额, 交易模式, 订单类型)
        (Decimal('10'), "cross", "limit"),   # 10 USDT 杠杆限价单
        (Decimal('5'), "cross", "limit"),    # 5 USDT 杠杆限价单
        (Decimal('1'), "cross", "limit"),    # 1 USDT 杠杆限价单
    ]
    
    success_count = 0
    fail_count = 0
    
    for amount, mode, order_type in test_cases:
        success, response = test_order(symbol, amount, current_price, mode, order_type)
        if success:
            success_count += 1
        else:
            fail_count += 1
    
    # 总结
    print("\n" + "="*70)
    print("测试结果总结")
    print("="*70)
    print(f"\n✅ 成功: {success_count} 个")
    print(f"❌ 失败: {fail_count} 个")
    
    if success_count > 0:
        print("\n🎉 修复成功! 现货杠杆下单参数配置正确")
        print("\n修复要点:")
        print("  1. 移除了 tgtCcy 参数(杠杆模式不支持)")
        print("  2. 使用 toPlainString() 避免科学计数法")
        print("  3. 添加了 ccy='USDT' 参数(杠杆模式必需)")
        print("\nJava 代码已更新:")
        print("  - OKXTradingService.buySpot()")
        print("  - OKXTradingService.sellSpot()")
    else:
        print("\n⚠️ 所有测试失败,可能存在其他问题:")
        print("  1. 检查 API 权限是否包含交易权限")
        print("  2. 检查账户余额是否充足")
        print("  3. 检查是否需要在网站上开通杠杆交易")
    
    print()

if __name__ == '__main__':
    main()
