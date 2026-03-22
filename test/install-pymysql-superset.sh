#!/bin/bash

# Superset 安装 PyMySQL 驱动脚本
# 解决 Git Bash 路径转换问题

echo "=========================================="
echo "在 Superset 容器中安装 PyMySQL"
echo "=========================================="
echo ""

# 方法 1: 使用 docker exec 直接安装
echo "方法 1: 使用虚拟环境的 pip 安装..."
docker exec -u root superset /app/.venv/bin/pip install pymysql 2>&1 | head -20
echo ""

# 验证安装
echo "验证 pymysql 是否安装成功..."
docker exec superset /app/.venv/bin/python -c "import pymysql; print('✓ pymysql 安装成功'); print(f'版本: {pymysql.__version__}')" 2>&1
echo ""

# 测试连接 Doris
echo "测试连接 Doris..."
docker exec superset /app/.venv/bin/python << 'EOF'
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
    for table in tables[:5]:
        print(f'  - {table[0]}')
    conn.close()
except Exception as e:
    print(f'✗ 连接失败: {e}')
EOF

echo ""
echo "=========================================="
echo "安装完成"
echo "=========================================="
echo ""
echo "现在可以在 Superset Web UI 中添加数据库:"
echo "  1. 访问 http://localhost:8088"
echo "  2. 点击 Settings -> Database Connections"
echo "  3. 点击 + Database 按钮"
echo "  4. 选择 'MySQL' 或在 SQLALCHEMY URI 中输入:"
echo "     mysql+pymysql://root@doris-fe:9030/crypto_dw"
echo ""
