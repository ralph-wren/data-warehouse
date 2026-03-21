#!/bin/bash
#
# StreamPark 完整初始化脚本
# 初始化数据库、用户、角色、权限
#

set -e

echo "=========================================="
echo "StreamPark 完整初始化"
echo "=========================================="
echo ""

# 数据库连接信息
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS="Hg19951030"
DB_NAME="streampark"

# StreamPark 源码路径
STREAMPARK_SRC="/c/Users/ralph/IdeaProject/streampark"
MYSQL_DATA_SQL="$STREAMPARK_SRC/streampark-console/streampark-console-service/src/main/assembly/script/data/mysql-data.sql"

echo "1. 检查数据库连接..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -e "SELECT 1;" > /dev/null 2>&1
if [ $? -eq 0 ]; then
    echo "   ✓ 数据库连接成功"
else
    echo "   ✗ 数据库连接失败"
    exit 1
fi

echo ""
echo "2. 清空现有数据..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
-- 清空数据（保留表结构）
SET FOREIGN_KEY_CHECKS = 0;
DELETE FROM t_role_menu;
DELETE FROM t_member;
DELETE FROM t_user;
DELETE FROM t_team;
DELETE FROM t_role;
DELETE FROM t_menu;
SET FOREIGN_KEY_CHECKS = 1;
EOF
echo "   ✓ 数据清空完成"

echo ""
echo "3. 导入菜单数据..."
grep "insert into.*t_menu" "$MYSQL_DATA_SQL" | mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS $DB_NAME 2>&1 | grep -v "Warning"
MENU_COUNT=$(mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -N -e "USE $DB_NAME; SELECT COUNT(*) FROM t_menu;" 2>/dev/null)
echo "   ✓ 导入 $MENU_COUNT 个菜单项"

echo ""
echo "4. 创建默认团队..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
INSERT INTO t_team (id, team_name, description, create_time, modify_time) 
VALUES (100000, 'default', 'Default Team', NOW(), NOW());
EOF
echo "   ✓ 默认团队创建成功"

echo ""
echo "5. 创建 Admin 角色..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
INSERT INTO t_role (role_id, role_name, description, create_time, modify_time) 
VALUES (100001, 'Admin', 'Administrator with full permissions', NOW(), NOW());
EOF
echo "   ✓ Admin 角色创建成功"

echo ""
echo "6. 分配所有菜单权限给 Admin 角色..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
INSERT INTO t_role_menu (role_id, menu_id) 
SELECT 100001, menu_id FROM t_menu;
EOF
PERM_COUNT=$(mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS -N -e "USE $DB_NAME; SELECT COUNT(*) FROM t_role_menu WHERE role_id = 100001;" 2>/dev/null)
echo "   ✓ 分配 $PERM_COUNT 个权限"

echo ""
echo "7. 创建 admin 用户..."
# 使用 StreamPark 测试用例中的配置
# Salt: rh8b1ojwog777yrg0daesf04gk
# Password: streampark
# Hash: 2513f3748847298ea324dffbf67fe68681dd92315bda830065facd8efe08f54f
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
INSERT INTO t_user (
    user_id, username, nick_name, salt, password, 
    user_type, login_type, email, status, 
    create_time, modify_time, 
    last_team_id, sex
) VALUES (
    100000, 'admin', 'Administrator', 
    'rh8b1ojwog777yrg0daesf04gk', 
    '2513f3748847298ea324dffbf67fe68681dd92315bda830065facd8efe08f54f',
    1, 0, 'admin@streampark.com', '1',
    NOW(), NOW(),
    100000, '0'
);
EOF
echo "   ✓ admin 用户创建成功"

echo ""
echo "8. 添加用户到团队..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
INSERT INTO t_member (team_id, user_id, role_id, create_time, modify_time) 
VALUES (100000, 100000, 100001, NOW(), NOW());
EOF
echo "   ✓ 用户已添加到团队"

echo ""
echo "9. 验证配置..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;
SELECT 
    u.user_id, 
    u.username, 
    u.user_type as type,
    u.status,
    t.team_name,
    r.role_name,
    COUNT(rm.menu_id) as permissions
FROM t_user u
LEFT JOIN t_team t ON u.last_team_id = t.id
LEFT JOIN t_member m ON u.user_id = m.user_id
LEFT JOIN t_role r ON m.role_id = r.role_id
LEFT JOIN t_role_menu rm ON r.role_id = rm.role_id
WHERE u.username = 'admin'
GROUP BY u.user_id, u.username, u.user_type, u.status, t.team_name, r.role_name;
EOF

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
echo "配置说明:"
echo "  - 用户类型: 1 (管理员)"
echo "  - 角色: Admin"
echo "  - 权限: $PERM_COUNT 个菜单权限"
echo "  - 团队: default"
echo ""
echo "请重启浏览器或清除缓存后重新登录"
echo ""
