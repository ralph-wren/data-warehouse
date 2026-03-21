#!/usr/bin/env python3
"""
StreamPark 密码生成工具
使用 SHA-256 + PBKDF2 生成密码哈希
"""

import hashlib

def sha256_hash_iterations(password, salt, iterations=1024):
    """
    使用 SHA-256 进行多次迭代哈希
    模拟 Shiro SimpleHash 的行为
    """
    # 第一次哈希: SHA-256(password + salt)
    hash_value = hashlib.sha256((password + salt).encode('utf-8')).digest()
    
    # 迭代哈希
    for i in range(1, iterations):
        hash_value = hashlib.sha256(hash_value).digest()
    
    # 转换为十六进制字符串
    return hash_value.hex()

def main():
    password = "streampark"
    salt = "26f87aee40e022f38e8ca2d8c9"
    iterations = 1024
    
    print("=" * 50)
    print("StreamPark 密码生成")
    print("=" * 50)
    print()
    print("输入:")
    print(f"  密码: {password}")
    print(f"  Salt: {salt}")
    print(f"  算法: SHA-256")
    print(f"  迭代: {iterations}")
    print()
    
    # 生成密码哈希
    hashed_password = sha256_hash_iterations(password, salt, iterations)
    
    print("输出:")
    print(f"  Password Hash: {hashed_password}")
    print()
    print("SQL 更新语句:")
    print(f"  UPDATE t_user SET")
    print(f"    salt = '{salt}',")
    print(f"    password = '{hashed_password}'")
    print(f"  WHERE username = 'admin';")
    print()

if __name__ == "__main__":
    main()
