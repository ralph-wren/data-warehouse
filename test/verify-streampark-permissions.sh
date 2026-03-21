#!/bin/bash

# StreamPark 权限验证脚本
# 用于验证 admin 用户的权限配置是否完整

echo "=========================================="
echo "StreamPark 权限配置验证"
echo "=========================================="

# 1. 检查用户信息
echo ""
echo "1. 检查 admin 用户信息..."
mysql -h 127.0.0.1 -P 3306 -u root -pHg19951030 streampark -e "
SELECT 
    user_id, 
    username, 
    nick_name, 
    user_type, 
    last_team_id, 
    status,
    last_login_time
FROM t_user 
WHERE username = 'admin';
" 2>/dev/null

# 2. 检查团队成员关系
echo ""
echo "2. 检查团队成员关系..."
mysql -h 127.0.0.1 -P 3306 -u root -pHg19951030 streampark -e "
SELECT 
    m.id,
    m.team_id,
    t.team_name,
    m.user_id,
    u.username,
    m.role_id,
    r.role_name
FROM t_member m
JOIN t_team t ON m.team_id = t.id
JOIN t_user u ON m.user_id = u.user_id
JOIN t_role r ON m.role_id = r.role_id
WHERE m.user_id = 100000;
" 2>/dev/null

# 3. 检查角色权限数量
echo ""
echo "3. 检查角色权限数量..."
mysql -h 127.0.0.1 -P 3306 -u root -pHg19951030 streampark -e "
SELECT 
    r.role_id,
    r.role_name,
    COUNT(rm.menu_id) as menu_count,
    (SELECT COUNT(*) FROM t_menu) as total_menus
FROM t_role r
LEFT JOIN t_role_menu rm ON r.role_id = rm.role_id
WHERE r.role_id = 100001
GROUP BY r.role_id, r.role_name;
" 2>/dev/null

# 4. 检查系统设置相关权限
echo ""
echo "4. 检查系统设置相关权限..."
mysql -h 127.0.0.1 -P 3306 -u root -pHg19951030 streampark -e "
SELECT 
    m.menu_id,
    m.menu_name,
    m.perms,
    CASE WHEN rm.menu_id IS NOT NULL THEN '✓' ELSE '✗' END as assigned
FROM t_menu m
LEFT JOIN t_role_menu rm ON m.menu_id = rm.menu_id AND rm.role_id = 100001
WHERE m.menu_name LIKE '%setting%' OR m.perms LIKE '%setting%'
ORDER BY m.menu_id;
" 2>/dev/null

# 5. 检查是否有未分配的权限
echo ""
echo "5. 检查未分配的权限..."
UNASSIGNED=$(mysql -h 127.0.0.1 -P 3306 -u root -pHg19951030 streampark -se "
SELECT COUNT(*)
FROM t_menu m
WHERE m.menu_id NOT IN (
    SELECT menu_id FROM t_role_menu WHERE role_id = 100001
);
" 2>/dev/null)

if [ "$UNASSIGNED" -eq 0 ]; then
    echo "✅ 所有权限都已分配"
else
    echo "⚠️  发现 $UNASSIGNED 个未分配的权限"
    mysql -h 127.0.0.1 -P 3306 -u root -pHg19951030 streampark -e "
    SELECT menu_id, menu_name, perms
    FROM t_menu m
    WHERE m.menu_id NOT IN (
        SELECT menu_id FROM t_role_menu WHERE role_id = 100001
    )
    ORDER BY menu_id;
    " 2>/dev/null
fi

echo ""
echo "=========================================="
echo "验证完成"
echo "=========================================="
echo ""
echo "📌 如果权限配置正确但仍无法访问系统设置："
echo "1. 运行: ./test/restart-streampark-refresh-permissions.sh"
echo "2. 清除浏览器缓存或使用无痕模式"
echo "3. 重新登录 StreamPark"
echo ""
