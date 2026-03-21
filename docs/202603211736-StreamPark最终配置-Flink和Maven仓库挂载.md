# StreamPark 最终配置 - Flink 和 Maven 仓库挂载

## 问题描述

StreamPark 部署时需要配置：
1. Flink Home 路径
2. Maven 本地仓库（避免重复下载依赖）
3. 数据持久化

## 最终解决方案

### 1. Docker Compose 配置

`docker-compose-streampark.yml` 最终配置：

```yaml
version: '3.8'

services:
  streampark:
    image: apache/streampark:v2.1.5
    container_name: streampark
    hostname: streampark
    ports:
      - "10000:10000"  # StreamPark Web UI
    environment:
      - TZ=Asia/Shanghai  # 中国时区
      - DATASOURCE_DIALECT=h2  # 使用 H2 内置数据库
      # 注意：不设置 JAVA_HOME，使用 StreamPark 容器内置的 Java
    volumes:
      # 挂载 Docker socket，允许 StreamPark 管理容器
      - /var/run/docker.sock:/var/run/docker.sock
      # 挂载 hosts 文件（只读）
      - /etc/hosts:/etc/hosts:ro
      # StreamPark 数据持久化
      - ./streampark-data:/streampark/data
      # 挂载 Flink 到容器（只读）
      - /e/DataFiles/flink-1.17.0:/opt/flink:ro
      # 挂载 Maven 本地仓库 - 避免重复下载依赖
      - ~/.m2/repository:/root/.m2/repository
    privileged: true
    restart: unless-stopped
    networks:
      - streampark-net
      - doris-net  # 连接到 Doris 网络
      - default    # 连接到默认网络（Kafka）
```

### 2. 路径映射关系

| 主机路径 | 容器内路径 | 说明 | 权限 |
|---------|-----------|------|------|
| `E:\DataFiles\flink-1.17.0` | `/opt/flink` | Flink 安装目录 | 只读 |
| `~/.m2/repository` | `/root/.m2/repository` | Maven 本地仓库 | 读写 |
| `./streampark-data` | `/streampark/data` | StreamPark 数据 | 读写 |
| `/var/run/docker.sock` | `/var/run/docker.sock` | Docker 管理 | 读写 |

### 3. 关键配置说明

#### Flink 挂载

```yaml
- /e/DataFiles/flink-1.17.0:/opt/flink:ro
```

**说明**：
- Windows E 盘路径在 Git Bash 中是 `/e/`
- `:ro` 表示只读挂载，防止容器修改主机文件
- 容器内路径：`/opt/flink`

#### Maven 仓库挂载

```yaml
- ~/.m2/repository:/root/.m2/repository
```

**优点**：
- 避免重复下载依赖，节省时间和带宽
- 主机和容器共享同一个 Maven 仓库
- 构建 Flink 作业时直接使用本地依赖

**注意**：
- 不需要挂载整个 Maven Home（`apache-maven-3.3.9`）
- 只需要挂载本地仓库（`~/.m2/repository`）
- StreamPark 容器内置了 Maven，只需要共享依赖

#### Java 环境

**不需要挂载 JDK**：
- StreamPark 容器已经内置了 Java
- 挂载外部 JDK 可能导致启动失败
- 使用容器内置的 Java 更稳定

### 4. 启动和验证

#### 启动 StreamPark

```bash
# 使用启动脚本
./start-streampark.sh

# 或手动启动
docker-compose -f docker-compose-streampark.yml up -d

# 等待服务启动
sleep 25

# 检查容器状态
docker ps | grep streampark
```

#### 验证挂载

```bash
# 验证 Flink 挂载
docker exec streampark ls -la /opt/flink/

# 验证 Maven 仓库挂载
docker exec streampark ls -la /root/.m2/repository/

# 验证 StreamPark 数据目录
ls -la ./streampark-data/
```

### 5. 在 StreamPark Web UI 中配置

#### 步骤 1：登录

访问：http://localhost:10000
- 账号：`admin`
- 密码：`streampark`

#### 步骤 2：配置 Flink Home

1. 点击左侧菜单"设置中心"
2. 选择"Flink Home"
3. 点击"添加"按钮
4. 填写配置：
   - **Flink Version**: `1.17.0`
   - **Flink Home**: `/opt/flink`（容器内路径）
   - **描述**: `Flink 1.17.0 for development`
5. 点击"验证"按钮
6. 如果显示绿色勾号，点击"确定"保存

#### 步骤 3：配置 Maven（可选）

如果需要自定义 Maven 配置：

1. 进入"设置中心" -> "Maven 配置"
2. 配置 Maven 仓库地址（如果需要使用私有仓库）
3. 配置 settings.xml（如果需要自定义配置）

**注意**：由于已经挂载了本地仓库，通常不需要额外配置。

## 技术要点

### 1. 为什么挂载 Maven 仓库？

**问题**：
- StreamPark 构建 Flink 作业时需要下载大量依赖
- 每次构建都重新下载，浪费时间和带宽
- 本地已经有完整的 Maven 仓库

**解决方案**：
- 挂载本地 Maven 仓库到容器
- 容器直接使用本地依赖，无需重新下载
- 节省时间：首次构建可能需要 10 分钟，使用本地仓库只需 1-2 分钟

### 2. 为什么不挂载 Maven Home？

**只需要挂载仓库**：
```yaml
✅ 正确：- ~/.m2/repository:/root/.m2/repository
❌ 不需要：- /e/DataFiles/apache-maven-3.3.9:/opt/maven
```

**原因**：
- StreamPark 容器已经内置了 Maven
- 只需要共享依赖仓库，不需要共享 Maven 程序
- 挂载 Maven Home 可能导致版本冲突

### 3. 为什么不挂载 JDK？

**使用容器内置 Java**：
```yaml
✅ 正确：不设置 JAVA_HOME
❌ 错误：- JAVA_HOME=/opt/java
❌ 错误：- /e/DataFiles/Java/jdk1.8.0_321:/opt/java:ro
```

**原因**：
- StreamPark 启动脚本对 JAVA_HOME 有严格检查
- 挂载外部 JDK 可能导致路径问题
- 容器内置的 Java 已经过测试，更稳定

### 4. 数据持久化路径

**正确的挂载路径**：
```yaml
✅ 正确：- ./streampark-data:/streampark/data
❌ 错误：- ./streampark-data:/streampark  # 会覆盖程序目录
```

**原因**：
- `/streampark` 是 StreamPark 的程序目录
- 挂载到 `/streampark` 会覆盖程序文件，导致启动失败
- 应该挂载到 `/streampark/data` 子目录

## 常见问题

### 1. 容器一直重启

**检查日志**：
```bash
docker logs streampark --tail 50
```

**常见原因**：
- 挂载路径覆盖了程序目录
- JAVA_HOME 配置错误
- 端口冲突

### 2. Maven 依赖下载慢

**检查仓库挂载**：
```bash
docker exec streampark ls -la /root/.m2/repository/
```

**如果仓库为空**：
- 检查主机路径：`ls -la ~/.m2/repository/`
- 检查挂载配置是否正确
- 重启容器

### 3. Flink Home 验证失败

**检查 Flink 挂载**：
```bash
docker exec streampark ls -la /opt/flink/
docker exec streampark /opt/flink/bin/flink --version
```

**常见原因**：
- 主机 Flink 路径错误
- 容器内路径错误
- Flink 目录不完整

## 性能优化

### Maven 仓库大小

```bash
# 查看本地仓库大小
du -sh ~/.m2/repository/

# 清理不需要的依赖（可选）
mvn dependency:purge-local-repository
```

### 磁盘空间

```bash
# 查看 StreamPark 数据大小
du -sh ./streampark-data/

# 查看 Docker 磁盘使用
docker system df
```

## 最佳实践

### 开发环境

```yaml
volumes:
  # Flink：只读挂载，防止修改
  - /e/DataFiles/flink-1.17.0:/opt/flink:ro
  # Maven 仓库：读写挂载，共享依赖
  - ~/.m2/repository:/root/.m2/repository
  # 数据：读写挂载，持久化配置
  - ./streampark-data:/streampark/data
```

### 生产环境

**方案 1：使用挂载（推荐）**
- 优点：灵活，易于更新
- 缺点：依赖主机文件系统

**方案 2：打包到镜像**
```dockerfile
FROM apache/streampark:v2.1.5
COPY flink-1.17.0 /opt/flink
COPY .m2/repository /root/.m2/repository
```
- 优点：独立，可移植
- 缺点：镜像较大，更新不便

## 验证清单

- [x] StreamPark 容器健康运行
- [x] Flink 路径挂载成功（`/opt/flink`）
- [x] Maven 仓库挂载成功（`/root/.m2/repository`）
- [x] 数据目录挂载成功（`/streampark/data`）
- [x] Web UI 可访问（http://localhost:10000）
- [x] Flink Home 配置成功
- [ ] 添加 Flink 作业测试

## 下一步

1. ✅ StreamPark 部署完成
2. ✅ Flink Home 配置完成
3. ✅ Maven 仓库配置完成
4. 📝 添加 Flink 作业到 StreamPark
5. 🚀 通过 StreamPark 启动和管理作业
6. 📊 查看作业监控和日志

## 参考资料

- [StreamPark 官方文档](https://streampark.apache.org/zh-CN/docs/intro)
- [Docker Volume 文档](https://docs.docker.com/storage/volumes/)
- [Maven 本地仓库配置](https://maven.apache.org/guides/mini/guide-configuring-maven.html)

## 总结

通过挂载 Flink 和 Maven 本地仓库，实现了：

1. **Flink 共享**：主机和容器共享同一个 Flink，节省空间
2. **依赖复用**：使用本地 Maven 仓库，避免重复下载
3. **数据持久化**：StreamPark 配置和数据保存在本地
4. **网络连接**：可以访问 Kafka、Doris 等服务

StreamPark 现在已经完全配置好，可以开始管理 Flink 作业了！🎉
