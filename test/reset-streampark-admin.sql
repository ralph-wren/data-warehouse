-- StreamPark 管理员用户重置 SQL
-- 用于重置 admin 用户密码为默认密码 streampark

USE streampark;

-- 1. 插入或更新默认团队
INSERT INTO t_team (id, team_name, description, create_time, modify_time) 
VALUES (100000, 'default', 'Default Team', NOW(), NOW())
ON DUPLICATE KEY UPDATE 
    team_name = 'default',
    description = 'Default Team',
    modify_time = NOW();

-- 2. 插入或更新 admin 用户
-- 密码: streampark
-- 加密方式: MD5(salt + password)
-- salt: 79e7e3b7e3b7e3b7e3b7e3
-- password: b4f3c0e8c5d7e8c5e7e7c0e8c5d7e8c5
INSERT INTO t_user (
    user_id, username, nick_name, salt, password, 
    user_type, login_type, email, status, 
    create_time, modify_time, 
    last_team_id, sex
) VALUES (
    100000, 'admin', 'Admin', 
    '79e7e3b7e3b7e3b7e3b7e3', 
    'b4f3c0e8c5d7e8c5e7e7c0e8c5d7e8c5',
    1, 0, 'admin@streampark.com', '1',
    NOW(), NOW(),
    100000, '0'
)
ON DUPLICATE KEY UPDATE 
    salt = '79e7e3b7e3b7e3b7e3b7e3',
    password = 'b4f3c0e8c5d7e8c5e7e7c0e8c5d7e8c5',
    nick_name = 'Admin',
    email = 'admin@streampark.com',
    status = '1',
    modify_time = NOW();

-- 3. 插入或更新管理员角色
INSERT INTO t_role (
    role_id, role_name, description, 
    create_time, modify_time
) VALUES (
    100001, 'Admin', 'Administrator', 
    NOW(), NOW()
)
ON DUPLICATE KEY UPDATE 
    role_name = 'Admin',
    description = 'Administrator',
    modify_time = NOW();

-- 4. 插入或更新团队成员关系
INSERT INTO t_member (
    team_id, user_id, role_id, 
    create_time, modify_time
) VALUES (
    100000, 100000, 100001,
    NOW(), NOW()
)
ON DUPLICATE KEY UPDATE 
    role_id = 100001,
    modify_time = NOW();

-- 5. 验证插入结果
SELECT 
    u.user_id, 
    u.username, 
    u.nick_name, 
    u.email, 
    u.status,
    t.team_name,
    r.role_name
FROM t_user u
LEFT JOIN t_team t ON u.last_team_id = t.id
LEFT JOIN t_member m ON u.user_id = m.user_id
LEFT JOIN t_role r ON m.role_id = r.role_id
WHERE u.username = 'admin';
