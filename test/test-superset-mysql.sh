#!/bin/bash

# Superset MySQL 连接测试脚本
# 用于诊断 Superset 是否支持 MySQL/Doris 连接

echo "=========================================="
echo "Superset MySQL 连接诊断"
echo "=========================================="
echo ""

# 1. 检查 pymysql 是否安装
echo "1. 检查 pymysql 驱动..."
docker exec superset pip list | grep -i mysql
echo ""

# 2. 测试 pymysql 导入
echo "2. 测试 pymysql 导入..."
docker exec superset python -c "import pymysql; print('✓ pymysql 导入成功')"
echo ""

# 3. 测试连接 Doris
echo "3. 测试连接 Doris FE..."
docker exec superset python -c "
import pymysql
try:
    conn = pymysql.connect(
        host='doris-fe',
        port=9030,
        user='root',
        password='',
        database='crypto_dw',
        connect_timeout=5
    )
    print('✓ 连接 Doris 成功')
    cursor = conn.cursor()
    cursor.execute('SELECT DATABASE()')
    result = cursor.fetchone()
    print(f'✓ 当前数据库: {result[0]}')
    cursor.execute('SHOW TABLES')
    tables = cursor.fetchall()
    print(f'✓ 表数量: {len(tables)}')
    conn.close()
except Exception as e:
    print(f'✗ 连接失败: {e}')
"
echo ""

# 4. 检查 Superset 可用的数据库引擎
echo "4. 检查 Superset 可用的数据库引擎..."
docker exec superset python -c "
from superset.db_engine_specs import load_engine_specs
specs = load_engine_specs()
print('可用的数据库引擎:')
for name in sorted(specs.keys()):
    spec = specs[name]
    engine_name = getattr(spec, 'engine_name', name)
    print(f'  - {name}: {engine_name}')
"
echo ""

# 5. 测试 SQLAlchemy 连接字符串
echo "5. 测试 SQLAlchemy 连接字符串..."
docker exec superset python -c "
from sqlalchemy import create_engine
from sqlalchemy.engine.url import make_url

# 测试连接字符串
url_str = 'mysql+pymysql://root@doris-fe:9030/crypto_dw'
print(f'连接字符串: {url_str}')

url = make_url(url_str)
print(f'  Driver: {url.drivername}')
print(f'  Host: {url.host}')
print(f'  Port: {url.port}')
print(f'  Database: {url.database}')

try:
    engine = create_engine(url_str, connect_args={'connect_timeout': 5})
    with engine.connect() as conn:
        result = conn.execute('SELECT 1')
        print('✓ SQLAlchemy 连接成功')
except Exception as e:
    print(f'✗ SQLAlchemy 连接失败: {e}')
"
echo ""

# 6. 检查 Superset 配置
echo "6. 检查 Superset 配置..."
docker exec superset python -c "
from superset import app
config = app.config

print('Superset 配置:')
print(f'  SQLALCHEMY_DATABASE_URI: {config.get(\"SQLALCHEMY_DATABASE_URI\", \"未设置\")}')
print(f'  FEATURE_FLAGS: {config.get(\"FEATURE_FLAGS\", {})}')
"
echo ""

echo "=========================================="
echo "诊断完成"
echo "=========================================="
echo ""
echo "如果所有测试都通过,但 Web UI 中仍然没有 MySQL 选项,"
echo "请尝试以下方法:"
echo ""
echo "方法 1: 使用 SQL Lab 直接连接"
echo "  1. 访问 http://localhost:8088"
echo "  2. 点击 SQL -> SQL Lab"
echo "  3. 点击 + 按钮添加数据库"
echo "  4. 在 SQLALCHEMY URI 中输入:"
echo "     mysql+pymysql://root@doris-fe:9030/crypto_dw"
echo ""
echo "方法 2: 重启 Superset 容器"
echo "  bash manage-superset.sh restart"
echo ""
