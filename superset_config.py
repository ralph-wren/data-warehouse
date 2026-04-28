# Superset 配置文件
import os
import pymysql

# 让 Superset 以 MySQLdb 方式识别 PyMySQL，保证 Doris(MySQL 协议)可在 UI 中选择
pymysql.install_as_MySQLdb()

# PostgreSQL 数据库连接
SQLALCHEMY_DATABASE_URI = 'postgresql://superset:superset@superset-db:5432/superset'

# Redis 缓存配置
REDIS_HOST = 'superset-redis'
REDIS_PORT = 6379

# 缓存配置
CACHE_CONFIG = {
    'CACHE_TYPE': 'RedisCache',
    'CACHE_DEFAULT_TIMEOUT': 300,
    'CACHE_KEY_PREFIX': 'superset_',
    'CACHE_REDIS_HOST': REDIS_HOST,
    'CACHE_REDIS_PORT': REDIS_PORT,
    'CACHE_REDIS_DB': 1,
}

# 数据缓存配置
DATA_CACHE_CONFIG = {
    'CACHE_TYPE': 'RedisCache',
    'CACHE_DEFAULT_TIMEOUT': 86400,
    'CACHE_KEY_PREFIX': 'superset_data_',
    'CACHE_REDIS_HOST': REDIS_HOST,
    'CACHE_REDIS_PORT': REDIS_PORT,
    'CACHE_REDIS_DB': 2,
}

# 密钥配置
SECRET_KEY = 'your_secret_key_change_this_in_production'

# 不加载示例数据
SUPERSET_LOAD_EXAMPLES = False

# 中文支持
BABEL_DEFAULT_LOCALE = 'zh'
LANGUAGES = {
    'zh': {'flag': 'cn', 'name': 'Chinese'},
    'en': {'flag': 'us', 'name': 'English'},
}
