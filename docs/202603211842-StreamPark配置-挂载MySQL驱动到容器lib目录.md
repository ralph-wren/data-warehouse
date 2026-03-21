# StreamPark配置 - 挂载MySQL驱动到容器lib目录

## 问题背景

StreamPark 容器需要 MySQL 驱动来连接 MySQL 数据库（或 Doris），但容器内可能缺少或版本不匹配的 MySQL 驱动，导致数据库连接失败。

## 解决方案

通过 Docker Compose 的 volumes 配置，将本地 Maven 仓库中的 MySQL 驱动挂载到容器的 lib 目录。

## 实施步骤

### 1. 查找本地 MySQL 驱动

```bash
find ~/.m2/repository -name "mysql-connector-*.jar"
```

找到的驱动文件：
- `/c/Users/ralph/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar`
- `/c/Users/ralph/.m2/repository/com/mysql/mysql-connector-j/8.1.0/mysql-connector-j-8.1.0.jar`
- `/c/Users/ralph/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar`

### 2. 修改 docker-compose-streampark.yml

在 volumes 配置中添加 MySQL 驱动挂载：

```yaml
volumes:
  # ... 其他挂载配置 ...
  
  # 挂载 MySQL 驱动到容器 lib 目录 - 支持 MySQL/Doris 连接
  - ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro
```

### 3. 重启 StreamPark 容器

```bash
# 停止并删除旧容器
docker-compose -f docker-compose-streampark.yml down

# 启动新容器
docker-compose -f docker-compose-streampark.yml up -d

# 查看日志确认启动成功
docker logs -f streampark
```

## 配置说明

### 挂载路径解释

- **源路径**：`~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar`
  - 本地 Maven 仓库中的 MySQL 驱动文件
  - 使用 8.3.0 版本（最新版本）

- **目标路径**：`/streampark/lib/mysql-connector-j-8.3.0.jar`
  - StreamPark 容器内的 lib 目录
  - 容器启动时会自动加载此目录下的 jar 文件

- **挂载模式**：`:ro`（只读）
  - 防止容器内修改驱动文件
  - 提高安全性

### 为什么选择 8.3.0 版本

1. **最新版本**：支持最新的 MySQL 8.x 特性
2. **兼容性好**：向下兼容 MySQL 5.7+
3. **性能优化**：包含最新的性能改进和 bug 修复
4. **Doris 兼容**：Doris 基于 MySQL 协议，使用最新驱动可以获得更好的兼容性

## 验证方法

### 1. 检查驱动是否挂载成功

```bash
# 进入容器
docker exec -it streampark bash

# 查看 lib 目录
ls -lh /streampark/lib/mysql-connector-j-8.3.0.jar

# 退出容器
exit
```

### 2. 检查数据库连接

访问 StreamPark Web UI：http://localhost:10000

查看系统设置中的数据库连接状态。

### 3. 查看容器日志

```bash
docker logs streampark | grep -i mysql
```

如果看到类似以下日志，说明驱动加载成功：
```
Loading MySQL driver: com.mysql.cj.jdbc.Driver
Database connection established successfully
```

## 注意事项

1. **路径格式**：
   - Windows Git Bash 环境下，`~/.m2` 会自动转换为正确的 Windows 路径
   - 如果使用 PowerShell，需要使用完整的 Windows 路径

2. **驱动版本**：
   - 如果需要使用其他版本，修改挂载路径中的版本号即可
   - 建议使用 8.0+ 版本以获得更好的性能和兼容性

3. **容器重启**：
   - 修改 docker-compose.yml 后必须重启容器才能生效
   - 使用 `docker-compose down` 和 `up -d` 而不是 `restart`

4. **权限问题**：
   - 确保本地驱动文件有读取权限
   - 使用 `:ro` 只读挂载提高安全性

## 相关文件

- `docker-compose-streampark.yml` - StreamPark Docker Compose 配置文件
- `streampark-config.yaml` - StreamPark 自定义配置文件

## 参考文档

- [StreamPark 官方文档](https://streampark.apache.org/)
- [MySQL Connector/J 文档](https://dev.mysql.com/doc/connector-j/en/)
- [Docker Compose Volumes 文档](https://docs.docker.com/compose/compose-file/compose-file-v3/#volumes)
