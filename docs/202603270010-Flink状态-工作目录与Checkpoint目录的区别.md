# Flink 状态 - 工作目录与 Checkpoint 目录的区别

## 问题描述

用户疑问: 为什么日志显示的 RocksDB 状态后端位置和配置的 `checkpoint-dir` 不一样?

```
日志显示:
Finished building RocksDB keyed state-backend at 
C:\Users\ralph\AppData\Local\Temp\minicluster_xxx\tm_0\tmp\job_xxx_op_xxx

配置文件:
checkpoint-dir: file:///c/flink-state/checkpoints
```

## 核心概念

Flink 状态后端有**两个不同的目录**,它们的用途完全不同:

### 1. 工作目录 (Working Directory) ⭐⭐⭐

**路径**: `C:\Users\ralph\AppData\Local\Temp\minicluster_xxx\tm_0\tmp\...`

**用途**:
- RocksDB 的**运行时工作目录**
- 存储正在使用的状态数据
- 包含 SST 文件、WAL 日志、Memtable 等

**特点**:
- 由 Flink 自动管理
- 使用系统临时目录 (`java.io.tmpdir`)
- **不受** `checkpoint-dir` 配置控制
- 作业运行期间一直存在
- 作业停止后可能被清理

**配置方式**:
```yaml
# 无法通过 checkpoint-dir 配置
# 由 Flink 自动选择临时目录
```

或通过 JVM 参数:
```bash
-Djava.io.tmpdir=/c/flink-temp
```

### 2. Checkpoint 目录 (Checkpoint Directory) ⭐⭐⭐

**路径**: `C:\flink-state\checkpoints`

**用途**:
- 存储 **Checkpoint 快照**
- 持久化状态数据
- 用于故障恢复

**特点**:
- 由用户配置
- **受** `checkpoint-dir` 配置控制
- 只在触发 Checkpoint 时写入
- 作业停止后仍然保留
- 用于故障恢复和状态迁移

**配置方式**:
```yaml
flink:
  state:
    checkpoint-dir: file:///c/flink-state/checkpoints
```

## 工作流程

```
┌─────────────────────────────────────────────────────────────┐
│                    Flink 状态管理流程                        │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  1. 运行时状态 (工作目录)                                    │
│  ┌──────────────────────────────────────────┐              │
│  │  C:\Users\...\Temp\minicluster_xxx\      │              │
│  │  ├─ SST 文件 (持久化的键值对)            │              │
│  │  ├─ WAL 日志 (写前日志)                  │              │
│  │  ├─ Memtable (内存中的写缓冲)            │              │
│  │  └─ Block Cache (读缓存)                 │              │
│  └──────────────────────────────────────────┘              │
│         │                                                    │
│         │ 触发 Checkpoint                                   │
│         ↓                                                    │
│  2. Checkpoint 快照 (Checkpoint 目录)                       │
│  ┌──────────────────────────────────────────┐              │
│  │  C:\flink-state\checkpoints\             │              │
│  │  ├─ chk-1/ (第 1 次 Checkpoint)          │              │
│  │  │  ├─ _metadata                         │              │
│  │  │  └─ state-files/                      │              │
│  │  ├─ chk-2/ (第 2 次 Checkpoint)          │              │
│  │  └─ chk-3/ (第 3 次 Checkpoint)          │              │
│  └──────────────────────────────────────────┘              │
│         │                                                    │
│         │ 故障恢复                                          │
│         ↓                                                    │
│  3. 从 Checkpoint 恢复到工作目录                            │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## 详细对比

| 特性 | 工作目录 | Checkpoint 目录 |
|------|---------|----------------|
| **路径** | 系统临时目录 | 用户配置路径 |
| **用途** | 运行时状态存储 | 持久化快照存储 |
| **配置** | 自动管理 | `checkpoint-dir` |
| **写入时机** | 持续写入 | Checkpoint 时写入 |
| **生命周期** | 作业运行期间 | 永久保留 |
| **大小** | 较大 (完整状态) | 较小 (增量快照) |
| **性能要求** | 高 (频繁读写) | 中 (定期写入) |
| **故障恢复** | 不可用 | 可用 |

## 为什么这样设计?

### 1. 性能优化

**工作目录**:
- 使用本地磁盘,读写速度快
- 适合频繁的状态更新操作
- 不需要网络传输

**Checkpoint 目录**:
- 可以使用远程存储 (HDFS、S3 等)
- 只在 Checkpoint 时写入,频率低
- 支持跨节点共享

### 2. 故障恢复

**工作目录**:
- 节点故障时数据丢失
- 不能用于恢复

**Checkpoint 目录**:
- 持久化存储,节点故障不影响
- 可以从任意 Checkpoint 恢复

### 3. 存储成本

**工作目录**:
- 存储完整状态,占用空间大
- 使用本地磁盘,成本低

**Checkpoint 目录**:
- 增量快照,占用空间小
- 可以使用廉价的对象存储

## 如何验证配置

### 1. 检查工作目录

```bash
# 查看日志中的工作目录路径
tail -f logs/app/flink-app.log | grep "RocksDB keyed state-backend"

# 输出示例:
# Finished building RocksDB keyed state-backend at C:\Users\...\Temp\...
```

### 2. 检查 Checkpoint 目录

```bash
# 查看 Checkpoint 目录
ls -la /c/flink-state/checkpoints/

# 成功的 Checkpoint 会创建子目录:
# drwxr-xr-x  chk-1/
# drwxr-xr-x  chk-2/
# drwxr-xr-x  chk-3/
```

### 3. 查看 Checkpoint 日志

```bash
# 查看 Checkpoint 完成日志
tail -f logs/app/flink-app.log | grep -i checkpoint

# 成功的输出:
# Completed checkpoint 1 for job xxx (12345 bytes in 1234 ms)
```

## 常见问题

### Q1: 为什么 Checkpoint 目录是空的?

**原因**:
1. Checkpoint 还没有成功完成
2. Checkpoint 间隔时间还没到
3. Checkpoint 失败 (查看错误日志)

**解决**:
```bash
# 查看 Checkpoint 状态
tail -f logs/app/flink-app.log | grep -i checkpoint

# 检查是否有错误
tail -f logs/error/error.log
```

### Q2: 工作目录占用空间太大怎么办?

**原因**:
- 状态数据量大
- RocksDB 压缩不及时

**解决**:
1. 增加 RocksDB 后台压缩任务:
   ```yaml
   rocksdb:
     max-background-jobs: 4
   ```

2. 启用增量 Checkpoint:
   ```yaml
   state:
     incremental: true
   ```

3. 定期清理旧状态

### Q3: 如何修改工作目录位置?

**方法 1: JVM 参数** (推荐)
```bash
export FLINK_ENV_JAVA_OPTS="-Djava.io.tmpdir=/c/flink-temp"
```

**方法 2: 环境变量**
```bash
export TMPDIR=/c/flink-temp
export TEMP=/c/flink-temp
export TMP=/c/flink-temp
```

**方法 3: Flink 配置**
```yaml
# flink-conf.yaml
env.java.opts: -Djava.io.tmpdir=/c/flink-temp
```

### Q4: Windows 路径长度限制影响哪个目录?

**影响**: 主要影响**工作目录**

**原因**:
- 工作目录路径包含多层嵌套 (minicluster_xxx/tm_0/tmp/job_xxx/...)
- 加上 RocksDB 的文件名,容易超过 260 字符

**解决**:
1. 修改 `java.io.tmpdir` 使用短路径
2. 禁用 `local-recovery` (避免额外的 localState 目录)
3. 启用 Windows 长路径支持

## 最佳实践

### 1. 开发环境

```yaml
flink:
  state:
    backend: rocksdb
    checkpoint-dir: file:///c/flink-state/checkpoints  # 短路径
    savepoint-dir: file:///c/flink-state/savepoints
    incremental: true
    local-recovery: false  # 禁用,避免路径过长
```

```bash
# 设置工作目录为短路径
export FLINK_ENV_JAVA_OPTS="-Djava.io.tmpdir=/c/flink-temp"
```

### 2. 生产环境

```yaml
flink:
  state:
    backend: rocksdb
    checkpoint-dir: hdfs:///flink/checkpoints  # 使用 HDFS
    savepoint-dir: hdfs:///flink/savepoints
    incremental: true
    local-recovery: true  # 启用,加快恢复速度
```

### 3. Docker 环境

```yaml
flink:
  state:
    backend: rocksdb
    checkpoint-dir: file:///opt/flink/checkpoints  # 容器内路径
    savepoint-dir: file:///opt/flink/savepoints
    incremental: true
    local-recovery: false  # 容器重启会丢失,不建议启用
```

## 总结

1. **工作目录** 和 **Checkpoint 目录** 是两个不同的概念
2. 工作目录由 Flink 自动管理,用于运行时状态存储
3. Checkpoint 目录由用户配置,用于持久化快照存储
4. 日志中显示的是工作目录,不是 Checkpoint 目录
5. 配置 `checkpoint-dir` 只影响 Checkpoint 目录,不影响工作目录
6. Windows 路径长度限制主要影响工作目录,需要单独处理

## 相关配置

- `checkpoint-dir`: Checkpoint 目录
- `savepoint-dir`: Savepoint 目录
- `java.io.tmpdir`: 工作目录 (JVM 参数)
- `local-recovery`: 本地恢复 (会创建额外的 localState 目录)

## 参考资料

- [Flink State Backends](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/ops/state/state_backends/)
- [RocksDB State Backend](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/ops/state/state_backends/#rocksdb-state-backend)
- [Checkpointing](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/dev/datastream/fault-tolerance/checkpointing/)
