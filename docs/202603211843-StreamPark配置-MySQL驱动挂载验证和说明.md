# StreamPark配置 - MySQL驱动挂载验证和说明

## 任务背景

用户需要在 StreamPark 容器中挂载 MySQL 驱动到 lib 目录，以支持 MySQL/Doris 数据库连接。

## 检查结果

经过检查，发现 `docker-compose-streampark.yml` 文件中**已经配置了 MySQL 驱动挂载**：

```yaml
volumes:
  # ... 其他挂载配置 ...
  
  # 挂载 MySQL 驱动到容器 lib 目录 - 支持 MySQL/Doris 连接
  - ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro
```

## 配置说明

### 1. 挂载路径

**源路径**（宿主机）：
```
~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar
```
- 位置：Maven 本地仓库
- 大小：2.4 MB
- 版本：8.3.0（最新稳定版）

**目标路径**（容器内）：
```
/streampark/lib/mysql-connector-j-8.3.0.jar
```
- StreamPark 会自动加载 lib 目录下的所有 jar 文件
- 容器启动时会将驱动添加到 classpath

**挂载模式**：`:ro`（只读）
- 防止容器内修改驱动文件
- 提高安全性

### 2. 为什么选择 MySQL Connector/J 8.3.0

| 特性 | 说明 |
|-----|------|
| **最新版本** | 支持最新的 MySQL 8.x 特性 |
| **向下兼容** | 兼容 MySQL 5.7+ |
| **性能优化** | 包含最新的性能改进和 bug 修复 |
| **Doris 兼容** | Doris 基于 MySQL 协议，使用最新驱动可以获得更好的兼容性 |
| **安全性** | 包含最新的安全补丁 |

### 3. StreamPark 数据库配置

在 `docker-compose-streampark.yml` 中，通过环境变量配置数据库连接：

```yaml
environment:
  # MySQL 数据库配置 - 使用环境变量配置数据库连接
  - SPRING_DATASOURCE_DIALECT=mysql
  - SPRING_DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/streampark?useUnicode=true&characterEncoding=UTF-8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai
  - SPRING_DATASOURCE_USERNAME=root
  - SPRING_DATASOURCE_PASSWORD=Hg19951030
```

**连接参数说明**：
- `host.docker.internal:3306`：连接宿主机的 MySQL（Windows/Mac Docker Desktop 特性）
- `useUnicode=true&characterEncoding=UTF-8`：支持中文字符
- `useSSL=false`：开发环境禁用 SSL（生产环境建议启用）
- `allowPublicKeyRetrieval=true`：允许客户端从服务器获取公钥
- `serverTimezone=Asia/Shanghai`：设置时区为东八区

## 验证步骤

### 1. 检查驱动文件是否存在

```bash
ls -lh ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar
```

**预期输出**：
```
-rw-r--r-- 1 ralph 197609 2.4M Jul  5  2025 /c/Users/ralph/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar
```

### 2. 启动 StreamPark 容器

```bash
# 停止并删除旧容器
docker-compose -f docker-compose-streampark.yml down

# 启动新容器
docker-compose -f docker-compose-streampark.yml up -d

# 查看启动日志
docker logs -f streampark
```

### 3. 验证驱动是否挂载成功

```bash
# 进入容器
docker exec -it streampark bash

# 查看 lib 目录
ls -lh /streampark/lib/mysql-connector-j-8.3.0.jar

# 预期输出
-rw-r--r-- 1 root root 2.4M Jul  5  2025 /streampark/lib/mysql-connector-j-8.3.0.jar

# 退出容器
exit
```

### 4. 检查数据库连接

访问 StreamPark Web UI：http://localhost:10000
- 用户名：admin
- 密码：streampark

如果能正常登录并看到系统信息，说明数据库连接成功。

### 5. 查看容器日志

```bash
# 查看启动日志
docker logs streampark | head -50

# 查看数据库连接日志
docker logs streampark | grep -i "mysql\|datasource\|jdbc"
```

**成功的日志示例**：
```
Loading MySQL driver: com.mysql.cj.jdbc.Driver
Database connection established successfully
HikariPool-1 - Starting...
HikariPool-1 - Start completed.
```

**失败的日志示例**：
```
java.lang.ClassNotFoundException: com.mysql.cj.jdbc.Driver
Could not load JDBC driver class [com.mysql.cj.jdbc.Driver]
```

## 常见问题排查

### 问题 1：驱动文件不存在

**错误信息**：
```
Error response from daemon: invalid mount config for type "bind": 
bind source path does not exist
```

**解决方法**：
```bash
# 检查驱动是否存在
ls ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/

# 如果不存在，下载驱动
mvn dependency:get -Dartifact=com.mysql:mysql-connector-j:8.3.0
```

### 问题 2：驱动未加载

**错误信息**：
```
java.lang.ClassNotFoundException: com.mysql.cj.jdbc.Driver
```

**解决方法**：
```bash
# 1. 检查挂载是否成功
docker exec streampark ls -lh /streampark/lib/mysql-connector-j-8.3.0.jar

# 2. 检查文件权限
docker exec streampark ls -la /streampark/lib/

# 3. 重启容器
docker-compose -f docker-compose-streampark.yml restart
```

### 问题 3：数据库连接失败

**错误信息**：
```
Communications link failure
The last packet sent successfully to the server was 0 milliseconds ago.
```

**解决方法**：
```bash
# 1. 检查 MySQL 是否运行
mysql -h 127.0.0.1 -P 3306 -u root -p

# 2. 检查数据库是否存在
mysql -h 127.0.0.1 -P 3306 -u root -p -e "SHOW DATABASES LIKE 'streampark';"

# 3. 创建数据库（如果不存在）
mysql -h 127.0.0.1 -P 3306 -u root -p -e "CREATE DATABASE IF NOT EXISTS streampark;"

# 4. 检查用户权限
mysql -h 127.0.0.1 -P 3306 -u root -p -e "SHOW GRANTS FOR 'root'@'%';"
```

### 问题 4：Windows 路径问题

**错误信息**：
```
invalid mount config: path does not exist
```

**解决方法**：

Windows Git Bash 环境下，`~/.m2` 会自动转换为正确的路径。如果遇到问题，可以使用完整路径：

```yaml
# 方式 1：使用 ~ 路径（推荐）
- ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro

# 方式 2：使用完整 Windows 路径
- C:/Users/ralph/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro

# 方式 3：使用 Unix 风格路径
- /c/Users/ralph/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro
```

## 其他可用的 MySQL 驱动版本

如果需要使用其他版本的 MySQL 驱动，可以修改挂载路径：

### MySQL Connector/J 8.1.0
```yaml
- ~/.m2/repository/com/mysql/mysql-connector-j/8.1.0/mysql-connector-j-8.1.0.jar:/streampark/lib/mysql-connector-j-8.1.0.jar:ro
```

### MySQL Connector/J 8.0.33
```yaml
- ~/.m2/repository/com/mysql/mysql-connector-j/8.0.33/mysql-connector-j-8.0.33.jar:/streampark/lib/mysql-connector-j-8.0.33.jar:ro
```

### 旧版本 MySQL Connector/Java 8.0.28
```yaml
- ~/.m2/repository/mysql/mysql-connector-java/8.0.28/mysql-connector-java-8.0.28.jar:/streampark/lib/mysql-connector-java-8.0.28.jar:ro
```

**注意**：
- MySQL Connector/J 8.0+ 的包名从 `mysql:mysql-connector-java` 改为 `com.mysql:mysql-connector-j`
- 推荐使用最新的 8.3.0 版本

## 最佳实践

### 1. 使用只读挂载

```yaml
- ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro
```

`:ro` 参数表示只读挂载，防止容器内修改驱动文件。

### 2. 统一驱动版本

确保所有使用 MySQL 的服务（StreamPark、Flink 作业等）使用相同版本的驱动，避免兼容性问题。

### 3. 定期更新驱动

定期检查并更新 MySQL 驱动到最新版本，获取性能改进和安全补丁：

```bash
# 更新到最新版本
mvn dependency:get -Dartifact=com.mysql:mysql-connector-j:LATEST
```

### 4. 备份驱动文件

```bash
# 备份驱动到项目目录
mkdir -p lib
cp ~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar lib/

# 修改挂载路径
- ./lib/mysql-connector-j-8.3.0.jar:/streampark/lib/mysql-connector-j-8.3.0.jar:ro
```

这样可以避免依赖本地 Maven 仓库，方便项目迁移。

## 相关配置文件

- `docker-compose-streampark.yml` - StreamPark Docker Compose 配置文件
- `streampark-config.yaml` - StreamPark 自定义配置文件
- `setup-streampark-mysql.sh` - StreamPark MySQL 数据库初始化脚本

## 参考文档

- [StreamPark 官方文档](https://streampark.apache.org/)
- [MySQL Connector/J 文档](https://dev.mysql.com/doc/connector-j/en/)
- [Docker Compose Volumes 文档](https://docs.docker.com/compose/compose-file/compose-file-v3/#volumes)
- [Spring Boot 数据源配置](https://docs.spring.io/spring-boot/docs/current/reference/html/application-properties.html#application-properties.data)

## 总结

StreamPark 的 MySQL 驱动挂载配置已经正确配置，包括：

1. ✅ 驱动文件存在：`~/.m2/repository/com/mysql/mysql-connector-j/8.3.0/mysql-connector-j-8.3.0.jar`
2. ✅ 挂载配置正确：挂载到容器的 `/streampark/lib/` 目录
3. ✅ 使用只读模式：`:ro` 参数提高安全性
4. ✅ 数据库连接配置：通过环境变量配置 MySQL 连接信息

**下一步操作**：

如果容器还未启动或需要重启以应用配置：

```bash
# 重启 StreamPark 容器
docker-compose -f docker-compose-streampark.yml down
docker-compose -f docker-compose-streampark.yml up -d

# 查看日志
docker logs -f streampark
```

访问 StreamPark Web UI 验证：http://localhost:10000
