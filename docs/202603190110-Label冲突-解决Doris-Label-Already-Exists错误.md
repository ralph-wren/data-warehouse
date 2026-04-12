# Label 冲突 - 解决 Doris Label Already Exists 错误

**时间**: 2026-03-19 01:10 (东八区)  
**问题**: Flink 作业重启时报错 "Label Already Exists"  
**解决方案**: 使用时间戳作为 Label 前缀

---

## 问题现象

### 错误信息

```
ERROR org.apache.doris.flink.sink.writer.DorisWriter - Failed to abort transaction.
org.apache.doris.flink.exception.DorisException: 
Load status is Label Already Exists and load job finished, 
change you label prefix or restore from latest savepoint!
```

### 触发条件

- Flink 作业多次重启
- 使用相同的 Label 前缀
- 之前的 Load 任务已经完成

---

## 问题原因

### Doris Label 机制

Doris 使用 Label 来保证数据写入的幂等性:
1. 每个 Stream Load 任务都有一个唯一的 Label
2. Label 格式: `{labelPrefix}_{database}_{table}_{subtaskId}_{checkpointId}`
3. 相同的 Label 只能使用一次
4. Label 会在 Doris 中保留 3 天

### 为什么会冲突

```
第一次运行: flink-ods_crypto_dw_ods_crypto_ticker_rt_0_1 ✓ 成功
第二次运行: flink-ods_crypto_dw_ods_crypto_ticker_rt_0_1 ✗ 冲突!
```

当 Flink 作业重启时:
- 如果没有从 Savepoint 恢复,Checkpoint ID 会从 1 重新开始
- 使用相同的 Label 前缀会导致 Label 重复
- Doris 拒绝重复的 Label,抛出异常

---

## 解决方案

### 方案 1: 使用时间戳 Label 前缀 (推荐) ⭐

**修改代码**:

```java
DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
    .setStreamLoadProp(streamLoadProp)
    .setMaxRetries(3)
    .setBufferSize(1000 * 1024)
    .setBufferCount(3)
    .setLabelPrefix("flink-ods-" + System.currentTimeMillis())  // 使用时间戳
    .build();
```

**优点**:
- ✓ 每次启动使用不同的 Label 前缀
- ✓ 避免 Label 冲突
- ✓ 无需手动清理

**缺点**:
- ⚠ 无法保证 Exactly-Once 语义 (如果作业异常重启)
- ⚠ 可能导致数据重复 (需要在下游去重)

**适用场景**:
- 开发和测试环境
- 可以容忍少量数据重复的场景
- 频繁重启作业的场景

---

### 方案 2: 使用 Savepoint 恢复 (生产推荐) ⭐⭐⭐

**保存 Savepoint**:

```bash
# 获取 Job ID
flink list

# 创建 Savepoint
flink savepoint <job-id> file:///tmp/flink-savepoints

# 停止作业
flink cancel <job-id>
```

**从 Savepoint 恢复**:

```bash
flink run -s file:///tmp/flink-savepoints/savepoint-xxx \
  -c com.crypto.dw.jobs.FlinkODSJobDataStream \
  target/realtime-crypto-datawarehouse-1.0.0.jar
```

**优点**:
- ✓ 保证 Exactly-Once 语义
- ✓ 不会产生重复数据
- ✓ 保持 Checkpoint 状态

**缺点**:
- ⚠ 需要手动管理 Savepoint
- ⚠ 操作相对复杂

**适用场景**:
- 生产环境
- 需要严格保证数据不重复
- 计划内的作业重启

---

### 方案 3: 等待 Label 过期

**原理**: Doris 的 Label 默认保留 3 天后自动过期

**操作**:
```bash
# 查看 Label 状态
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SHOW LOAD WHERE Label LIKE 'flink-ods%' ORDER BY CreateTime DESC LIMIT 20;
"

# 等待 3 天后,Label 自动过期
```

**优点**:
- ✓ 无需修改代码
- ✓ 保持原有配置

**缺点**:
- ⚠ 需要等待 3 天
- ⚠ 不适合频繁重启的场景

---

## 实施步骤

### 使用方案 1 (时间戳 Label)

1. **修改代码** (已完成):
```java
.setLabelPrefix("flink-ods-" + System.currentTimeMillis())
```

2. **重新编译**:
```bash
mvn clean compile -DskipTests
```

3. **停止旧作业** (如果正在运行):
```bash
# 查找 Java 进程
ps aux | grep FlinkODSJobDataStream

# 停止进程
kill <pid>
```

4. **重新运行**:
```bash
./run-flink-ods-datastream.sh
```

---

## 验证

### 1. 检查 Label

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SHOW LOAD WHERE Label LIKE 'flink-ods%' ORDER BY CreateTime DESC LIMIT 10;
"
```

**期望结果**: 看到新的 Label,格式为 `flink-ods-1773929xxx_...`

### 2. 检查数据写入

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT COUNT(*) FROM crypto_dw.ods_crypto_ticker_rt;
SELECT * FROM crypto_dw.ods_crypto_ticker_rt ORDER BY ingest_time DESC LIMIT 5;
"
```

**期望结果**: 数据持续增长,无错误

### 3. 检查 Flink 日志

```bash
tail -f logs/flink-app.log | grep -E "ERROR|Exception|Label"
```

**期望结果**: 无 "Label Already Exists" 错误

---

## 技术细节

### Label 生成逻辑

```java
// Doris Connector 内部生成 Label 的逻辑
String label = String.format(
    "%s_%s_%s_%d_%d",
    labelPrefix,           // flink-ods-1773929xxx
    database,              // crypto_dw
    table,                 // ods_crypto_ticker_rt
    subtaskId,             // 0, 1, 2...
    checkpointId           // 1, 2, 3...
);

// 示例: flink-ods-1773929123456_crypto_dw_ods_crypto_ticker_rt_0_1
```

### Label 状态

| 状态 | 说明 | 可重用 |
|-----|------|--------|
| PENDING | 正在加载 | ✗ |
| FINISHED | 加载成功 | ✗ |
| CANCELLED | 已取消 | ✗ |
| FAILED | 加载失败 | ✓ (可重试) |

### Label 过期时间

```sql
-- 查看 Label 配置
SHOW VARIABLES LIKE '%label%';

-- 默认配置
label_keep_max_second = 259200  -- 3 天 (72 小时)
```

---

## 最佳实践

### 开发环境

```java
// 使用时间戳,方便频繁重启
.setLabelPrefix("dev-" + System.currentTimeMillis())
```

### 测试环境

```java
// 使用日期 + 时间戳
String dateStr = new SimpleDateFormat("yyyyMMdd").format(new Date());
.setLabelPrefix("test-" + dateStr + "-" + System.currentTimeMillis())
```

### 生产环境

```java
// 使用固定前缀 + 从 Savepoint 恢复
.setLabelPrefix("prod-flink-ods")

// 启动命令
flink run -s file:///path/to/savepoint \
  -c com.crypto.dw.jobs.FlinkODSJobDataStream \
  target/realtime-crypto-datawarehouse-1.0.0.jar
```

---

## 常见问题

### Q1: 使用时间戳会导致数据重复吗?

**A**: 可能会。如果作业异常重启,相同的数据可能会被重新写入。

**解决方案**:
- 在 DWD 层进行去重
- 使用 Flink 的 Exactly-Once 语义 + Savepoint
- 在查询时使用 `DISTINCT` 或 `GROUP BY`

### Q2: 如何清理旧的 Label?

**A**: Doris 会自动清理 3 天前的 Label,无需手动操作。

如果需要立即清理,可以重启 Doris FE:
```bash
docker restart doris-fe
```

### Q3: Savepoint 和 Checkpoint 的区别?

**A**:
- **Checkpoint**: 自动创建,用于故障恢复,会被自动清理
- **Savepoint**: 手动创建,用于计划内重启,需要手动管理

### Q4: 如何避免数据重复?

**A**: 
1. 使用固定的 Label 前缀
2. 从 Savepoint 恢复作业
3. 在 DWD 层使用 `ROW_NUMBER()` 去重
4. 在 ODS 层添加唯一键约束 (如果支持)

---

## 相关配置

### Flink 配置

```yaml
# config/application.yml
doris:
  stream-load:
    label-prefix: "flink-ods"  # 可以在这里配置
    max-retries: 3
    timeout-seconds: 600
```

### Doris 配置

```sql
-- 查看 Load 历史
SHOW LOAD ORDER BY CreateTime DESC LIMIT 20;

-- 查看特定 Label
SHOW LOAD WHERE Label = 'flink-ods_crypto_dw_ods_crypto_ticker_rt_0_1';

-- 取消 Load 任务 (如果卡住)
CANCEL LOAD FROM crypto_dw WHERE Label = 'xxx';
```

---

## 总结

### 问题根源
Flink 作业重启时使用相同的 Label 前缀,导致 Label 冲突

### 推荐方案
- **开发/测试**: 使用时间戳 Label 前缀 (方便快速迭代)
- **生产环境**: 使用固定 Label + Savepoint 恢复 (保证数据一致性)

### 关键代码
```java
.setLabelPrefix("flink-ods-" + System.currentTimeMillis())
```

---

**下一步**: 重新运行 Flink 作业,验证 Label 冲突已解决

**相关文档**:
- [问题解决汇总](../问题解决汇总.md)
- [最终成功方案](./202603190100-最终成功-Doris部署和Flink连接完整方案.md)
