# Flink 集群部署 - 本地 Standalone 集群供 StreamPark 使用

## 问题描述

StreamPark 需要连接到 Flink 集群来提交和管理作业。需要在本地启动 Flink Standalone 集群，并配置网络让 Docker 容器中的 StreamPark 可以访问。

## 解决方案

### 1. 网络架构

```
┌─────────────────────────────────────────────────┐
│ 主机（Windows）                                  │
│                                                 │
│  ┌──────────────────┐      ┌─────────────────┐ │
│  │ Flink Cluster    │      │ StreamPark      │ │
│  │ (本地进程)        │◄─────│ (Docker 容器)   │ │
│  │                  │      │                 │ │
│  │ JobManager:6123  │      │ 通过             │ │
│  │ Web UI:8081      │      │ host.docker.    │ │
│  └──────────────────┘      │ internal 访问   │ │
│                            └─────────────────┘ │
└─────────────────────────────────────────────────┘
```

**关键点**：
- Flink 运行在主机上（本地进程）
- StreamPark 运行在 Docker 容器中
- Docker 容器通过 `host.docker.internal` 访问主机服务

### 2. Flink 配置

#### 配置文件：`flink-conf.yaml`

```yaml
# JobManager 配置 - 监听所有网络接口
jobmanager.rpc.address: 0.0.0.0
jobmanager.rpc.port: 6123
jobmanager.bind-host: 0.0.0.0
jobmanager.memory.process.size: 1600m

# TaskManager 配置
taskmanager.bind-host: 0.0.0.0
taskmanager.host: localhost
taskmanager.numberOfTaskSlots: 4
taskmanager.memory.process.size: 1728m

# Web UI 配置 - 监听所有网络接口
rest.address: 0.0.0.0
rest.bind-address: 0.0.0.0
rest.port: 8081

# 并行度配置
parallelism.default: 2

# Checkpoint 配置
execution.checkpointing.interval: 60000
execution.checkpointing.mode: EXACTLY_ONCE
state.backend: rocksdb
state.checkpoints.dir: file:///tmp/flink-checkpoints

# 重启策略
restart-strategy: fixed-delay
restart-strategy.fixed-delay.attempts: 3
restart-strategy.fixed-delay.delay: 10s
```

**关键配置说明**：

1. **`jobmanager.rpc.address: 0.0.0.0`**
   - 监听所有网络接口
   - 允许 Docker 容器访问

2. **`rest.address: 0.0.0.0`**
   - Web UI 监听所有网络接口
   - 允许从任何地址访问

3. **`taskmanager.host: localhost`**
   - TaskManager 注册地址
   - 使用 localhost 避免网络问题

### 3. 启动 Flink 集群

#### 使用启动脚本

```bash
# 启动 Flink 集群
./start-flink-cluster.sh
```

**脚本功能**：
1. 检查 Flink 是否已安装
2. 备份原始配置文件
3. 创建适合 StreamPark 访问的配置
4. 启动 Flink JobManager 和 TaskManager
5. 验证启动状态

#### 手动启动

```bash
# 进入 Flink 目录
cd /e/DataFiles/flink-1.17.0

# 启动集群
./bin/start-cluster.sh

# 检查进程
jps | grep -E "StandaloneSessionClusterEntrypoint|TaskManagerRunner"

# 访问 Web UI
open http://localhost:8081
```

### 4. 验证 Flink 集群

#### 检查进程

```bash
# 查看 Java 进程
jps

# 应该看到：
# StandaloneSessionClusterEntrypoint (JobManager)
# TaskManagerRunner (TaskManager)
```

#### 检查 Web UI

```bash
# 访问 Flink Web UI
curl http://localhost:8081

# 或在浏览器中打开
open http://localhost:8081
```

#### 检查日志

```bash
# JobManager 日志
tail -f /e/DataFiles/flink-1.17.0/log/flink-*-standalonesession-*.log

# TaskManager 日志
tail -f /e/DataFiles/flink-1.17.0/log/flink-*-taskexecutor-*.log
```

### 5. StreamPark 配置

#### 在 StreamPark Web UI 中配置

1. **登录 StreamPark**：http://localhost:10000

2. **添加 Flink 集群**：
   - 进入"设置中心" → "集群管理"
   - 点击"添加集群"
   - 填写配置：

```yaml
集群名称: Local Flink Cluster
集群类型: Standalone
JobManager 地址: host.docker.internal:6123
JobManager Web UI: http://host.docker.internal:8081
描述: 本地 Flink Standalone 集群
```

**关键点**：
- 使用 `host.docker.internal` 而不是 `localhost`
- `host.docker.internal` 是 Docker 提供的特殊域名，指向主机

#### 测试连接

在 StreamPark 中：
1. 点击"测试连接"按钮
2. 如果显示绿色勾号，说明连接成功
3. 可以看到 TaskManager 数量和可用 Slot

### 6. 网络配置详解

#### Docker 访问主机服务

**Windows/Mac Docker Desktop**：
- 使用 `host.docker.internal`
- 自动解析为主机 IP

**Linux Docker**：
- 需要使用 `--add-host=host.docker.internal:host-gateway`
- 或使用主机实际 IP 地址

#### 端口映射

| 服务 | 主机端口 | Docker 访问地址 | 说明 |
|-----|---------|----------------|------|
| Flink JobManager RPC | 6123 | host.docker.internal:6123 | 作业提交 |
| Flink Web UI | 8081 | http://host.docker.internal:8081 | 监控界面 |
| StreamPark Web UI | 10000 | http://localhost:10000 | 管理界面 |

### 7. 常用命令

#### 启动和停止

```bash
# 启动 Flink 集群
./start-flink-cluster.sh

# 停止 Flink 集群
./stop-flink-cluster.sh

# 重启 Flink 集群
./stop-flink-cluster.sh && ./start-flink-cluster.sh
```

#### 查看状态

```bash
# 查看 Flink 进程
jps | grep -E "Standalone|TaskManager"

# 查看端口占用
netstat -ano | grep -E "6123|8081"

# 查看 TaskManager 数量
curl -s http://localhost:8081/taskmanagers | jq '.taskmanagers | length'
```

#### 提交作业（测试）

```bash
# 提交示例作业
/e/DataFiles/flink-1.17.0/bin/flink run \
  /e/DataFiles/flink-1.17.0/examples/streaming/WordCount.jar

# 查看作业列表
/e/DataFiles/flink-1.17.0/bin/flink list

# 取消作业
/e/DataFiles/flink-1.17.0/bin/flink cancel <job-id>
```

## 常见问题

### 1. StreamPark 无法连接 Flink

**检查项**：
```bash
# 1. 检查 Flink 是否运行
jps | grep Standalone

# 2. 检查端口是否监听
netstat -ano | grep 6123

# 3. 测试连接
curl http://localhost:8081

# 4. 从 Docker 容器测试
docker exec streampark curl http://host.docker.internal:8081
```

**解决方案**：
- 确保 Flink 配置中 `jobmanager.rpc.address: 0.0.0.0`
- 确保 Flink 配置中 `rest.address: 0.0.0.0`
- 重启 Flink 集群

### 2. 端口冲突

**现象**：
```
Address already in use: bind
```

**解决方案**：
```bash
# 查找占用端口的进程
netstat -ano | grep 8081

# 杀死进程（Windows）
taskkill /PID <pid> /F

# 或修改 Flink 配置使用其他端口
rest.port: 8082
```

### 3. TaskManager 无法连接 JobManager

**检查日志**：
```bash
tail -f /e/DataFiles/flink-1.17.0/log/flink-*-taskexecutor-*.log
```

**常见原因**：
- JobManager 地址配置错误
- 防火墙阻止连接
- 网络配置问题

**解决方案**：
```yaml
# 确保配置正确
jobmanager.rpc.address: 0.0.0.0
taskmanager.host: localhost
```

### 4. 内存不足

**现象**：
```
Insufficient number of network buffers
```

**解决方案**：
```yaml
# 增加内存配置
jobmanager.memory.process.size: 2048m
taskmanager.memory.process.size: 2048m
```

## 性能优化

### 1. TaskManager 配置

```yaml
# 增加 Task Slot 数量（根据 CPU 核心数）
taskmanager.numberOfTaskSlots: 8

# 增加内存
taskmanager.memory.process.size: 4096m
```

### 2. 并行度配置

```yaml
# 默认并行度（根据 Task Slot 数量）
parallelism.default: 4
```

### 3. Checkpoint 优化

```yaml
# Checkpoint 间隔（毫秒）
execution.checkpointing.interval: 30000

# Checkpoint 超时
execution.checkpointing.timeout: 600000

# 最小间隔
execution.checkpointing.min-pause: 10000
```

## 部署模式对比

### Standalone 模式（当前）

**优点**：
- 简单易用，快速启动
- 适合开发和测试
- 资源独占，性能稳定

**缺点**：
- 资源固定，不能动态扩展
- 需要手动管理
- 单点故障

### Yarn 模式

**优点**：
- 资源动态分配
- 高可用
- 适合生产环境

**缺点**：
- 需要 Hadoop 集群
- 配置复杂

### Kubernetes 模式

**优点**：
- 云原生，易于扩展
- 容器化部署
- 自动故障恢复

**缺点**：
- 需要 K8s 集群
- 学习成本高

## 最佳实践

### 开发环境

```yaml
# 使用 Standalone 模式
# 配置较小的资源
jobmanager.memory.process.size: 1600m
taskmanager.memory.process.size: 1728m
taskmanager.numberOfTaskSlots: 4
```

### 测试环境

```yaml
# 使用 Standalone 模式
# 配置中等资源
jobmanager.memory.process.size: 2048m
taskmanager.memory.process.size: 2048m
taskmanager.numberOfTaskSlots: 8
```

### 生产环境

```yaml
# 使用 Yarn 或 Kubernetes 模式
# 配置高可用
high-availability: zookeeper
high-availability.storageDir: hdfs:///flink/ha/
```

## 监控和运维

### 1. 监控指标

访问 Flink Web UI：http://localhost:8081

**关键指标**：
- TaskManager 数量
- 可用 Task Slot
- 运行中的作业数量
- CPU 和内存使用率

### 2. 日志管理

```bash
# 日志位置
/e/DataFiles/flink-1.17.0/log/

# 日志文件
flink-*-standalonesession-*.log  # JobManager
flink-*-taskexecutor-*.log       # TaskManager
```

### 3. 备份和恢复

```bash
# 备份 Checkpoint
cp -r /tmp/flink-checkpoints /backup/

# 恢复作业
flink run -s /backup/flink-checkpoints/<checkpoint-id> app.jar
```

## 下一步

1. ✅ Flink 集群启动成功
2. ✅ StreamPark 可以访问 Flink
3. 📝 在 StreamPark 中添加 Flink 作业
4. 🚀 通过 StreamPark 提交作业到 Flink 集群
5. 📊 监控作业运行状态

## 参考资料

- [Flink Standalone 部署](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/resource-providers/standalone/)
- [StreamPark 集群管理](https://streampark.apache.org/zh-CN/docs/user-guide/cluster)
- [Docker 网络配置](https://docs.docker.com/desktop/networking/)

## 总结

通过启动本地 Flink Standalone 集群，并配置网络让 StreamPark 可以访问，实现了：

1. **Flink 集群运行**：JobManager + TaskManager
2. **网络连通**：Docker 容器通过 `host.docker.internal` 访问主机
3. **StreamPark 集成**：可以提交和管理 Flink 作业

现在可以在 StreamPark 中管理 Flink 作业了！🎉
