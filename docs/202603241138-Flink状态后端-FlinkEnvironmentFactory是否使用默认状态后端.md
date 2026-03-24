# FlinkEnvironmentFactory 是否使用默认状态后端

## 1. 问题背景

用户问题：

`FlinkEnvironmentFactory` 怎么配置状态后端的，当前是不是默认状态后端？

## 2. 结论

结论很明确：

1. 当前项目里的 `FlinkEnvironmentFactory` **没有显式配置状态后端**。
2. 它目前只做了：
   - 创建 `StreamExecutionEnvironment`
   - 设置并行度
   - 开启 Checkpoint
   - 配置 Web UI
   - 配置 Metrics
3. 所以从这段工厂代码本身看，**状态后端不是这里手动指定的，属于走 Flink 默认行为或外部 Flink/StreamPark 配置覆盖**。

## 3. 代码证据

### 3.1 Factory 里只开启了 Checkpoint，没有设置 StateBackend

文件：

- `src/main/java/com/crypto/dw/flink/factory/FlinkEnvironmentFactory.java`

关键代码逻辑：

- `createStreamEnvironment()` 中只调用了 `env.enableCheckpointing(checkpointInterval);`
- `createTableEnvironment()` 中也只调用了 `env.enableCheckpointing(checkpointInterval);`
- 整个类中**没有**看到：
  - `env.setStateBackend(...)`
  - `env.getCheckpointConfig().setCheckpointStorage(...)`
  - `env.configure(...)` 注入 `state.backend.type`

这说明：

**当前 Factory 不负责真正把 RocksDB / HashMap 之类的状态后端应用到运行环境。**

### 3.2 配置文件里虽然写了 backend，但 Factory 没有读取并应用

项目配置文件中存在以下配置：

#### 开发环境

文件：`src/main/resources/config/application-dev.yml`

```yaml
flink:
  state:
    backend: rocksdb
    checkpoint-dir: file:///tmp/flink-checkpoints
    savepoint-dir: file:///tmp/flink-savepoints
```

#### Docker 环境

文件：`src/main/resources/config/application-docker.yml`

```yaml
flink:
  state:
    backend: rocksdb
    checkpoint-dir: file:///opt/flink/checkpoints
    savepoint-dir: file:///opt/flink/savepoints
```

但是 `FlinkEnvironmentFactory` 当前并没有把这些值取出来后调用 Flink API 设置进去。

所以这里要特别注意：

**“配置文件里写了” 不等于 “运行时一定生效了”。**

## 4. 当前到底谁决定状态后端

当前项目里，状态后端最终可能由下面几种来源决定：

1. `FlinkEnvironmentFactory` 代码显式设置
   - 目前没有
2. Flink 集群配置（例如 `flink-conf.yaml`）
   - 如果集群里设置了 `state.backend: rocksdb`，那运行时可能会生效
3. StreamPark 的 Dynamic Properties / Application Configuration
   - 如果提交作业时传了 `state.backend`、`state.checkpoints.dir` 等，也可能覆盖默认值
4. Flink 默认配置
   - 当以上都没有生效时，就会回到默认行为

## 5. 这个项目里“默认”可以怎么理解

项目当前使用的是 **Flink 1.17.2**。

根据 Flink 官方文档：

- 默认 State Backend 是 `HashMapStateBackend`
- 如果**没有配置** checkpoint 目录，默认 Checkpoint Storage 是 `JobManagerCheckpointStorage`
- 如果配置了 `state.checkpoints.dir`，则会使用 `FileSystemCheckpointStorage`

官方参考：

- [Flink 1.17 State Backends](https://nightlies.apache.org/flink/flink-docs-release-1.17/zh/docs/ops/state/state_backends/)
- [Flink 1.17 Checkpoints](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/ops/state/checkpoints/)
- [Flink 1.17 Configuration](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/config/)

所以更准确地说：

**当前 `FlinkEnvironmentFactory` 代码本身没有设置状态后端。仅从这段代码判断，它依赖 Flink 外部配置；如果外部也没配，就会落到 Flink 1.17 的默认组合。**

## 6. 结合本项目的实际判断

这意味着当前项目存在一个容易误判的点：

1. `application-dev.yml` 和 `application-docker.yml` 里写了 `flink.state.backend: rocksdb`
2. 但 `FlinkEnvironmentFactory` 没把这个配置真正应用到 `env`
3. 因此：
   - 如果 Flink 集群或 StreamPark 另外配置了 `state.backend=rocksdb`，那最终可能还是 RocksDB
   - 如果外部没配，那**不是因为项目 YAML 写了 rocksdb 就自动生效**

也就是说：

**当前仓库里的 YAML 更像“项目侧意图配置”，但 Factory 代码还没有把它落实成真正的 Flink Runtime 配置。**

## 7. 排查方法

后续如果你想确认“当前运行中的作业到底是不是 RocksDB”，建议这样排查：

1. 看代码里有没有 `env.setStateBackend(...)`
2. 看提交参数里有没有：
   - `state.backend: rocksdb`
   - `state.checkpoints.dir: ...`
3. 看 Flink 集群的 `flink-conf.yaml`
4. 看 StreamPark 的 Dynamic Properties
5. 结合 Flink Web UI / 作业日志确认最终生效参数

## 8. 一句话总结

一句话总结：

**当前 `FlinkEnvironmentFactory` 没有显式配置状态后端，所以它本身是“默认/外部配置驱动”的；项目 YAML 虽然写了 `rocksdb`，但仅靠这段 Factory 代码还不会自动生效。**
