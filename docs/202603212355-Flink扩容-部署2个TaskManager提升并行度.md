# Flink 扩容 - 部署 2 个 TaskManager 提升并行度

**时间**: 2026-03-21 23:55  
**问题**: Flink 集群只有 1 个 TaskManager，需要扩展到 2 个以提升并行处理能力  
**状态**: ✅ 已解决

---

## 问题描述

用户要求将 Flink TaskManager 从 1 个扩展到 2 个，以提升集群的并行处理能力和资源利用率。

---

## 解决方案

### 1. 修改 Docker Compose 配置

修改 `docker-compose-flink.yml`，将原来的单个 `taskmanager` 服务拆分为 `taskmanager-1` 和 `taskmanager-2`:

```yaml
# TaskManager 1
taskmanager-1:
  image: flink:1.17.0-scala_2.12-java11
  container_name: flink-taskmanager-1
  hostname: taskmanager-1
  depends_on:
    - jobmanager
  command: taskmanager
  environment:
    - |
      FLINK_PROPERTIES=
      jobmanager.rpc.address: jobmanager
      taskmanager.numberOfTaskSlots: 4
      parallelism.default: 4
  networks:
    - flink-net
    - default
    - doris-net
  restart: unless-stopped

# TaskManager 2
taskmanager-2:
  image: flink:1.17.0-scala_2.12-java11
  container_name: flink-taskmanager-2
  hostname: taskmanager-2
  depends_on:
    - jobmanager
  command: taskmanager
  environment:
    - |
      FLINK_PROPERTIES=
      jobmanager.rpc.address: jobmanager
      taskmanager.numberOfTaskSlots: 4
      parallelism.default: 4
  networks:
    - flink-net
    - default
    - doris-net
  restart: unless-stopped
```

### 2. 修复管理脚本

修复 `manage-flink.sh` 中的 `$0` 问题（Git Bash 兼容性）:

```bash
# 修改前
restart)
    echo "重启 Flink 集群..."
    $0 stop
    sleep 3
    $0 start
    ;;

# 修改后
restart)
    echo "重启 Flink 集群..."
    bash manage-flink.sh stop
    sleep 3
    bash manage-flink.sh start
    ;;
```

同时更新验证部分，检查 2 个 TaskManager 的 Java 版本:

```bash
echo "TaskManager-1:"
docker exec flink-taskmanager-1 java -version 2>&1 | head -1
echo "TaskManager-2:"
docker exec flink-taskmanager-2 java -version 2>&1 | head -1
```

### 3. 重启 Flink 集群

```bash
bash manage-flink.sh restart
```

---

## 验证结果

### 容器状态

```bash
$ docker ps | grep flink
flink-jobmanager      flink:1.17.0-scala_2.12-java11    Up 14 seconds    0.0.0.0:6123->6123/tcp, 0.0.0.0:8081->8081/tcp
flink-taskmanager-1   flink:1.17.0-scala_2.12-java11    Up 13 seconds    6123/tcp, 8081/tcp
flink-taskmanager-2   flink:1.17.0-scala_2.12-java11    Up 14 seconds    6123/tcp, 8081/tcp
```

### REST API 验证

```bash
$ curl -s http://localhost:8081/taskmanagers | python -m json.tool
{
    "taskmanagers": [
        {
            "id": "172.21.0.5:43835-896d71",
            "slotsNumber": 4,
            "freeSlots": 4,
            ...
        },
        {
            "id": "172.25.0.7:38487-6c4b00",
            "slotsNumber": 4,
            "freeSlots": 4,
            ...
        }
    ]
}
```

### 集群资源统计

- **TaskManager 数量**: 2 个 ✅
- **每个 TaskManager Slot 数**: 4 个
- **总 Slot 数**: 8 个 (4 × 2) ✅
- **可用 Slot 数**: 8 个 (全部空闲)
- **总 CPU 核心**: 8 个 (4 × 2)
- **总内存**: 约 3.4 GB (1.7 GB × 2)

---

## 关键技术点

### 1. Docker Compose 服务命名

- 每个 TaskManager 需要独立的服务名称（`taskmanager-1`, `taskmanager-2`）
- 容器名称也需要唯一（`flink-taskmanager-1`, `flink-taskmanager-2`）
- hostname 设置为服务名，便于网络通信

### 2. 配置一致性

- 所有 TaskManager 使用相同的配置（Slot 数、并行度、网络等）
- 都需要连接到相同的 JobManager (`jobmanager.rpc.address: jobmanager`)
- 都需要加入相同的网络（`flink-net`, `default`, `doris-net`）

### 3. Git Bash 兼容性

- 避免使用 `$0` 变量，在 Git Bash 中无法正确解析
- 改用 `bash script-name.sh` 显式调用脚本

### 4. 资源分配

- 每个 TaskManager 默认 4 个 Slot
- 2 个 TaskManager 提供 8 个并行度
- 可以运行更多并行任务或更高并行度的作业

---

## 优势

1. **提升并行度**: 从 4 个 Slot 增加到 8 个 Slot
2. **高可用性**: 一个 TaskManager 故障不会影响另一个
3. **资源隔离**: 不同任务可以分配到不同的 TaskManager
4. **负载均衡**: JobManager 自动在 2 个 TaskManager 之间分配任务

---

## 后续优化建议

1. **动态扩容**: 可以根据负载动态增加 TaskManager 数量
2. **资源配置**: 可以为不同 TaskManager 配置不同的资源（CPU、内存）
3. **监控告警**: 在 Grafana 中添加 TaskManager 数量监控
4. **Slot 调整**: 根据实际任务需求调整每个 TaskManager 的 Slot 数

---

## 相关文件

- `docker-compose-flink.yml` - Flink 集群配置
- `manage-flink.sh` - Flink 管理脚本

---

## 参考资料

- [Flink TaskManager 配置文档](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/config/#taskmanager)
- [Flink Slot 和并行度](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/dev/datastream/execution/parallel/)
