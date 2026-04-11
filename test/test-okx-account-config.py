#!/usr/bin/env python3
# -*- coding: utf-8 -*-

"""
OKX 账户配置检查
检查账户是否开通杠杆交易权限
"""

import os
import sys
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

def check_account_config():
    """检查账户配置"""
    print("=" * 60)
    print("检查账户配置")
    print("=" * 60)
    
    endpoint = '/api/v5/account/config'
    response = api_request('GET', endpoint)
    
    print("\n账户配置:")
    print(json.dumps(response, indent=2, ensure_ascii=False))
    
    if response['code'] == '0' and response['data']:
        data = response['data'][0]
        print("\n关键配置:")
        print(f"  账户等级 (acctLv): {data.get('acctLv')}")
        print(f"  持仓模式 (posMode): {data.get('posMode')}")
        print(f"  自动借币 (autoLoan): {data.get('autoLoan')}")
        print(f"  账户模式 (mainUid): {data.get('mainUid')}")
        
        # 检查是否支持杠杆
        acct_lv = data.get('acctLv')
        if acct_lv == '1':
            print("\n✓ 账户等级: 简单交易模式 (不支持杠杆)")
            print("  需要升级到统一账户模式才能使用杠杆")
        elif acct_lv == '2':
            print("\n✓ 账户等级: 单币种保证金模式")
        elif acct_lv == '3':
            print("\n✓ 账户等级: 跨币种保证金模式")
        elif acct_lv == '4':
            print("\n✓ 账户等级: 组合保证金模式")
        
        return data
    else:
        print(f"\n❌ 查询失败: {response.get('msg')}")
        return None

def check_leverage_info(symbol):
    """检查杠杆信息"""
    print("\n" + "=" * 60)
    print(f"检查 {symbol} 杠杆信息")
    print("=" * 60)
    
    endpoint = f'/api/v5/account/leverage-info?instId={symbol}&mgnMode=cross'
    response = api_request('GET', endpoint)
    
    print("\n杠杆信息:")
    print(json.dumps(response, indent=2, ensure_ascii=False))
    
    return response

def test_simple_spot_order(symbol, size):
    """测试普通现货下单(不使用杠杆)"""
    print("\n" + "=" * 60)
    print("测试普通现货下单(tdMode=cash)")
    print("=" * 60)
    
    body = {
        "instId": symbol,
        "tdMode": "cash",  # 非保证金模式(普通现货)
        "side": "buy",
        "ordType": "market",
        "sz": str(size)
    }
    
    print(f"\n请求参数:")
    print(json.dumps(body, indent=2, ensure_ascii=False))
    
    endpoint = '/api/v5/trade/order'
    response = api_request('POST', endpoint, body)
    
    print(f"\n响应:")
    print(json.dumps(response, indent=2, ensure_ascii=False))
    
    if response['code'] == '0':
        print("\n✓ 普通现货下单成功!")
        return True
    else:
        print("\n✗ 普通现货下单失败")
        return False

def main():
    """主函数"""
    print("\n" + "=" * 60)
    print("OKX 账户配置检查")
    print("=" * 60 + "\n")
    
    # 1. 检查账户配置
    config = check_account_config()
    
    # 2. 检查杠杆信息
    check_leverage_info("BTC-USDT")
    
    # 3. 测试普通现货下单
    test_simple_spot_order("BTC-USDT", "0.0001")
    
    print("\n" + "=" * 60)
    print("检查完成")
    print("=" * 60)
    
    if config:
        acct_lv = config.get('acctLv')
        if acct_lv == '1':
            print("\n建议:")
            print("1. 当前账户为简单交易模式,不支持杠杆交易")
            print("2. 需要在 OKX 网站升级到统一账户模式")
            print("3. 或者使用 tdMode=cash 进行普通现货交易")
        else:
            print("\n建议:")
            print("1. 账户已支持保证金模式")
            print("2. 检查是否需要在网站上开通杠杆交易权限")
            print("3. 确认最小下单金额要求")
    
    print()

if __name__ == '__main__':
    main()
