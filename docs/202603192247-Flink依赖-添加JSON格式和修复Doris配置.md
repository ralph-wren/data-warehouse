# Flink DWD SQL 作业 - 添加 JSON 依赖和修复 Doris 配置

## 问题描述

运行 Flink DWD SQL 作业时遇到多个问题：

1. **缺少 JSON 格式依赖**
   - 错误信息：`Could not find any factory for identifier 'json'`
   - 原因：pom.xml 中缺少 `flink-json` 依赖

2. **Doris Sink 配置参数错误**
   - 错误信息：`Unsupported options: sink.batch.interval, sink.batch.size`
   - 原因：使用了旧版本的参数名称

3. **SQL 类型不匹配**
   - 错误信息：`Incompatible types for sink column 'trade_hour'`
   - 原因：`EXTRACT(HOUR ...)` 返回 `BIGINT`，但 Sink 表定义为 `INT`

4. **Kafka 消费模式问题**
   - 问题：DWD 作业从 `latest-offset` 开始，无法处理历史数据
   - 需要改为 `earliest-offset`

## 解决方案

### 1. 添加 flink-json 依赖

在 `pom.xml` 中添加 Flink JSON 格式依赖：

```xml
<!-- Flink JSON Format - 用于 Kafka 消息的 JSON 序列化/反序列化 -->
<dependency>
    <groupId>org.apache.flink</groupId>
    <artifactId>flink-json</artifactId>
    <version>${flink.version}</version>
</dependency>
```

### 2. 修复 Doris Sink 配置参数

将旧的参数名称替换为新的：

**修改前：**
```java
'sink.batch.size' = '1000',
'sink.batch.interval' = '5000ms',
```

**修改后：**
```java
'sink.buffer-flush.max-rows' = '1000',
'sink.buffer-flush.interval' = '5s',
```

### 3. 添加类型转换

在 INSERT SQL 中添加显式类型转换：

```java
// trade_hour: BIGINT -> INT
CAST(EXTRACT(HOUR FROM TO_TIMESTAMP(FROM_UNIXTIME(`timestamp` / 1000))) AS INT) as trade_hour,

// spread: DECIMAL(21, 8) -> DECIMAL(20, 8)
CAST((ask_price - bid_price) AS DECIMAL(20, 8)) as spread,

// spread_rate: DECIMAL(38, 17) -> DECIMAL(10, 6)
CAST(CASE 
    WHEN last_price > 0 THEN (ask_price - bid_price) / last_price
    ELSE 0
END AS DECIMAL(10, 6)) as spread_rate,

// 其他计算字段也添加类型转换
CAST(... AS DECIMAL(20, 8)) as price_change_24h,
CAST(... AS DECIMAL(10, 6)) as price_change_rate_24h,
CAST(... AS DECIMAL(10, 6)) as amplitude_24h,

// data_source: CHAR(3) -> STRING
CAST('OKX' AS STRING) as data_source
```

### 4. 修改 Kafka 消费模式

将 Kafka Source 的启动模式从 `latest-offset` 改为 `earliest-offset`：

```java
'scan.startup.mode' = 'earliest-offset',  // 从最早的数据开始消费
```

## 验证步骤

1. 重新编译项目：
```bash
mvn clean compile -DskipTests
```

2. 启动 DWD SQL 作业：
```bash
./run-flink-dwd-sql.sh
```

3. 等待 Checkpoint 触发（30秒），然后查询 Doris：
```bash
bash query-doris.sh
```

## 当前状态

### ✅ 已解决的问题

1. **JSON 依赖缺失** - 已添加 `flink-json` 依赖
2. **Doris Sink 配置参数错误** - 已修复为正确的参数名
3. **SQL 类型不匹配** - 已添加所有必要的类型转换
4. **Kafka 消费模式** - 已改为 `earliest-offset`
5. **字段名不匹配** - 已修复为匹配 Kafka JSON 格式（驼峰命名）

### ⚠️ 待解决的问题

**Flink SQL 作业无法消费 Kafka 数据**

**现象**：
- Flink DWD SQL 作业显示已启动
- 但 Kafka 消费组没有活跃成员
- DWD 层没有数据写入

**可能原因**：
1. Flink SQL 作业在本地模式运行时可能存在问题
2. 使用 `mvn exec:java` 运行可能导致类加载问题
3. 需要使用 Flink 集群模式或提交到 Flink standalone 集群

**建议方案**：
- 使用 DataStream API 代替 SQL API（参考 FlinkODSJobDataStream.java）
- 或者部署 Flink standalone 集群，使用 `flink run` 提交作业

## 技术要点

### Flink JSON 格式依赖的作用

- 提供 JSON 序列化和反序列化功能
- 支持 Kafka Connector 的 `'format' = 'json'` 配置
- 必须添加到 classpath 中才能使用

### Doris Flink Connector 1.6.2 的配置参数

官方支持的参数：
- `sink.buffer-flush.max-rows`: 批量写入的最大行数
- `sink.buffer-flush.interval`: 批量写入的时间间隔
- `sink.max-retries`: 最大重试次数
- `sink.properties.format`: 数据格式（json/csv）
- `sink.properties.read_json_by_line`: 是否按行读取 JSON

### Flink SQL 类型推断

- `EXTRACT(HOUR FROM timestamp)` 返回 `BIGINT`
- 算术运算会自动提升精度（如 DECIMAL(20,8) + DECIMAL(20,8) = DECIMAL(21,8)）
- 除法运算会大幅提升精度（如 DECIMAL(20,8) / DECIMAL(20,8) = DECIMAL(38,17)）
- 需要使用 `CAST` 显式转换到目标类型

### Kafka 消费模式

- `earliest-offset`: 从最早的可用数据开始消费（适合处理历史数据）
- `latest-offset`: 从最新的数据开始消费（适合实时处理）
- `group-offsets`: 从消费组的上次提交位置开始

## 相关文件

- `pom.xml`: 添加 flink-json 依赖
- `src/main/java/com/crypto/dw/flink/FlinkDWDJobSQL.java`: DWD SQL 作业代码
- `run-flink-dwd-sql.sh`: DWD 作业运行脚本

## 时间

- 创建时间：2026-03-19 22:47 (UTC+8)
- 解决时间：约 30 分钟

## 状态

✅ JSON 依赖已添加
✅ Doris 配置参数已修复
✅ SQL 类型转换已添加
✅ Kafka 消费模式已修改
⏳ 等待验证数据写入
