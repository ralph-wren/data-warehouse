# StreamPark 部署 - Docker 方式部署 Flink 作业管理平台

## 问题背景

需要一个统一的平台来管理和监控 Flink 作业，StreamPark 是 Apache 顶级项目，提供了完整的 Flink 作业生命周期管理功能。

## 解决方案

使用 Docker Compose 部署 StreamPark v2.1.5 版本，并配置与现有 Kafka、Doris 等服务的网络连接。

## 部署步骤

### 1. 创建 Docker Compose 配置

创建 `docker-compose-streampark.yml` 文件：

```yaml
version: '3.8'

services:
  streampark:
    image: apache/streampark:v2.1.5  # 注意：需要加 v 前缀
    container_name: streampark
    hostname: streampark
    ports:
      - "10000:10000"  # StreamPark Web UI
    environment:
      - TZ=Asia/Shanghai  # 中国时区
      - DATASOURCE_DIALECT=h2  # 使用 H2 内置数据库
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock  # Docker 管理
      - /etc/hosts:/etc/hosts:ro
      - streampark-data:/streampark  # 数据持久化
    privileged: true
    restart: unless-stopped
    networks:
      - streampark-net
      - doris-net  # 连接到 Doris 网络
      - default    # 连接到默认网络（Kafka）
```

### 2. 网络配置说明

StreamPark 需要连接到多个网络：
- `streampark-net`: StreamPark 自己的网络
- `doris-net`: 连接到 Doris 数据库（外部网络）
- `default`: 连接到 Kafka 等服务（外部网络）

### 3. 启动服务

使用提供的脚本启动：

```bash
./start-streampark.sh
```

或手动启动：

```bash
docker-compose -f docker-compose-streampark.yml up -d
```

### 4. 访问 StreamPark

- 访问地址: http://localhost:10000
- 默认账号: `admin`
- 默认密码: `streampark`

## 关键配置说明

### 镜像版本

- 使用 `apache/streampark:v2.1.5`（注意 v 前缀）
- 这是 StreamPark 成为 Apache 顶级项目后的最新稳定版本

### 数据库配置

默认使用 H2 内置数据库，适合快速部署和测试。

如需使用 MySQL，可以修改环境变量：

```yaml
environment:
  - DATASOURCE_DIALECT=mysql
  - DATASOURCE_URL=jdbc:mysql://host.docker.internal:3306/streampark?...
  - DATASOURCE_USERNAME=root
  - DATASOURCE_PASSWORD=your_password
```

### 数据持久化

- StreamPark 数据存储在 Docker Volume: `streampark-data`
- 包括作业配置、历史记录等

## 功能特性

StreamPark 提供以下功能：

1. **作业开发**: 支持 Flink SQL 和 DataStream API
2. **作业部署**: 支持多种部署模式（Local、Yarn、Kubernetes）
3. **作业监控**: 实时监控作业运行状态
4. **作业管理**: 启动、停止、重启作业
5. **Savepoint 管理**: 管理作业的 Savepoint
6. **配置管理**: 统一管理 Flink 配置
7. **告警通知**: 支持多种告警方式

## 常用命令

### 查看日志

```bash
docker logs -f streampark
```

### 停止服务

```bash
./stop-streampark.sh
# 或
docker-compose -f docker-compose-streampark.yml down
```

### 删除数据（重置）

```bash
docker-compose -f docker-compose-streampark.yml down -v
```

### 重启服务

```bash
docker-compose -f docker-compose-streampark.yml restart
```

## 与现有项目集成

### 1. 配置 Flink Home

在 StreamPark 中配置 Flink 安装路径，可以使用容器内的 Flink 或挂载外部 Flink。

### 2. 配置 Kafka 连接

StreamPark 可以通过 Docker 网络直接访问 Kafka：
- Kafka 地址: `kafka:9092`（容器内网络）
- 或使用宿主机地址: `host.docker.internal:9092`

### 3. 配置 Doris 连接

StreamPark 可以访问 Doris：
- FE 地址: `doris-fe:9030`
- BE 地址: `doris-be:8040`

## 故障排查

### 1. 容器无法启动

检查日志：
```bash
docker logs streampark
```

### 2. 网络连接问题

检查网络配置：
```bash
docker network inspect data-warehouse_doris-net
docker network inspect data-warehouse_default
```

### 3. 端口冲突

确保 10000 端口未被占用：
```bash
netstat -ano | grep 10000
```

## 下一步

1. 登录 StreamPark Web UI
2. 配置 Flink Home
3. 添加现有的 Flink 作业
4. 配置作业监控和告警

## 参考资料

- [StreamPark 官方文档](https://streampark.apache.org/zh-CN/docs/get-started/installation-docker)
- [StreamPark GitHub](https://github.com/apache/streampark)
- [Docker Hub - apache/streampark](https://hub.docker.com/r/apache/streampark)

## 总结

通过 Docker Compose 成功部署了 StreamPark v2.1.5，提供了统一的 Flink 作业管理平台。StreamPark 已经与现有的 Kafka、Doris 等服务网络打通，可以方便地管理和监控 Flink 作业。
