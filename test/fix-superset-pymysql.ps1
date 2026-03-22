# PowerShell 脚本 - 修复 Superset PyMySQL 驱动问题
# 用于在 Windows PowerShell 中执行,避免 Git Bash 路径转换问题

Write-Host "=========================================="
Write-Host "Superset PyMySQL 驱动安装"
Write-Host "=========================================="
Write-Host ""

# 1. 进入容器并安装 pymysql
Write-Host "1. 在 Superset 容器中安装 pymysql..."
docker exec -u root superset sh -c 'python -m ensurepip --upgrade && python -m pip install pymysql'

Write-Host ""

# 2. 验证安装
Write-Host "2. 验证 pymysql 安装..."
docker exec superset python -c "import pymysql; print('✓ pymysql 版本:', pymysql.__version__)"

Write-Host ""

# 3. 测试连接 Doris
Write-Host "3. 测试连接 Doris..."
docker exec superset python -c @"
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
    print('✓ 当前数据库:', result[0])
    cursor.execute('SHOW TABLES')
    tables = cursor.fetchall()
    print('✓ 表数量:', len(tables))
    conn.close()
except Exception as e:
    print('✗ 连接失败:', e)
"@

Write-Host ""
Write-Host "=========================================="
Write-Host "安装完成"
Write-Host "=========================================="
Write-Host ""
Write-Host "现在请重启 Superset 容器:"
Write-Host "  docker-compose -f docker-compose-superset.yml restart superset"
Write-Host ""
Write-Host "然后在 Superset Web UI 中添加数据库:"
Write-Host "  SQLALCHEMY URI: mysql+pymysql://root@doris-fe:9030/crypto_dw"
Write-Host ""
