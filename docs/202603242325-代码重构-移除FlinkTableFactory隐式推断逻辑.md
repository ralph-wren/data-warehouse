# 代码重构 - 移除 FlinkTableFactory 隐式推断逻辑

## 问题描述

`FlinkTableFactory` 类中存在隐式推断逻辑，通过表类型字符串（如 "ods"、"dwd"、"dws-1min"）间接推断数据库名和表名，导致代码不够直观，难以理解和维护。

### 问题代码示例

```java
// 隐式推断方式 - 不直观 ❌
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_dwd_sink",
    "dwd",  // 通过字符串推断表名
    TableSchemas.DORIS_DWD_SINK_SCHEMA
);
```

这种方式存在以下问题：
1. 无法直观看出实际操作的数据库和表
2. 需要查看 `getDorisTableName()` 方法才能知道映射关系
3. 修改表名需要同时修改配置文件和代码
4. 不支持动态指定数据库和表

## 解决方案

### 1. 删除隐式推断方法

删除了以下方法：
- `createDorisSinkTable(String tableName, String tableType, String schema)` - 三参数方法
- `getDorisTableName(String tableType)` - 辅助方法

### 2. 统一使用显式参数方法

保留并推荐使用五参数方法：
```java
public String createDorisSinkTable(String tableName, String database, String dorisTable, 
                                   String labelPrefix, String schema)
```

### 3. 更新所有调用位置

#### FlinkODSJobSQL.java
```java
// 重构前 ❌
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_ods_sink",
    "ods",
    TableSchemas.DORIS_ODS_SOURCE_SCHEMA
);

// 重构后 ✅
String database = config.getString("doris.database");
String odsTable = config.getString("doris.tables.ods");
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_ods_sink",
    database,
    odsTable,
    "ods_" + System.currentTimeMillis(),  // Label 前缀
    TableSchemas.DORIS_ODS_SOURCE_SCHEMA
);
```

#### FlinkDWDJobSQL.java
```java
// 重构前 ❌
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_dwd_sink",
    "dwd",
    TableSchemas.DORIS_DWD_SINK_SCHEMA
);

// 重构后 ✅
String database = config.getString("doris.database");
String dwdTable = config.getString("doris.tables.dwd");
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_dwd_sink",
    database,
    dwdTable,
    "dwd_" + System.currentTimeMillis(),  // Label 前缀
    TableSchemas.DORIS_DWD_SINK_SCHEMA
);
```

#### FlinkDWSJob1MinSQL.java
```java
// 重构前 ❌
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_dws_1min_sink",
    "dws-1min",
    TableSchemas.DORIS_DWS_1MIN_SINK_SCHEMA
);

// 重构后 ✅
String database = config.getString("doris.database");
String dwsTable = config.getString("doris.tables.dws-1min");
String dorisSinkDDL = tableFactory.createDorisSinkTable(
    "doris_dws_1min_sink",
    database,
    dwsTable,
    "dws_1min_" + System.currentTimeMillis(),  // Label 前缀
    TableSchemas.DORIS_DWS_1MIN_SINK_SCHEMA
);
```

#### FlinkADSRealtimeMetricsJob.java
```java
// 重构前 ❌
String dwsSourceDDL = tableFactory.createDorisSourceTable(
    "dws_source",
    "dws_crypto_ticker_1min",
    TableSchemas.DORIS_DWS_1MIN_SOURCE_SCHEMA_WITH_WATERMARK
);

String adsSinkDDL = tableFactory.createDorisSinkTable(
    "ads_realtime_metrics_sink",
    "ads_crypto_ticker_realtime_metrics",
    TableSchemas.DORIS_ADS_REALTIME_METRICS_SINK_SCHEMA
);

// 重构后 ✅
String database = config.getString("doris.database");
String dwsTable = config.getString("doris.tables.dws-1min");
String dwsSourceDDL = tableFactory.createDorisSourceTable(
    "dws_source",
    database,
    dwsTable,
    "*",  // 读取所有字段
    TableSchemas.DORIS_DWS_1MIN_SOURCE_SCHEMA_WITH_WATERMARK
);

String adsTable = config.getString("doris.tables.ads");
String adsSinkDDL = tableFactory.createDorisSinkTable(
    "ads_realtime_metrics_sink",
    database,
    adsTable,
    "ads_metrics_" + System.currentTimeMillis(),  // Label 前缀
    TableSchemas.DORIS_ADS_REALTIME_METRICS_SINK_SCHEMA
);
```

## 重构效果

### 优点
1. **代码更直观**：可以直接看到操作的数据库和表名
2. **易于维护**：修改表名只需修改配置文件，不需要修改代码逻辑
3. **灵活性更高**：支持动态指定数据库和表，不受配置文件限制
4. **减少隐式依赖**：消除了对 `getDorisTableName()` 方法的依赖
5. **统一代码风格**：与 `DorisSinkFactory` 的重构保持一致

### 编译验证
```bash
$ mvn clean compile -DskipTests
[INFO] BUILD SUCCESS
[INFO] Total time: 4.790 s
```

## 涉及文件

### 修改的文件
1. `src/main/java/com/crypto/dw/flink/factory/FlinkTableFactory.java`
   - 删除 `createDorisSinkTable(String, String, String)` 三参数方法
   - 删除 `getDorisTableName(String)` 辅助方法

2. `src/main/java/com/crypto/dw/flink/FlinkODSJobSQL.java`
   - 更新 `createDorisSinkTable` 调用，使用显式参数

3. `src/main/java/com/crypto/dw/flink/FlinkDWDJobSQL.java`
   - 更新 `createDorisSinkTable` 调用，使用显式参数

4. `src/main/java/com/crypto/dw/flink/FlinkDWSJob1MinSQL.java`
   - 更新 `createDorisSinkTable` 调用，使用显式参数

5. `src/main/java/com/crypto/dw/flink/FlinkADSRealtimeMetricsJob.java`
   - 更新 `createDorisSourceTable` 调用，使用显式参数
   - 更新 `createDorisSinkTable` 调用，使用显式参数

## 相关重构

本次重构是系列重构的一部分：
1. [202603242304-Kafka配置-为每个Flink作业配置独立Consumer-Group-ID.md](202603242304-Kafka配置-为每个Flink作业配置独立Consumer-Group-ID.md)
2. [202603242235-RocksDB依赖-解决状态后端工厂类找不到错误.md](202603242235-RocksDB依赖-解决状态后端工厂类找不到错误.md)
3. DorisSinkFactory 重构（移除单参数方法）
4. 本次 FlinkTableFactory 重构（移除三参数方法）

## 总结

通过移除隐式推断逻辑，代码变得更加直观和易于维护。所有的数据库名和表名都显式传递，提高了代码的可读性和灵活性。这次重构与之前的 `DorisSinkFactory` 重构保持一致，统一了代码风格。

---

**作者**: Kiro AI Assistant  
**日期**: 2026-03-24 23:25  
**类型**: 代码重构
