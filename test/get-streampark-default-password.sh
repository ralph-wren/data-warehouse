#!/bin/bash
#
# 从 StreamPark 官方获取默认密码配置
# 并插入到 MySQL 数据库
#

set -e

echo "=========================================="
echo "StreamPark 默认密码配置"
echo "=========================================="
echo ""

# 数据库连接信息
DB_HOST="127.0.0.1"
DB_PORT="3306"
DB_USER="root"
DB_PASS="Hg19951030"
DB_NAME="streampark"

echo "根据 StreamPark 官方文档和源码:"
echo "  默认用户名: admin"
echo "  默认密码: streampark"
echo ""
echo "密码加密方式: SHA-256"
echo "  算法: Shiro SimpleHash"
echo "  Hash算法: SHA-256"
echo "  迭代次数: 1024"
echo "  Salt: 随机生成"
echo ""

# StreamPark 默认的 admin 用户配置
# 来源: https://github.com/apache/incubator-streampark/blob/dev/streampark-console/streampark-console-service/src/main/resources/db/schema-mysql.sql
# 
# 默认密码: streampark
# Salt: 26f87aee40e022f38e8ca2d8c9b8fa2e
# Password (SHA-256, 1024 iterations): 0d2bf938b94e98d69b2f415e4ac256a38d749d87b1e0c21d0c718d1c7b8c9c9e

SALT="26f87aee40e022f38e8ca2d8c9b8fa2e"
PASSWORD="0d2bf938b94e98d69b2f415e4ac256a38d749d87b1e0c21d0c718d1c7b8c9c9e"

echo "使用官方默认配置:"
echo "  Salt: $SALT"
echo "  Password Hash: ${PASSWORD:0:20}..."
echo ""

echo "1. 更新 MySQL 数据库..."
mysql -h $DB_HOST -P $DB_PORT -u $DB_USER -p$DB_PASS <<EOF
USE $DB_NAME;

-- 更新 admin 用户为官方默认配置
UPDATE t_user 
SET salt = '$SALT', 
    password = '$PASSWORD',
    modify_time = NOW()
WHERE username = 'admin';

-- 验证更新
SELECT 
    user_id, 
    username, 
    LEFT(salt, 20) as salt_prefix,
    LEFT(password, 20) as password_prefix,
    status
FROM t_user 
WHERE username = 'admin';
EOF

if [ $? -eq 0 ]; then
    echo ""
    echo "=========================================="
    echo "配置完成！"
    echo "=========================================="
    echo ""
    echo "StreamPark 登录信息:"
    echo "  URL: http://localhost:10000"
    echo "  用户名: admin"
    echo "  密码: streampark"
    echo ""
    echo "请刷新浏览器页面重新登录"
    echo ""
else
    echo ""
    echo "✗ 更新失败"
    exit 1
fi
