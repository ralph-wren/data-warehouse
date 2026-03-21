# Flink 集群 Docker 部署 - 解决 Windows 环境启动问题

## 问题描述

在 Windows 环境下尝试启动本地 Flink Standalone 集群时遇到问题：
```
错误: 找不到或无法加载主类 org.apache.flink.client.cli.CliFrontend
```

## 问题原因

1. **Flink 发行版类型错误**：下载的是 Linux 版本的 Flink，不适合在 Windows 上直接运行
2. **Java 版本问题**：系统默认 Java 21，但 Flink 1.17 需要 Java 8 或 11
3. **路径转换问题**：Windows 环境下的路径在 Git Bash 中转换可能有问题
4. **脚本兼容性**：Flink 的 bash 脚本在 Windows 上运行不稳定

## 解决方案：使用 Docker 运行 Flink 集群 ✅

### 1. Docker Compose 配置

创建 `docker-compose-flink.yml`：

```yaml
version: '3.8'

services:
  jobmanager:
    image: flink:1.17.0-scala_2.12-java8
    container_name: flink-jobmanager
    hostname: jobmanager
    ports:
      - "8081:8081"  # Web UI
      - "6123:6123"  # RPC
    command: jobmanager
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: jobmanager
        taskmanager.numberOfTaskSlots: 4
        parallelism.default: 2
    networks:
      - flink-net
      - default  # 连接到 Kafka 网络
    restart: unless-stopped

  taskmanager:
    image: flink:1.17.0-scala_2.12-java8
    container_name: flink-taskmanager
    hostname: taskmanager
    depends_on:
      - jobmanager
    command: taskmanager
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.rpc.address: jobmanager
        taskmanager.numberOfTaskSlots: 4
        parallelism.default: 2
    networks:
      - flink-net
      - default  # 连接到 Kafka 网络
    restart: unless-stopped

networks:
  flink-net:
    driver: bridge
  default:
    external: true
    name: data-warehouse_default
```

### 2. 启动 Flink 集群

```bash
# 使用启动脚本
./start-flink-docker.sh

# 或手动启动
docker-compose -f docker-compose-flink.yml up -d
```

### 3. 验证 Flink 集群

```bash
# 检查容器状态
docker ps | grep flink

# 访问 Web UI
curl http://localhost:8081

# 或在浏览器中打开
open http://localhost:8081

# 查看日志
docker logs flink-jobmanager
docker logs flink-taskmanager
```

### 4. StreamPark 配置

#### 在 StreamPark Web UI 中添加集群

1. **登录 StreamPark**：http://localhost:10000

2. **添加 Flink 集群**：
   - 进入"设置中心" → "集群管理"
   - 点击"添加集群"
   - 填写配置：

```yaml
集群名称: Flink Docker Cluster
集群类型: Standalone
JobManager 地址: jobmanager:6123
JobManager Web UI: http://jobmanager:8081
描述: Docker 运行的 Flink Standalone 集群
```

**关键点**：
- 使用容器名称 `jobmanager` 而不是 `localhost`
- StreamPark 和 Flink 在同一个 Docker 网络中，可以直接通过容器名访问

## 网络架构

```
┌─────────────────────────────────────────────────────────┐
│ Docker 网络                                              │
│                                                         │
│  ┌──────────────────┐      ┌─────────────────────────┐ │
│  │ Flink JobManager │◄─────│ StreamPark              │ │
│  │ (容器)            │      │ (容器)                   │ │
│  │                  │      │                         │ │
│  │ jobmanager:6123  │      │ 通过容器名访问           │ │
│  │ Web UI:8081      │      │ jobmanager:6123         │ │
│  └──────────────────┘      └─────────────────────────┘ │
│           ▲                                             │
│           │                                             │
│  ┌────────┴─────────┐                                  │
│  │ Flink TaskManager│                                  │
│  │ (容器)            │                                  │
│  └──────────────────┘                                  │
│                                                         │
│  ┌──────────────────┐                                  │
│  │ Kafka            │                                  │
│  │ (容器)            │                                  │
│  └──────────────────┘                                  │
└─────────────────────────────────────────────────────────┘
         │
         │ 端口映射
         ▼
    主机 (Windows)
    - http://localhost:8081 (Flink Web UI)
    - localhost:6123 (JobManager RPC)
```

## 优势对比

### Docker 方式（推荐）✅

**优点**：
- 跨平台，Windows/Mac/Linux 都可以运行
- 环境隔离，不影响主机
- 配置简单，一键启动
- 与 StreamPark 在同一网络，连接稳定
- 官方镜像，经过充分测试

**缺点**：
- 需要 Docker 环境
- 资源开销略大（但可以接受）

### 本地方式（不推荐）❌

**优点**：
- 不需要 Docker
- 资源开销小

**缺点**：
- Windows 环境兼容性差
- 需要配置 Java 环境
- 路径问题复杂
- 脚本可能不稳定
- 需要下载 Windows 版本的 Flink

## 常用命令

### 启动和停止

```bash
# 启动 Flink 集群
./start-flink-docker.sh

# 停止 Flink 集群
./stop-flink-docker.sh

# 重启 Flink 集群
./stop-flink-docker.sh && ./start-flink-docker.sh
```

### 查看状态

```bash
# 查看容器状态
docker ps | grep flink

# 查看 JobManager 日志
docker logs -f flink-jobmanager

# 查看 TaskManager 日志
docker logs -f flink-taskmanager

# 查看 TaskManager 数量
curl -s http://localhost:8081/taskmanagers | jq '.taskmanagers | length'
```

### 进入容器

```bash
# 进入 JobManager 容器
docker exec -it flink-jobmanager bash

# 进入 TaskManager 容器
docker exec -it flink-taskmanager bash

# 在容器内查看 Flink 版本
docker exec flink-jobmanager flink --version
```

## 配置调优

### 增加 TaskManager 数量

修改 `docker-compose-flink.yml`：

```yaml
services:
  taskmanager:
    # ... 其他配置
    deploy:
      replicas: 2  # 启动 2 个 TaskManager
```

或手动启动多个：

```bash
docker-compose -f docker-compose-flink.yml up -d --scale taskmanager=2
```

### 调整资源配置

```yaml
services:
  jobmanager:
    environment:
      - |
        FLINK_PROPERTIES=
        jobmanager.memory.process.size: 2048m
        
  taskmanager:
    environment:
      - |
        FLINK_PROPERTIES=
        taskmanager.memory.process.size: 2048m
        taskmanager.numberOfTaskSlots: 8
```

### 持久化配置

挂载配置文件：

```yaml
services:
  jobmanager:
    volumes:
      - ./flink-conf.yaml:/opt/flink/conf/flink-conf.yaml:ro
```

## 故障排查

### 1. 容器无法启动

```bash
# 查看日志
docker logs flink-jobmanager
docker logs flink-taskmanager

# 检查网络
docker network inspect data-warehouse_flink-net
```

### 2. TaskManager 无法连接 JobManager

```bash
# 检查 JobManager 是否运行
docker ps | grep jobmanager

# 检查网络连接
docker exec flink-taskmanager ping jobmanager

# 查看 TaskManager 日志
docker logs flink-taskmanager
```

### 3. Web UI 无法访问

```bash
# 检查端口映射
docker ps | grep flink-jobmanager

# 测试连接
curl http://localhost:8081

# 检查防火墙
netstat -ano | grep 8081
```

### 4. 端口冲突

```bash
# 查找占用端口的进程
netstat -ano | grep 8081

# 修改端口映射
# 在 docker-compose-flink.yml 中修改：
ports:
  - "8082:8081"  # 使用 8082 端口
```

## 与其他服务集成

### 连接 Kafka

Flink 和 Kafka 在同一个 Docker 网络中：

```java
// Flink 作业中使用 Kafka
properties.setProperty("bootstrap.servers", "kafka:9092");
```

### 连接 Doris

需要将 Flink 连接到 Doris 网络：

```yaml
services:
  jobmanager:
    networks:
      - flink-net
      - doris-net  # 添加 Doris 网络
      
networks:
  doris-net:
    external: true
    name: data-warehouse_doris-net
```

然后在 Flink 作业中：

```java
// 使用 Doris
dorisOptions.setFenodes("doris-fe:8030");
```

## 监控和运维

### 1. 资源监控

访问 Flink Web UI：http://localhost:8081

**关键指标**：
- Available Task Slots
- Running Jobs
- CPU Load
- Memory Usage

### 2. 日志管理

```bash
# 实时查看日志
docker logs -f flink-jobmanager
docker logs -f flink-taskmanager

# 导出日志
docker logs flink-jobmanager > jobmanager.log
docker logs flink-taskmanager > taskmanager.log
```

### 3. 备份和恢复

```bash
# 备份 Checkpoint（如果配置了持久化）
docker cp flink-jobmanager:/tmp/flink-checkpoints ./backup/

# 恢复作业
flink run -s /path/to/checkpoint app.jar
```

## 最佳实践

### 开发环境

```yaml
# 使用较小的资源配置
taskmanager.numberOfTaskSlots: 4
parallelism.default: 2
```

### 生产环境

```yaml
# 使用高可用配置
high-availability: zookeeper
high-availability.storageDir: hdfs:///flink/ha/

# 增加资源
taskmanager.numberOfTaskSlots: 8
taskmanager.memory.process.size: 4096m
```

## 下一步

1. ✅ Flink Docker 集群启动成功
2. ✅ Web UI 可访问（http://localhost:8081）
3. 📝 在 StreamPark 中配置 Flink 集群
4. 🚀 通过 StreamPark 提交作业到 Flink
5. 📊 监控作业运行状态

## 参考资料

- [Flink Docker 官方文档](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/resource-providers/standalone/docker/)
- [Flink Docker Hub](https://hub.docker.com/_/flink)
- [StreamPark 集群管理](https://streampark.apache.org/zh-CN/docs/user-guide/cluster)

## 总结

通过使用 Docker 运行 Flink 集群，成功解决了 Windows 环境下的启动问题：

1. **跨平台兼容**：Docker 镜像在任何平台都可以运行
2. **配置简单**：一键启动，无需复杂配置
3. **网络互通**：与 StreamPark、Kafka、Doris 在同一网络
4. **稳定可靠**：官方镜像，经过充分测试

现在可以在 StreamPark 中管理 Flink 作业了！🎉
