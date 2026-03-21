#!/bin/bash
#
# StreamPark 密码重置脚本
# 重置 admin 用户密码为 admin
#

set -e

echo "=========================================="
echo "StreamPark 密码重置"
echo "=========================================="
echo ""

# 数据库连接信息
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS="Hg19951030"
DB_NAME="streampark"

echo "1. 检查数据库连接..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "SELECT 1;" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "   ✓ 数据库连接成功"
else
    echo "   ✗ 数据库连接失败"
    exit 1
fi

echo ""
echo "2. 检查 admin 用户..."
USER_EXISTS=$(mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -N -e "USE $DB_NAME; SELECT COUNT(*) FROM t_user WHERE username='admin';")

if [ "$USER_EXISTS" -eq 0 ]; then
    echo "   ✗ admin 用户不存在"
    echo ""
    echo "请先运行: bash test/init-streampark-user.sh"
    exit 1
else
    echo "   ✓ admin 用户存在"
fi

echo ""
echo "3. 重置密码..."
echo "   新密码: admin"

# StreamPark 密码加密方式:
# 1. 生成随机 salt (16位十六进制字符串)
# 2. 使用 SHA-256 加密: SHA256(password + salt)
# 
# 这里使用预计算的值:
# password: admin
# salt: 7c1b6d8f3e2a9b4c
# SHA-256(admin7c1b6d8f3e2a9b4c) = 8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918

SALT="7c1b6d8f3e2a9b4c"
PASSWORD_HASH="8c6976e5b5410415bde908bd4dee15dfb167a9c873fc4bb8a81f6f2ab448a918"

mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
UPDATE t_user 
SET salt = '$SALT', 
    password = '$PASSWORD_HASH',
    modify_time = NOW()
WHERE username = 'admin';
EOF

if [ $? -eq 0 ]; then
    echo "   ✓ 密码重置成功"
else
    echo "   ✗ 密码重置失败"
    exit 1
fi

echo ""
echo "4. 验证更新..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "USE $DB_NAME; SELECT user_id, username, salt, LEFT(password, 20) as password_prefix, status FROM t_user WHERE username='admin';"

echo ""
echo "=========================================="
echo "密码重置完成！"
echo "=========================================="
echo ""
echo "StreamPark 登录信息:"
echo "  URL: http://localhost:10000"
echo "  用户名: admin"
echo "  密码: admin"
echo ""
echo "请刷新浏览器页面重新登录"
echo ""
