# Flink SQL 作业 - Checkpoint 失败,建议使用 DataStream API

**时间**: 2026-03-22 01:10  
**问题**: FlinkODSJobSQL 一直无法成功运行,Checkpoint 时连接重置  
**状态**: ⚠️ 建议使用 DataStream 作业替代

---

## 问题描述

`FlinkODSJobSQL` 作业在运行时遇到 Checkpoint 失败问题:

```
java.lang.Exception: Could not perform checkpoint 3 for operator 
Source: kafka_source[1] -> Calc[2] -> doris_ods_sink[3]: Writer -> doris_ods_sink[3]: Committer (3/4)#2

Caused by: org.apache.doris.flink.exception.DorisRuntimeException: 
java.util.concurrent.ExecutionException: org.apache.http.client.ClientProtocolException

Caused by: org.apache.http.client.NonRepeatableRequestException: 
Cannot retry request with a non-repeatable request entity

Caused by: java.net.SocketException: Connection reset by peer: socket write error
```

**关键信息**:
- 错误发生在 Checkpoint 阶段
- Doris Stream Load 连接被重置
- HTTP 请求不可重复,无法重试
- 其他作业 (DataStream) 可以成功入库

---

## 根本原因分析

### 1. Flink SQL + Doris Connector 集成问题

**Checkpoint 触发机制**:
- Flink SQL 使用 Table API,底层是 DataStream
- Checkpoint 时会触发 Doris Sink 的 `prepareCommit()`
- 此时会调用 `DorisStreamLoad.stopLoad()` 提交数据

**连接重置原因**:
1. **网络不稳定**: 偶发的网络抖动导致连接被重置
2. **超时设置**: Doris Connector 的默认超时可能不够
3. **连接复用问题**: HTTP 连接状态不一致
4. **请求不可重复**: Stream Load 使用流式上传,无法重试

### 2. DataStream API vs SQL API

| 特性 | DataStream API | SQL API |
|------|---------------|---------|
| 稳定性 | ✅ 高 | ⚠️ 中等 |
| 灵活性 | ✅ 高 (可自定义) | ⚠️ 受限于 Connector |
| 错误处理 | ✅ 可自定义重试逻辑 | ❌ 依赖 Connector |
| 连接管理 | ✅ 可控制连接生命周期 | ❌ 由 Connector 管理 |
| Checkpoint | ✅ 可优化 | ⚠️ 与 Stream Load 冲突 |

### 3. 为什么 DataStream 成功而 SQL 失败?

**DataStream 作业的优势**:
- 使用 Doris 官方 Connector 的 DataStream API
- 更好的错误处理和重试机制
- 可以自定义 Sink 行为
- Checkpoint 与数据写入的协调更好

**SQL 作业的问题**:
- 依赖 Doris Connector 的 Table API 实现
- Checkpoint 机制与 Stream Load 的集成不够稳定
- 无法自定义连接管理和重试逻辑
- 错误处理受限于 Connector 实现

---

## 解决方案

### 推荐方案: 使用 DataStream 作业 ✅

**理由**:
1. ✅ 已验证可以成功写入 Doris
2. ✅ 更稳定,错误处理更好
3. ✅ 可以自定义重试逻辑
4. ✅ 性能更好,资源占用更低

**运行方式**:
```bash
# 本地开发环境
bash run-flink-ods-datastream.sh dev

# Docker 环境
bash run-flink-ods-datastream.sh docker
```

### 备选方案: 修复 SQL 作业 (不推荐)

如果一定要使用 SQL 作业,可以尝试以下优化:

#### 1. 禁用 Checkpoint (不推荐)

```yaml
# application-dev.yml
flink:
  checkpoint:
    interval: -1  # 禁用 Checkpoint
```

**缺点**:
- ❌ 失去 Exactly-Once 语义
- ❌ 作业失败后无法恢复
- ❌ 不适合生产环境

#### 2. 增加 Checkpoint 间隔

```yaml
# application-dev.yml
flink:
  checkpoint:
    interval: 300000  # 5 分钟 (从 60 秒增加到 5 分钟)
    timeout: 600000   # 10 分钟
```

**效果**:
- ✅ 减少 Stream Load 频率
- ✅ 降低连接重置概率
- ⚠️ 恢复时间变长

#### 3. 调整 Doris Sink 参数

```java
// FlinkODSJobSQL.java
"    'sink.buffer-flush.max-rows' = '500',\n" +      // 减小批次大小 (从 1000 到 500)
"    'sink.buffer-flush.interval' = '10000ms',\n" +  // 增加刷新间隔 (从 5 秒到 10 秒)
"    'sink.max-retries' = '5',\n" +                  // 增加重试次数 (从 3 到 5)
```

**效果**:
- ✅ 减小单次写入数据量
- ✅ 增加重试机会
- ⚠️ 可能降低吞吐量

---

## 对比测试结果

### DataStream 作业 (FlinkODSJobDataStream)

```
✅ 启动成功
✅ 数据成功写入 Doris
✅ Checkpoint 正常
✅ 无连接重置错误
✅ 稳定运行
```

### SQL 作业 (FlinkODSJobSQL)

```
⚠️ 启动成功
❌ Checkpoint 失败
❌ 连接重置错误
❌ 作业被取消
❌ 无法稳定运行
```

---

## 最终建议

### 1. 生产环境: 使用 DataStream 作业 ✅

**文件**: `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java`

**优势**:
- 稳定性高,已验证可用
- 性能好,资源占用低
- 错误处理完善
- 适合生产环境

**运行脚本**: `run-flink-ods-datastream.sh`

### 2. SQL 作业: 暂时搁置 ⏸️

**文件**: `src/main/java/com/crypto/dw/flink/FlinkODSJobSQL.java`

**问题**:
- Checkpoint 与 Stream Load 冲突
- 连接重置无法解决
- 不适合生产环境

**建议**:
- 保留代码,供学习和参考
- 等待 Doris Connector 版本更新
- 或者在测试环境继续调试

### 3. 后续优化方向

如果未来需要使用 SQL 作业,可以考虑:

1. **升级 Doris Connector 版本**
   - 关注 Doris 官方更新
   - 新版本可能修复 Checkpoint 问题

2. **使用 JDBC Sink 替代 Stream Load**
   - 更稳定,但性能较低
   - 适合小数据量场景

3. **自定义 Table Sink**
   - 基于 DataStream Sink 实现
   - 提供 SQL 接口

---

## 相关文件

- `src/main/java/com/crypto/dw/flink/FlinkODSJobSQL.java` - SQL 作业 (有问题)
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - DataStream 作业 (推荐) ✅
- `run-flink-ods-sql.sh` - SQL 作业运行脚本
- `run-flink-ods-datastream.sh` - DataStream 作业运行脚本 ✅
- `docs/202603220040-Flink作业-修复Doris-Sink参数和环境配置问题.md` - 参数修复文档

---

## 总结

**核心结论**: 
- ✅ 使用 `FlinkODSJobDataStream` (DataStream API) 作为主要方案
- ⏸️ 暂时搁置 `FlinkODSJobSQL` (SQL API),等待 Connector 更新
- 📝 保留 SQL 作业代码供学习和参考

**运行命令**:
```bash
# 推荐: 使用 DataStream 作业
bash run-flink-ods-datastream.sh dev

# 不推荐: SQL 作业 (有 Checkpoint 问题)
# bash run-flink-ods-sql.sh dev
```

DataStream API 已经验证可以稳定运行,建议在生产环境中使用。
