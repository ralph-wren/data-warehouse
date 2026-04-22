# MySQL 常用命令

## StreamPark Docker MySQL

```bash
# 启动 Docker MySQL
bash manage-mysql.sh start

# 查看状态
bash manage-mysql.sh status

# 查看日志
bash manage-mysql.sh logs

# 停止 MySQL
bash manage-mysql.sh stop

# 清理并重建（会删除 streampark 元数据）
bash manage-mysql.sh clean
```

## StreamPark 元数据库初始化

```bash
# 手动补初始化 StreamPark 表结构和初始数据
bash scripts/init-streampark-mysql.sh

# 查看 StreamPark 表数量
docker exec streampark-mysql mysql -N -uroot -pHg19951030 -e \
  "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema='streampark';"
```

## 宿主机 MySQL 管理

```bash
# 停止本地 Homebrew MySQL
brew services stop mysql
brew services stop mysql@8.0

# 检查 3306 监听
lsof -iTCP:3306 -sTCP:LISTEN -n -P
```
