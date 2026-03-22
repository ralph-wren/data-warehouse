# Flink + Doris 端到端一致性保证方案

**时间**: 2026-03-23 00:10  
**类型**: 技术方案  
**适用场景**: Flink 从 Doris 读取数据写到另一个 Doris 表

---

## 问题背景

在实时数据仓库中,经常需要 Flink 从一个 Doris 表读取数据,经过计算后写入另一个 Doris 表。例如:
- ODS → DWD: 数据清洗
- DWD → DWS: 数据聚合
- DWS → ADS: 指标计算

**核心问题**: 如何保证端到端的数据一致性?
- 数据不丢失 (No Data Loss)
- 数据不重复 (No Duplicate)
- 精确一次语义 (Exactly-Once Semantics)

---

## 端到端一致性公式

```
端到端一致性 = Source 一致性 + Flink 处理一致性 + Sink 一致性
```

---

## 1. Flink Checkpoint 机制 (核心)

### 1.1 什么是 Checkpoint?

Checkpoint 是 Flink 的**分布式快照机制**:
- 定期保存作业的状态快照
- 包括算子状态、数据流位置、时间戳等
- 失败时从最近的 Checkpoint 恢复

### 1.2 Checkpoint 配置

```yaml
# src/main/resources/config/application-dev.yml
flink:
  checkpoint:
    interval: 60000  # 60秒,Checkpoint 间隔 ⭐
    mode: EXACTLY_ONCE  # 精确一次语义 ⭐⭐⭐
    timeout: 300000  # 5分钟,Checkpoint 超时时间
    min-pause: 500  # 两次 Checkpoint 之间的最小间隔
    max-concurrent: 1  # 同时只有1个 Checkpoint (避免冲突)
```

**关键参数说明**:

| 参数 | 说明 | 推荐值 |
|------|------|--------|
| interval | Checkpoint 间隔 | 60秒 (生产环境可调整为 30-120秒) |
| mode | 一致性模式 | EXACTLY_ONCE (精确一次) |
| timeout | Checkpoint 超时 | 5分钟 (根据数据量调整) |
| max-concurrent | 最大并发 Checkpoint | 1 (避免冲突) |

### 1.3 Checkpoint 工作流程

```
1. JobManager 触发 Checkpoint
   ↓
2. Source 算子保存状态 (记录读取位置)
   ↓
3. 中间算子保存状态 (窗口数据、聚合结果等)
   ↓
4. Sink 算子保存状态 (缓存的待写入数据)
   ↓
5. 所有算子确认完成
   ↓
6. Checkpoint 成功 (状态持久化到存储)
```

**失败恢复**:
```
作业失败
  ↓
从最近的 Checkpoint 恢复
  ↓
Source 从保存的位置继续读取
  ↓
Sink 重新写入未提交的数据
  ↓
保证数据不丢失、不重复
```

---

## 2. Doris Source 一致性

### 2.1 Doris Source 特点 ⭐⭐⭐

Doris Source 是**有界流** (Bounded Stream):
- 读取表的快照数据 (Snapshot Read)
- **不支持 CDC 方式的增量读取** (官方文档明确说明)
- 读取完成后 Source 结束
- **不保存读取位置** (重启后重新读取全表)

**官方文档说明** (来自 Doris 3.x 文档):
> Doris Source 目前是有界流,不支持以 CDC 的方式持续读取。如果需要增量同步,可以使用 Flink CDC + 整库同步工具。

**读取模式**:
```
Flink 启动
  ↓
连接 Doris FE
  ↓
获取表的分区信息和数据快照
  ↓
并行读取各个分区 (每个并行度读取一部分数据)
  ↓
读取完成,Source 结束 ✅
  ↓
即使 Doris 表继续更新,也不会再读取新数据 ⚠️
```

**关键特性**:

| 特性 | Kafka Source | Doris Source |
|------|-------------|--------------|
| 流类型 | 无界流 (Unbounded) | 有界流 (Bounded) |
| 读取模式 | 持续消费 | 一次性读取 |
| 保存 Offset | ✅ 是 | ❌ 否 |
| 新数据读取 | ✅ 自动读取 | ❌ 不会读取 |
| Source 结束 | ❌ 永不结束 | ✅ 读取完就结束 |
| Checkpoint 恢复 | 从 Offset 继续 | 重新读取全表 |
| 适用场景 | 实时流处理 | 批处理/离线处理 |

### 2.2 Checkpoint 恢复时的行为 ⭐⭐⭐

**重要**: Doris Source 不保存读取位置!

```
Checkpoint 保存的内容:
├── Kafka Source: ✅ Partition Offset (分区 0: 12345, 分区 1: 23456)
├── Doris Source: ❌ 无状态 (不保存读取位置)
├── 窗口算子: ✅ 窗口数据
├── 聚合算子: ✅ 聚合结果
└── Doris Sink: ✅ 事务 ID、缓存数据
```

**恢复流程对比**:

**Kafka Source 恢复** (有状态):
```
作业失败
  ↓
从 Checkpoint 恢复
  ↓
Kafka Source 从保存的 Offset 继续消费 ✅
  - Partition 0: 从 Offset 12345 开始
  - Partition 1: 从 Offset 23456 开始
  ↓
只处理新数据,不重复处理
```

**Doris Source 恢复** (无状态):
```
作业失败 (已读取 500 条,共 1000 条)
  ↓
从 Checkpoint 恢复
  ↓
Doris Source 重新读取全表 ⚠️
  - 重新扫描整个表或分区
  - 从头开始读取 1000 条数据
  ↓
Doris Sink 两阶段提交保证不重复写入 ✅
  - 未提交的事务会回滚
  - 重新处理的数据不会重复写入
```

**为什么 Doris Source 不保存读取位置?**

1. **技术原因**:
   - Doris 是 OLAP 数据库,不是消息队列
   - 没有 Offset/Position 概念
   - 实现的是 `InputFormat` (批处理接口),不是 `SourceFunction` (流处理接口)

2. **设计考虑**:
   - 读取的是表快照,不是流式数据
   - 批处理场景不需要保存位置
   - 读取完成后 Source 就结束了

### 2.3 Doris 表持续更新的问题 ⚠️

**场景**: Flink 作业运行期间,Doris Source 表持续有新数据写入

```
T0: Flink 启动,Doris 表有 1000 条数据
  ↓
T1: Flink 开始读取 (获取快照: 1000 条)
  ↓
T2: Doris 表新增 100 条 (总共 1100 条)
  ↓
T3: Flink 读取完成 (只读取了 1000 条)
  ↓
T4: Source 结束
  ↓
T5: 新增的 100 条不会被读取 ❌
```

**结论**: Doris Source 不会持续读取新数据,只读取启动时的快照!

### 2.4 保证一致性的方法

#### 方法 1: 使用分区读取 (推荐) ⭐⭐⭐

只读取已完成的分区,避免读取过程中数据变化:

```sql
-- 示例: 只读取昨天的数据
SELECT * FROM dwd_crypto_ticker_detail 
WHERE trade_date = DATE_SUB(CURDATE(), INTERVAL 1 DAY)
```

**优点**:
- 读取的是静态数据,不会变化
- 可以重复读取,结果一致
- 适合批处理场景

#### 方法 2: 使用时间戳过滤

只读取特定时间范围的数据:

```sql
-- 示例: 读取过去1小时的数据
SELECT * FROM dwd_crypto_ticker_detail 
WHERE process_time >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000 
  AND process_time < UNIX_TIMESTAMP(NOW()) * 1000
```

**优点**:
- 可以控制读取的数据量
- 适合增量处理场景

#### 方法 3: 使用 WHERE 1=1 读取全表 (不推荐)

```sql
-- 读取全表 (数据量大时不推荐)
SELECT * FROM dwd_crypto_ticker_detail
```

**缺点**:
- 读取过程中表可能有新数据写入
- 无法保证读取的一致性
- 数据量大时性能差

### 2.5 Doris Source 配置

#### 默认使用 ArrowFlightSQL 读取方式 (Doris 3.0+) ⭐⭐⭐

**当前项目配置**: Doris 3.0.7 + ArrowFlightSQL (高性能模式)

```java
// 创建 Doris Source 表 (默认 ArrowFlightSQL 读取)
String ddl = "CREATE TABLE doris_source (\n" +
             "    inst_id STRING,\n" +
             "    timestamp BIGINT,\n" +
             "    ...\n" +
             ") WITH (\n" +
             "    'connector' = 'doris',\n" +
             "    'fenodes' = '127.0.0.1:8030',\n" +
             "    'table.identifier' = 'crypto_dw.dwd_crypto_ticker_detail',\n" +
             "    'username' = 'root',\n" +
             "    'password' = '',\n" +
             "    -- ⭐ ArrowFlightSQL 配置 (性能提升 2-10 倍)\n" +
             "    'doris.deserialize.arrow.async' = 'true',\n" +
             "    'doris.deserialize.queue.size' = '64',\n" +
             "    'doris.request.query.timeout.s' = '3600',\n" +
             "    'doris.read.field' = '*'\n" +
             ")";
```

**ArrowFlightSQL 优势**:
- ✅ 性能提升 2-10 倍 (相比 Thrift)
- ✅ 内存占用更少
- ✅ 支持更大的数据量
- ✅ 异步反序列化,提高吞吐量
- ✅ Doris 3.0+ 默认推荐方式

**关键配置参数**:
- `doris.deserialize.arrow.async = true`: 启用异步反序列化
- `doris.deserialize.queue.size = 64`: 反序列化队列大小
- `doris.request.query.timeout.s = 3600`: 查询超时时间(1小时)
- `doris.read.field = *`: 指定读取字段(可以只读取需要的字段)

**注意事项**:
- Doris Source 不支持 Checkpoint (因为是有界流)
- 读取失败时会重新读取整个表或分区
- 建议使用分区读取,减少重复读取的数据量

### 2.6 如何实现持续读取? ⭐⭐⭐

由于 Doris Source 是一次性读取,如果需要持续处理新数据,有以下方案:

#### 方案 1: 定时批处理 (推荐) ⭐⭐⭐

使用调度工具定时启动 Flink 作业:

```bash
# 使用 Cron 定时任务,每小时执行一次
0 * * * * /path/to/run-flink-dwd-sql.sh

# 配合时间过滤,只处理最近的数据
SELECT * FROM ods_crypto_ticker_rt 
WHERE ingest_time >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000 
  AND ingest_time < UNIX_TIMESTAMP(NOW()) * 1000
```

#### 方案 2: 使用 Kafka 作为中间层 (推荐) ⭐⭐⭐

```
实时层 (持续运行):
├── WebSocket → Kafka (无界流)
└── Kafka → Doris ODS (无界流)

批处理层 (定时运行):
├── Doris ODS → Doris DWD (有界流,定时)
├── Doris DWD → Doris DWS (有界流,定时)
└── Doris DWS → Doris ADS (有界流,定时)
```

**这就是你当前项目的架构!** ✅

#### 方案 3: 使用 Flink CDC + 整库同步 (官方推荐的增量方案) ⭐⭐

**官方文档说明**:
> 如果需要增量同步,可以使用 Flink CDC 整库同步功能。该功能集成了 Flink CDC,可以同步 MySQL、Oracle、PostgreSQL、SQLServer 等数据库到 Doris。

**架构**:
```
MySQL (源数据库)
  ↓ (Flink CDC 监听 Binlog)
Flink CDC 整库同步工具
  ↓ (实时写入)
Doris (目标数据库)
```

**使用场景**:
- 需要从 MySQL 等数据库实时同步到 Doris
- 需要 CDC 增量同步能力
- 需要整库或多表同步

**限制**:
- 只支持从外部数据库同步到 Doris
- 不支持 Doris 表之间的 CDC 同步
- 需要源数据库支持 CDC (如 MySQL Binlog)

**配置示例** (使用 Flink CDC 整库同步):
```yaml
# flink-cdc-sync-config.yaml
source:
  type: mysql
  hostname: localhost
  port: 3306
  username: root
  password: password
  database-name: source_db
  table-name: .*  # 同步所有表

sink:
  type: doris
  fenodes: 127.0.0.1:8030
  username: root
  password: ""
  database-name: target_db
  sink.enable-2pc: true
```

**启动命令**:
```bash
# 使用 Flink CDC 整库同步工具
flink run \
  -c com.ververica.cdc.cli.CliFrontend \
  flink-cdc-cli.jar \
  --config flink-cdc-sync-config.yaml
```

#### 方案 4: 使用增量标记表 (自己实现增量逻辑)

```sql
-- 创建增量标记表
CREATE TABLE ods_process_watermark (
    job_name VARCHAR(100),
    last_process_time BIGINT,
    update_time DATETIME
);

-- 只读取新数据
SELECT * FROM ods_crypto_ticker_rt 
WHERE ingest_time > (
    SELECT last_process_time 
    FROM ods_process_watermark 
    WHERE job_name = 'flink-dwd-job'
);

-- 处理完成后更新标记
UPDATE ods_process_watermark 
SET last_process_time = UNIX_TIMESTAMP(NOW()) * 1000 
WHERE job_name = 'flink-dwd-job';
```

**优点**:
- 简单易实现
- 不需要额外工具
- 可以精确控制增量范围

**缺点**:
- 需要手动管理标记表
- 不是真正的 CDC (无法捕获 UPDATE/DELETE)
- 依赖时间戳字段

### 2.7 Doris Source 读取方式对比

| 读取方式 | 性能 | 兼容性 | 适用场景 | Doris 版本要求 | 当前项目 |
|---------|------|--------|---------|---------------|---------|
| Thrift | 中等 | 最好 | 小数据量,兼容性优先 | 所有版本 | ❌ 未使用 |
| ArrowFlightSQL | 高 (2-10倍) | 好 | 大数据量,性能优先 | 2.1+ | ✅ 默认使用 |

**当前项目配置**: ✅ Doris 3.0.7 + ArrowFlightSQL (高性能模式)

**推荐配置**:
- ✅ 生产环境: ArrowFlightSQL (Doris 2.1+) - 当前使用
- ⚠️ 开发环境: Thrift (兼容性好) - 备选方案
- ✅ 大数据量: ArrowFlightSQL + 分区读取 - 当前使用

### 2.8 增量读取方案对比

| 方案 | 实时性 | 复杂度 | CDC 支持 | 适用场景 |
|------|--------|--------|---------|---------|
| 定时批处理 | 低 (分钟级) | 低 | ❌ | 离线分析,定时 ETL |
| Kafka 中间层 | 高 (秒级) | 中 | ❌ | 实时数仓 (当前架构) |
| Flink CDC 整库同步 | 高 (秒级) | 中 | ✅ | MySQL → Doris 同步 |
| 增量标记表 | 中 (分钟级) | 低 | ❌ | 简单增量处理 |

**当前项目推荐**: Kafka 中间层 (方案 2) ✅
- 实时层使用 Kafka (无界流,持续运行)
- 批处理层使用 Doris Source (有界流,定时运行)
- 架构清晰,职责分明

---

## 3. Doris Sink 一致性 (关键) ⭐⭐⭐

### 3.1 Doris Connector 的两阶段提交 (2PC)

Flink Doris Connector 支持**两阶段提交** (Two-Phase Commit),保证精确一次语义。

#### 阶段 1: Pre-commit (预提交)

**触发时机**: Flink Checkpoint 开始时

**执行流程**:
```
1. Flink Checkpoint 触发
   ↓
2. Doris Sink 将缓存的数据写入 Doris (Stream Load)
   ↓
3. 获取 Doris 事务 ID (Transaction ID)
   ↓
4. 将事务 ID 保存到 Checkpoint 状态中
   ↓
5. 数据已写入 Doris,但事务未提交 (对外不可见)
```

**关键点**:
- 数据已经写入 Doris 的存储层
- 但事务未提交,数据对外不可见
- 如果 Checkpoint 失败,事务会回滚

#### 阶段 2: Commit (提交)

**触发时机**: Flink Checkpoint 完成时

**执行流程**:
```
1. Flink Checkpoint 完成
   ↓
2. JobManager 通知所有 Sink 算子
   ↓
3. Doris Sink 提交事务 (使用保存的事务 ID)
   ↓
4. 数据对外可见
   ↓
5. Checkpoint 完全成功
```

**关键点**:
- 只有 Checkpoint 完全成功,数据才会提交
- 保证了 Flink 状态和 Doris 数据的一致性

#### 失败回滚

**场景 1: Pre-commit 失败**
```
Checkpoint 触发
  ↓
Doris 写入失败 (网络问题、Doris 故障等)
  ↓
Checkpoint 失败
  ↓
Flink 从上一个 Checkpoint 恢复
  ↓
重新处理数据
```

**场景 2: Commit 失败**
```
Pre-commit 成功 (数据已写入 Doris)
  ↓
Checkpoint 完成
  ↓
Commit 失败 (网络问题)
  ↓
Doris 事务超时自动回滚
  ↓
Flink 从上一个 Checkpoint 恢复
  ↓
重新处理数据
```

**场景 3: Flink 作业失败**
```
作业运行中失败
  ↓
未提交的 Doris 事务自动回滚
  ↓
Flink 从最近的 Checkpoint 恢复
  ↓
重新处理数据
```

### 3.2 启用两阶段提交配置

#### 方法 1: 在 WITH 子句中配置 (推荐)

```java
// 创建 Doris Sink 表
String ddl = "CREATE TABLE doris_sink (\n" +
             "    inst_id STRING,\n" +
             "    timestamp BIGINT,\n" +
             "    ...\n" +
             ") WITH (\n" +
             "    'connector' = 'doris',\n" +
             "    'fenodes' = '127.0.0.1:8030',\n" +
             "    'benodes' = '127.0.0.1:8040',  -- BE 节点地址\n" +
             "    'table.identifier' = 'crypto_dw.dws_crypto_ticker_1min',\n" +
             "    'username' = 'root',\n" +
             "    'password' = '',\n" +
             "    -- ⭐⭐⭐ 启用两阶段提交 (关键配置)\n" +
             "    'sink.enable-2pc' = 'true',\n" +
             "    -- Label 前缀 (用于标识事务)\n" +
             "    'sink.label-prefix' = 'flink_dws_1min',\n" +
             "    -- 数据格式\n" +
             "    'sink.properties.format' = 'json',\n" +
             "    'sink.properties.read_json_by_line' = 'true',\n" +
             "    -- 批量写入配置\n" +
             "    'sink.buffer-flush.max-rows' = '1000',  -- 每1000行刷新一次\n" +
             "    'sink.buffer-flush.interval' = '5s'  -- 每5秒刷新一次\n" +
             ")";
```

#### 方法 2: 修改 FlinkTableFactory.java

```java
/**
 * 创建 Doris Sink 表 DDL (支持两阶段提交)
 */
public String createDorisSinkTable(String tableName, String dorisTable, String schema) {
    String feNodes = config.getString("doris.fe.http-url").replace("http://", "");
    String beNodes = config.getString("doris.be.nodes", "127.0.0.1:8040");
    String database = config.getString("doris.database");
    String username = config.getString("doris.fe.username");
    String password = config.getString("doris.fe.password", "");
    
    StringBuilder ddl = new StringBuilder();
    ddl.append("CREATE TABLE ").append(tableName).append(" (\n");
    ddl.append(schema);
    ddl.append("\n) WITH (\n");
    ddl.append("    'connector' = 'doris',\n");
    ddl.append("    'fenodes' = '").append(feNodes).append("',\n");
    ddl.append("    'benodes' = '").append(beNodes).append("',\n");
    ddl.append("    'table.identifier' = '").append(database).append(".").append(dorisTable).append("',\n");
    ddl.append("    'username' = '").append(username).append("',\n");
    ddl.append("    'password' = '").append(password).append("',\n");
    // ⭐⭐⭐ 启用两阶段提交
    ddl.append("    'sink.enable-2pc' = 'true',\n");
    ddl.append("    'sink.label-prefix' = 'flink_").append(dorisTable).append("',\n");
    ddl.append("    'sink.properties.format' = 'json',\n");
    ddl.append("    'sink.properties.read_json_by_line' = 'true',\n");
    ddl.append("    'sink.buffer-flush.max-rows' = '1000',\n");
    ddl.append("    'sink.buffer-flush.interval' = '5s'\n");
    ddl.append(")");
    
    return ddl.toString();
}
```

### 3.3 两阶段提交的关键参数

| 参数 | 说明 | 推荐值 |
|------|------|--------|
| sink.enable-2pc | 启用两阶段提交 | true (必须) |
| sink.label-prefix | Label 前缀 | flink_{table_name} |
| sink.buffer-flush.max-rows | 批量大小 | 1000-10000 |
| sink.buffer-flush.interval | 刷新间隔 | 5s-30s |
| sink.max-retries | 最大重试次数 | 3 |

**注意事项**:
- `sink.enable-2pc` 必须设置为 `true`
- `sink.label-prefix` 必须唯一,避免 Label 冲突
- 批量大小和刷新间隔影响性能和延迟

---

## 4. 完整的端到端一致性配置示例

### 4.1 Flink 作业配置

```java
package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.FlinkTableFactory;
import com.crypto.dw.flink.schema.TableSchemas;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;

/**
 * Flink DWD 作业 - 从 Doris ODS 读取数据写入 Doris DWD
 * 
 * 端到端一致性保证:
 * 1. Checkpoint: EXACTLY_ONCE 模式
 * 2. Doris Source: 分区读取,保证数据一致性
 * 3. Doris Sink: 两阶段提交,保证精确一次语义
 */
public class FlinkDWDJobSQL {
    
    public static void main(String[] args) throws Exception {
        // 1. 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 2. 创建 Flink 环境 (启用 Checkpoint)
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dwd-job", 8082);
        
        // 3. 创建 Doris Source 表 (从 ODS 读取)
        FlinkTableFactory tableFactory = new FlinkTableFactory(config);
        String odsSourceDDL = tableFactory.createDorisSourceTable(
            "ods_source",
            "ods_crypto_ticker_rt",
            TableSchemas.DORIS_ODS_SOURCE_SCHEMA
        );
        tableEnv.executeSql(odsSourceDDL);
        
        // 4. 创建 Doris Sink 表 (写入 DWD,启用两阶段提交)
        String dwdSinkDDL = tableFactory.createDorisSinkTable(
            "dwd_sink",
            "dwd_crypto_ticker_detail",
            TableSchemas.DORIS_DWD_SINK_SCHEMA
        );
        tableEnv.executeSql(dwdSinkDDL);
        
        // 5. 执行 INSERT INTO (数据清洗和转换)
        String insertSQL = "INSERT INTO dwd_sink\n" +
                           "SELECT \n" +
                           "    inst_id,\n" +
                           "    `timestamp`,\n" +
                           "    CAST(FROM_UNIXTIME(`timestamp` / 1000, 'yyyy-MM-dd') AS DATE) AS trade_date,\n" +
                           "    CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000))) AS INT) AS trade_hour,\n" +
                           "    last_price,\n" +
                           "    bid_price,\n" +
                           "    ask_price,\n" +
                           "    ask_price - bid_price AS spread,\n" +
                           "    CAST((ask_price - bid_price) / bid_price * 100 AS DECIMAL(10, 6)) AS spread_rate,\n" +
                           "    volume_24h,\n" +
                           "    high_24h,\n" +
                           "    low_24h,\n" +
                           "    open_24h,\n" +
                           "    last_price - open_24h AS price_change_24h,\n" +
                           "    CAST((last_price - open_24h) / open_24h * 100 AS DECIMAL(10, 6)) AS price_change_rate_24h,\n" +
                           "    CAST((high_24h - low_24h) / open_24h * 100 AS DECIMAL(10, 6)) AS amplitude_24h,\n" +
                           "    data_source,\n" +
                           "    ingest_time,\n" +
                           "    UNIX_TIMESTAMP() * 1000 AS process_time\n" +
                           "FROM ods_source\n" +
                           "WHERE last_price > 0 AND bid_price > 0 AND ask_price > 0";  // 数据质量过滤
        
        tableEnv.executeSql(insertSQL);
    }
}
```

### 4.2 配置文件

```yaml
# src/main/resources/config/application-dev.yml

# Flink 配置
flink:
  # Checkpoint 配置 (保证一致性的核心)
  checkpoint:
    interval: 60000  # 60秒
    mode: EXACTLY_ONCE  # ⭐ 精确一次语义
    timeout: 300000  # 5分钟
    min-pause: 500
    max-concurrent: 1  # 同时只有1个 Checkpoint
  
  # 状态后端配置
  state:
    backend: rocksdb  # 使用 RocksDB 状态后端
    checkpoint-dir: file:///tmp/flink-checkpoints  # Checkpoint 存储目录
    savepoint-dir: file:///tmp/flink-savepoints

# Doris 配置
doris:
  fe:
    http-url: http://127.0.0.1:8030
    jdbc-url: jdbc:mysql://127.0.0.1:9030
    username: root
    password: ""
  
  be:
    nodes: "127.0.0.1:8040"  # BE 节点地址 (两阶段提交需要)
  
  database: crypto_dw
  
  tables:
    ods: ods_crypto_ticker_rt
    dwd: dwd_crypto_ticker_detail
    dws-1min: dws_crypto_ticker_1min
```

---

## 5. 一致性验证方法

### 5.1 验证 Checkpoint 是否启用

```bash
# 查看 Flink Web UI
open http://localhost:8082

# 进入作业详情页面
# 查看 "Checkpoints" 标签页
# 确认:
# - Checkpoint 是否定期触发
# - Checkpoint 是否成功完成
# - Checkpoint 持续时间是否正常
```

### 5.2 验证两阶段提交是否启用

```bash
# 查看 Flink 日志
tail -f logs/flink/flink.log | grep "2pc"

# 应该看到类似日志:
# [INFO] Doris Sink: 2PC enabled, label prefix: flink_dws_1min
# [INFO] Pre-commit: transaction 12345 started
# [INFO] Commit: transaction 12345 committed
```

### 5.3 验证数据一致性

**方法 1: 对比数据量**
```sql
-- 查询 Source 表数据量
SELECT COUNT(*) FROM ods_crypto_ticker_rt;

-- 查询 Sink 表数据量
SELECT COUNT(*) FROM dwd_crypto_ticker_detail;

-- 两者应该一致 (考虑数据过滤)
```

**方法 2: 对比数据内容**
```sql
-- 查询 Source 表的某条数据
SELECT * FROM ods_crypto_ticker_rt 
WHERE inst_id = 'BTC-USDT' 
  AND timestamp = 1711065600000;

-- 查询 Sink 表的对应数据
SELECT * FROM dwd_crypto_ticker_detail 
WHERE inst_id = 'BTC-USDT' 
  AND timestamp = 1711065600000;

-- 数据应该一致 (考虑字段转换)
```

**方法 3: 模拟失败恢复**
```bash
# 1. 启动 Flink 作业
bash run-flink-dwd-sql.sh

# 2. 等待几分钟,让数据写入

# 3. 强制杀死 Flink 作业
kill -9 <flink_pid>

# 4. 重新启动 Flink 作业
bash run-flink-dwd-sql.sh

# 5. 验证数据是否一致
# - 没有数据丢失
# - 没有数据重复
```

---

## 6. 常见问题和解决方案

### 6.1 Checkpoint 超时

**现象**:
```
Checkpoint 12345 expired before completing
```

**原因**:
- Checkpoint 持续时间超过 timeout 配置
- 数据量太大,写入 Doris 太慢
- Doris 性能问题

**解决方案**:
```yaml
# 增加 Checkpoint 超时时间
flink:
  checkpoint:
    timeout: 600000  # 10分钟

# 增加批量大小,减少写入次数
doris:
  sink:
    buffer-flush.max-rows: 10000  # 增加到 10000
```

### 6.2 Label Already Exists 错误

**现象**:
```
Label already exists: flink_dws_1min_12345
```

**原因**:
- Label 重复,Doris 拒绝写入
- 通常是作业重启导致

**解决方案**:
```java
// 使用时间戳作为 Label 前缀
'sink.label-prefix' = 'flink_dws_1min_' + System.currentTimeMillis()

// 或者清理旧的 Label
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SHOW LOAD WHERE LABEL LIKE 'flink_dws_1min%';
-- 删除失败的 Label
CANCEL LOAD WHERE LABEL = 'flink_dws_1min_12345';
"
```

### 6.3 数据重复

**现象**:
- Sink 表中有重复数据

**原因**:
- 两阶段提交未启用
- Checkpoint 配置错误

**解决方案**:
```java
// 1. 确认启用两阶段提交
'sink.enable-2pc' = 'true'

// 2. 确认 Checkpoint 模式
flink.checkpoint.mode = EXACTLY_ONCE

// 3. 使用 DUPLICATE KEY 或 UNIQUE KEY 去重
CREATE TABLE dws_crypto_ticker_1min (
    ...
) DUPLICATE KEY(inst_id, window_start, window_end)  -- 去重
```

---

## 7. 推荐的作业运行方式 ⭐⭐⭐

### 7.1 混合架构 (最佳实践)

根据 Source 类型选择不同的运行方式:

**实时层** (持续运行 - 7x24 小时):
```bash
# Kafka Source (无界流) - 持续运行
bash run-flink-collector.sh &       # WebSocket → Kafka
bash run-flink-ods-sql.sh &         # Kafka → Doris ODS
```

**批处理层** (定时运行 - Cron 调度):
```bash
# Doris Source (有界流) - 定时运行
# 每小时执行一次
0 * * * * /path/to/run-flink-dwd-sql.sh
0 * * * * /path/to/run-flink-dws-1min-sql.sh
0 * * * * /path/to/run-flink-ads-realtime-metrics.sh
```

**为什么这样设计?**

| 作业 | Source 类型 | 运行方式 | 原因 |
|------|------------|---------|------|
| FlinkDataCollectorJob | WebSocket | 持续运行 | 实时采集数据 |
| FlinkODSJobSQL | Kafka | 持续运行 | Kafka 支持持续消费 |
| FlinkDWDJobSQL | Doris | 定时运行 | Doris Source 一次性读取 |
| FlinkDWSJob1MinSQL | Doris | 定时运行 | Doris Source 一次性读取 |
| FlinkADSRealtimeMetricsJob | Doris | 定时运行 | Doris Source 一次性读取 |

### 7.2 配合时间过滤使用

```sql
-- 在 Doris Source 作业中使用时间过滤
-- 只处理最近1小时的数据,避免重复处理

-- FlinkDWDJobSQL
SELECT * FROM ods_crypto_ticker_rt 
WHERE ingest_time >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000 
  AND ingest_time < UNIX_TIMESTAMP(NOW()) * 1000

-- FlinkDWSJob1MinSQL
SELECT * FROM dwd_crypto_ticker_detail 
WHERE process_time >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000 
  AND process_time < UNIX_TIMESTAMP(NOW()) * 1000

-- FlinkADSRealtimeMetricsJob
SELECT * FROM dws_crypto_ticker_1min 
WHERE window_end >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000 
  AND window_end < UNIX_TIMESTAMP(NOW()) * 1000
```

### 7.3 Cron 调度配置示例

```bash
# 编辑 Cron 任务
crontab -e

# 添加以下内容
# 每小时的第5分钟执行 (避免整点高峰)
5 * * * * /path/to/run-flink-dwd-sql.sh >> /path/to/logs/cron-dwd.log 2>&1
10 * * * * /path/to/run-flink-dws-1min-sql.sh >> /path/to/logs/cron-dws.log 2>&1
15 * * * * /path/to/run-flink-ads-realtime-metrics.sh >> /path/to/logs/cron-ads.log 2>&1

# 查看 Cron 任务
crontab -l

# 查看 Cron 日志
tail -f /path/to/logs/cron-dwd.log
```

## 8. 最佳实践总结

### 8.1 必须配置 ⭐⭐⭐

1. **启用 Checkpoint**
   ```yaml
   flink.checkpoint.mode = EXACTLY_ONCE
   flink.checkpoint.interval = 60000
   ```

2. **启用两阶段提交**
   ```java
   'sink.enable-2pc' = 'true'
   ```

3. **配置 BE 节点地址**
   ```yaml
   doris.be.nodes = "127.0.0.1:8040"
   ```

### 8.2 推荐配置 ⭐⭐

1. **使用分区读取**
   ```sql
   SELECT * FROM source_table 
   WHERE partition_date = '2026-03-22'
   ```

2. **配合时间过滤**
   ```sql
   -- 只处理最近的数据
   WHERE ingest_time >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
   ```

3. **配置合理的批量大小**
   ```java
   'sink.buffer-flush.max-rows' = '1000'
   'sink.buffer-flush.interval' = '5s'
   ```

4. **使用 RocksDB 状态后端**
   ```yaml
   flink.state.backend = rocksdb
   ```

### 8.3 监控指标 ⭐

1. **Checkpoint 指标**
   - Checkpoint 成功率
   - Checkpoint 持续时间
   - Checkpoint 大小

2. **Doris 写入指标**
   - 写入 TPS
   - 写入延迟
   - 失败次数

3. **数据一致性指标**
   - Source 数据量
   - Sink 数据量
   - 数据差异

---

## 9. 总结

### 9.1 端到端一致性保证机制

```
Doris Source (分区读取 + 时间过滤)
  ↓
Flink 处理 (Checkpoint + EXACTLY_ONCE)
  ↓
Doris Sink (两阶段提交)
  ↓
端到端精确一次语义 ✅
```

### 9.2 Checkpoint 保存内容总结

| 组件 | 是否保存状态 | 保存内容 | 恢复行为 |
|------|-------------|----------|----------|
| Kafka Source | ✅ 是 | Partition Offset | 从 Offset 继续消费 |
| Doris Source | ❌ 否 | 无 | 重新读取全表/分区 |
| 窗口算子 | ✅ 是 | 窗口数据 | 恢复窗口状态 |
| 聚合算子 | ✅ 是 | 聚合结果 | 恢复聚合状态 |
| Doris Sink | ✅ 是 | 事务 ID、缓存数据 | 重新提交事务 |

### 9.3 Doris Source 关键特性

| 问题 | 答案 |
|------|------|
| 每次启动读取全量表吗? | 默认是,除非使用 WHERE 过滤 |
| Source 表一直更新能一直读取吗? | ❌ 不能,读取完就结束 |
| Checkpoint 保存读取位置吗? | ❌ 不保存,重启后重新读取 |
| 如何读取新数据? | 定时重启作业或使用时间过滤 |
| 适合什么场景? | 批处理、离线分析、定时 ETL |
| 推荐运行方式? | 定时调度 (Cron/Airflow) |

### 9.4 关键配置清单

| 组件 | 配置项 | 值 | 说明 |
|------|--------|-----|------|
| Flink | checkpoint.mode | EXACTLY_ONCE | 精确一次语义 |
| Flink | checkpoint.interval | 60000 | Checkpoint 间隔 |
| Doris Sink | sink.enable-2pc | true | 启用两阶段提交 |
| Doris Sink | sink.label-prefix | flink_{table} | Label 前缀 |
| Doris | be.nodes | 127.0.0.1:8040 | BE 节点地址 |

### 9.5 验证清单

- ✅ Checkpoint 定期触发且成功
- ✅ Doris Sink 日志显示 2PC 启用
- ✅ Source 和 Sink 数据量一致
- ✅ 模拟失败恢复后数据一致
- ✅ 没有 Label 冲突错误
- ✅ 理解 Doris Source 不保存读取位置
- ✅ 使用定时调度处理批处理作业

---

## 参考资料

1. [Flink Checkpoint 官方文档](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/dev/datastream/fault-tolerance/checkpointing/)
2. [Flink Doris Connector 官方文档](https://doris.apache.org/zh-CN/docs/ecosystem/flink-doris-connector)
3. [Doris Stream Load 官方文档](https://doris.apache.org/zh-CN/docs/data-operate/import/stream-load-manual)
4. [两阶段提交协议 (2PC)](https://en.wikipedia.org/wiki/Two-phase_commit_protocol)
