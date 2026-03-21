# Flink 配置 - 修复 Docker 环境 Kafka 连接地址问题

**时间**: 2026-03-22 00:05  
**问题**: Flink 作业在 Docker 容器中运行时无法连接 Kafka  
**状态**: ✅ 已解决

---

## 问题描述

Flink 作业在 Docker 容器中运行时，日志显示无法连接 Kafka:

```
[AdminClient clientId=flink-ods-consumer-enumerator-admin-client] 
Connection to node 1 (localhost/127.0.0.1:9092) could not be established. 
Broker may not be available.
```

**问题现象**:
- Flink 作业持续尝试连接 `localhost:9092`
- 连接失败，提示 "Broker may not be available"
- Kafka 容器运行正常，但 Flink 无法访问

---

## 根本原因

**Docker 网络隔离问题** ⭐⭐⭐

1. **配置文件使用 localhost**:
   - `application-dev.yml` 中配置: `kafka.bootstrap-servers: localhost:9092`
   - 这个配置适用于本地 IDE 运行，但不适用于 Docker 容器

2. **容器内的 localhost 指向自己**:
   - 在 Docker 容器内部，`localhost` 指向容器自己
   - Flink 容器的 `localhost` 不是宿主机的 `localhost`
   - 因此无法访问宿主机或其他容器的服务

3. **Docker 网络通信方式**:
   - Docker 容器之间通过容器名称通信
   - Kafka 容器名称: `kafka`
   - Flink 需要使用 `kafka:9092` 而不是 `localhost:9092`

---

## 解决方案

### 修改配置文件

修改 `src/main/resources/config/application-dev.yml`，将所有 `localhost` 改为 Docker 容器名称:

**1. Kafka 配置**:
```yaml
# 修改前
kafka:
  bootstrap-servers: localhost:9092  # ❌ 本地地址

# 修改后
kafka:
  bootstrap-servers: kafka:9092  # ✅ Docker 容器名称
```

**2. Doris 配置**:
```yaml
# 修改前
doris:
  fe:
    http-url: http://127.0.0.1:8030
    jdbc-url: jdbc:mysql://127.0.0.1:9030
  be:
    nodes: "127.0.0.1:8040"

# 修改后
doris:
  fe:
    http-url: http://doris-fe:8030  # ✅ Docker 容器名称
    jdbc-url: jdbc:mysql://doris-fe:9030
  be:
    nodes: "doris-be:8040"
```

**3. Pushgateway 配置**:
```yaml
# 修改前
metrics:
  pushgateway:
    host: localhost  # ❌ 本地地址

# 修改后
metrics:
  pushgateway:
    host: pushgateway  # ✅ Docker 容器名称
```

### 清理旧容器

停止并删除旧的 TaskManager 容器（如果存在）:
```bash
docker stop flink-taskmanager
docker rm flink-taskmanager
```

### 重新编译和部署

```bash
# 1. 重新编译项目
mvn clean compile -DskipTests

# 2. 打包项目
mvn clean package -DskipTests

# 3. 通过 StreamPark 重新部署作业
# 访问 http://localhost:10000
# 上传新的 JAR 包并启动作业
```

---

## 验证结果

### 1. 检查 Flink 作业日志

```bash
# 查看 JobManager 日志
docker logs flink-jobmanager --tail 50

# 应该看到成功连接 Kafka 的日志
# ✅ Successfully connected to Kafka broker kafka:9092
```

### 2. 检查 Kafka 连接

```bash
# 从 Flink 容器内部测试 Kafka 连接
docker exec flink-jobmanager bash -c "nc -zv kafka 9092"

# 预期输出
# kafka (172.20.0.4:9092) open
```

### 3. 检查作业状态

访问 Flink Web UI: http://localhost:8081
- 作业应该处于 RUNNING 状态
- 没有 Kafka 连接错误

---

## 关键技术点

### 1. Docker 网络通信

**容器名称解析**:
- Docker Compose 自动为每个服务创建 DNS 记录
- 容器名称 = 服务名称（在 docker-compose.yml 中定义）
- 例如: `kafka` 容器可以通过 `kafka` 名称访问

**网络连接要求**:
- 两个容器必须在同一个 Docker 网络中
- Flink 容器已连接到 `data-warehouse_default` 网络
- Kafka 容器也在 `data-warehouse_default` 网络中
- 因此可以通过容器名称互相访问

### 2. localhost 的含义

**在不同环境中的含义**:
- **宿主机**: `localhost` 指向宿主机自己（127.0.0.1）
- **Docker 容器**: `localhost` 指向容器自己（容器内部的 127.0.0.1）
- **容器访问宿主机**: 使用 `host.docker.internal`（Windows/Mac）

**为什么不能用 localhost**:
```
┌─────────────────────────────────────────────────────────┐
│                    宿主机 (localhost)                    │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────────┐         ┌──────────────────┐     │
│  │  Flink 容器      │         │  Kafka 容器      │     │
│  │  localhost ❌    │────X────│  localhost ❌    │     │
│  │  (127.0.0.1)     │         │  (127.0.0.1)     │     │
│  └──────────────────┘         └──────────────────┘     │
│          │                             │               │
│          │                             │               │
│          └─────────────┬───────────────┘               │
│                        │                               │
│              ┌─────────┴──────────┐                    │
│              │  Docker 网络       │                    │
│              │  kafka ✅          │                    │
│              │  doris-fe ✅       │                    │
│              │  doris-be ✅       │                    │
│              └────────────────────┘                    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 3. 配置文件管理

**环境区分**:
- `application-dev.yml`: 开发环境（本地 IDE 运行）
- `application-docker.yml`: Docker 环境（容器运行）

**最佳实践**:
- 使用环境变量或配置文件区分不同环境
- Docker 环境使用容器名称
- 本地 IDE 运行使用 localhost
- 通过 `APP_ENV` 参数切换环境

**当前方案**:
- 将 `application-dev.yml` 改为 Docker 环境配置
- 因为主要通过 StreamPark 在 Docker 中运行
- 如果需要本地 IDE 运行，可以创建 `application-local.yml`

---

## 相关容器名称

| 服务 | 容器名称 | 端口 | 用途 |
|-----|---------|------|------|
| Kafka | `kafka` | 9092 | 消息队列 |
| Zookeeper | `zookeeper` | 2181 | Kafka 协调 |
| Doris FE | `doris-fe` | 8030, 9030 | 数据仓库前端 |
| Doris BE | `doris-be` | 8040, 9050 | 数据仓库后端 |
| Flink JobManager | `flink-jobmanager` | 8081, 6123 | Flink 主节点 |
| Flink TaskManager 1 | `flink-taskmanager-1` | - | Flink 工作节点 1 |
| Flink TaskManager 2 | `flink-taskmanager-2` | - | Flink 工作节点 2 |
| Pushgateway | `pushgateway` | 9091 | 监控指标推送 |
| Prometheus | `prometheus` | 9090 | 监控数据存储 |
| Grafana | `grafana` | 3000 | 监控可视化 |

---

## 后续优化建议

### 1. 创建多环境配置

创建 `application-local.yml` 用于本地 IDE 运行:
```yaml
kafka:
  bootstrap-servers: localhost:9092
doris:
  fe:
    http-url: http://localhost:8030
    jdbc-url: jdbc:mysql://localhost:9030
  be:
    nodes: "localhost:8040"
```

### 2. 使用环境变量

在 `application.yml` 中使用环境变量:
```yaml
kafka:
  bootstrap-servers: ${KAFKA_BOOTSTRAP_SERVERS:kafka:9092}
doris:
  fe:
    http-url: ${DORIS_FE_HTTP_URL:http://doris-fe:8030}
```

### 3. 配置文件选择

通过 Program Args 指定配置文件:
```bash
# Docker 环境
--APP_ENV docker

# 本地环境
--APP_ENV local
```

---

## 修改文件

- `src/main/resources/config/application-dev.yml` - 修改为 Docker 环境配置

---

## 参考资料

- [Docker 网络文档](https://docs.docker.com/network/)
- [Docker Compose 网络](https://docs.docker.com/compose/networking/)
- [Flink Kafka Connector](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/connectors/datastream/kafka/)

---

## 总结

**问题根源**: 配置文件使用 `localhost`，在 Docker 容器中无法访问其他容器

**解决方案**: 将所有 `localhost` 改为 Docker 容器名称（`kafka`, `doris-fe`, `doris-be`, `pushgateway`）

**关键经验**:
- Docker 容器之间通过容器名称通信
- `localhost` 在容器内部指向容器自己
- 配置文件需要区分本地和 Docker 环境
- 容器必须在同一个 Docker 网络中才能互相访问
