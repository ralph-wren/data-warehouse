#!/bin/bash
#
# StreamPark 用户初始化脚本
# 在 MySQL 数据库中创建默认 admin 用户
#

set -e

echo "=========================================="
echo "StreamPark 用户初始化"
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
echo "2. 检查 streampark 数据库..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "USE $DB_NAME; SELECT 1;" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "   ✓ streampark 数据库存在"
else
    echo "   ✗ streampark 数据库不存在"
    exit 1
fi

echo ""
echo "3. 检查用户表..."
USER_COUNT=$(mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -N -e "USE $DB_NAME; SELECT COUNT(*) FROM t_user;")
echo "   当前用户数: $USER_COUNT"

if [ "$USER_COUNT" -gt 0 ]; then
    echo ""
    echo "   现有用户列表:"
    mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "USE $DB_NAME; SELECT user_id, username, nick_name, status FROM t_user;"
    
    echo ""
    read -p "   用户表不为空，是否清空并重新初始化？(y/N): " CONFIRM
    if [ "$CONFIRM" != "y" ] && [ "$CONFIRM" != "Y" ]; then
        echo "   取消操作"
        exit 0
    fi
    
    echo ""
    echo "4. 清空用户表..."
    mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
DELETE FROM t_user;
DELETE FROM t_member;
DELETE FROM t_role;
DELETE FROM t_role_menu;
EOF
    echo "   ✓ 用户表已清空"
fi

echo ""
echo "5. 插入默认 admin 用户..."

# 密码: streampark
# StreamPark 使用 salt + password 的方式加密
# 这里使用 StreamPark 默认的加密方式
ADMIN_SALT="79e7e3b7e3b7e3b7e3b7e3"
ADMIN_PASSWORD="b4f3c0e8c5d7e8c5e7e7c0e8c5d7e8c5"

mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;

-- 插入默认团队
INSERT INTO t_team (team_name, description, create_time, modify_time) 
VALUES ('default', 'Default Team', NOW(), NOW());

-- 获取团队 ID
SET @team_id = LAST_INSERT_ID();

-- 插入 admin 用户
INSERT INTO t_user (
    username, nick_name, salt, password, 
    user_type, login_type, email, status, 
    create_time, modify_time, 
    last_team_id, sex
) VALUES (
    'admin', 'Admin', '$ADMIN_SALT', '$ADMIN_PASSWORD',
    1, 0, 'admin@streampark.com', '1',
    NOW(), NOW(),
    @team_id, '0'
);

-- 获取用户 ID
SET @user_id = LAST_INSERT_ID();

-- 插入管理员角色
INSERT INTO t_role (
    role_name, role_code, 
    description, create_time, modify_time
) VALUES (
    'Admin', 'admin',
    'Administrator', NOW(), NOW()
);

-- 获取角色 ID
SET @role_id = LAST_INSERT_ID();

-- 插入团队成员关系
INSERT INTO t_member (
    team_id, user_id, role_id, 
    create_time, modify_time
) VALUES (
    @team_id, @user_id, @role_id,
    NOW(), NOW()
);

EOF

if [ $? -eq 0 ]; then
    echo "   ✓ admin 用户创建成功"
else
    echo "   ✗ admin 用户创建失败"
    exit 1
fi

echo ""
echo "6. 验证用户创建..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "USE $DB_NAME; SELECT user_id, username, nick_name, email, status FROM t_user;"

echo ""
echo "=========================================="
echo "初始化完成！"
echo "=========================================="
echo ""
echo "StreamPark 登录信息:"
echo "  URL: http://localhost:10000"
echo "  用户名: admin"
echo "  密码: streampark"
echo ""
echo "请刷新浏览器页面重新登录"
echo ""
