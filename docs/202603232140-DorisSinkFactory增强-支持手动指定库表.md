# DorisSinkFactory 增强 - 支持手动指定库名和表名

**日期**: 2026-03-23 21:40  
**需求**: DorisSinkFactory 支持手动传入库名和表名，提高灵活性  
**状态**: ✅ 已完成

---

## 一、需求背景

### 原有设计的局限性

**原有方式**:
```java
// 只能使用预定义的表类型
DorisSink<String> odsSink = factory.createDorisSink("ods");
DorisSink<String> dwdSink = factory.createDorisSink("dwd");

// 表名从配置文件读取，不够灵活
// doris.tables.ods = ods_crypto_ticker_rt
// doris.tables.dwd = dwd_crypto_ticker_detail
```

**局限性**:
1. 只能写入预定义的表（ods/dwd/dws-1min）
2. 无法动态指定库名和表名
3. 无法写入到其他数据库
4. 测试时不够灵活

### 增强后的设计

**新增方式**:
```java
// 方式 1: 使用表类型（保持向后兼容）
DorisSink<String> odsSink = factory.createDorisSink("ods");

// 方式 2: 手动指定库名和表名（新增，更灵活）
DorisSink<String> customSink = factory.createDorisSink(
    "my_database",           // 数据库名
    "my_table",              // 表名
    "my-label-prefix"        // Label 前缀
);
```

**优势**:
- ✅ 向后兼容（原有代码无需修改）
- ✅ 支持动态指定库名和表名
- ✅ 可以写入到任意数据库
- ✅ 测试更灵活

---

## 二、实现方案

### 方法重载设计

```java
public class DorisSinkFactory {
    
    /**
     * 方法 1: 使用表类型（从配置文件读取库名和表名）
     * 
     * @param tableType 表类型（ods/dwd/dws-1min）
     * @return 配置好的 DorisSink
     */
    public DorisSink<String> createDorisSink(String tableType) {
        // 根据表类型获取表名
        String database = config.getString("doris.database", "crypto_dw");
        String table = getDorisTableName(tableType);
        
        // 调用方法 2
        return createDorisSink(database, table, tableType);
    }
    
    /**
     * 方法 2: 手动指定库名和表名（更灵活）
     * 
     * @param database 数据库名称
     * @param table 表名
     * @param labelPrefix Label 前缀（用于去重）
     * @return 配置好的 DorisSink
     */
    public DorisSink<String> createDorisSink(String database, String table, String labelPrefix) {
        // 实际创建 Sink 的逻辑
        // ...
    }
}
```

### 关键改进

1. **方法重载**: 提供两个 `createDorisSink` 方法
2. **向后兼容**: 原有的单参数方法保持不变
3. **代码复用**: 单参数方法内部调用三参数方法
4. **灵活性**: 三参数方法支持任意库名和表名

---

## 三、使用示例

### 示例 1: 使用表类型（原有方式）

```java
ConfigLoader config = ConfigLoader.getInstance();
DorisSinkFactory factory = new DorisSinkFactory(config);

// 创建 ODS Sink（从配置文件读取库名和表名）
DorisSink<String> odsSink = factory.createDorisSink("ods");
// 实际写入: crypto_dw.ods_crypto_ticker_rt

// 创建 DWD Sink
DorisSink<String> dwdSink = factory.createDorisSink("dwd");
// 实际写入: crypto_dw.dwd_crypto_ticker_detail
```

### 示例 2: 手动指定库名和表名

```java
ConfigLoader config = ConfigLoader.getInstance();
DorisSinkFactory factory = new DorisSinkFactory(config);

// 写入到自定义表
DorisSink<String> customSink = factory.createDorisSink(
    "my_database",           // 数据库名
    "my_custom_table",       // 表名
    "custom"                 // Label 前缀
);
// 实际写入: my_database.my_custom_table
```

### 示例 3: 写入到测试数据库

```java
ConfigLoader config = ConfigLoader.getInstance();
DorisSinkFactory factory = new DorisSinkFactory(config);

// 写入到测试数据库（不影响生产数据）
DorisSink<String> testSink = factory.createDorisSink(
    "test_db",               // 测试数据库
    "test_crypto_ticker",    // 测试表
    "test"                   // Label 前缀
);
// 实际写入: test_db.test_crypto_ticker
```

### 示例 4: 动态表名（按日期分表）

```java
ConfigLoader config = ConfigLoader.getInstance();
DorisSinkFactory factory = new DorisSinkFactory(config);

// 按日期分表
String today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
DorisSink<String> dailySink = factory.createDorisSink(
    "crypto_dw",                          // 数据库名
    "ods_crypto_ticker_" + today,         // 表名: ods_crypto_ticker_20260323
    "ods-" + today                        // Label 前缀
);
// 实际写入: crypto_dw.ods_crypto_ticker_20260323
```

### 示例 5: 多租户场景

```java
ConfigLoader config = ConfigLoader.getInstance();
DorisSinkFactory factory = new DorisSinkFactory(config);

// 租户 A
DorisSink<String> tenantASink = factory.createDorisSink(
    "tenant_a_db",           // 租户 A 的数据库
    "crypto_ticker",         // 表名
    "tenant-a"               // Label 前缀
);

// 租户 B
DorisSink<String> tenantBSink = factory.createDorisSink(
    "tenant_b_db",           // 租户 B 的数据库
    "crypto_ticker",         // 表名
    "tenant-b"               // Label 前缀
);
```

---

## 四、完整代码示例

### 在 Flink 作业中使用

```java
public class FlinkCustomJob {
    
    public static void main(String[] args) throws Exception {
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 创建 Flink 执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
        
        // 创建 Kafka Source
        KafkaSource<String> kafkaSource = KafkaSource.<String>builder()
            .setBootstrapServers(config.getString("kafka.bootstrap-servers"))
            .setTopics(config.getString("kafka.topic.crypto-ticker"))
            .setGroupId("custom-consumer")
            .setStartingOffsets(OffsetsInitializer.latest())
            .setValueOnlyDeserializer(new SimpleStringSchema())
            .build();
        
        // 创建数据流
        DataStream<String> stream = env.fromSource(
            kafkaSource,
            WatermarkStrategy.noWatermarks(),
            "Kafka Source"
        );
        
        // 创建 Doris Sink Factory
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        
        // 方式 1: 使用表类型
        DorisSink<String> odsSink = dorisSinkFactory.createDorisSink("ods");
        stream.sinkTo(odsSink).name("ODS Sink");
        
        // 方式 2: 手动指定库名和表名
        DorisSink<String> customSink = dorisSinkFactory.createDorisSink(
            "custom_db",
            "custom_table",
            "custom"
        );
        stream.sinkTo(customSink).name("Custom Sink");
        
        // 执行作业
        env.execute("Custom Flink Job");
    }
}
```

---

## 五、配置文件

### application-dev.yml

```yaml
# Doris 配置
doris:
  # FE 节点配置
  fe:
    http-url: http://127.0.0.1:8030
    jdbc-url: jdbc:mysql://127.0.0.1:9030
    username: root
    password: ""
  
  # BE 节点配置
  be:
    nodes: "127.0.0.1:8040"
  
  # 默认数据库配置
  database: crypto_dw
  
  # 表配置（用于表类型方式）
  tables:
    ods: ods_crypto_ticker_rt
    dwd: dwd_crypto_ticker_detail
    dws-1min: dws_crypto_ticker_1min
    dws-5min: dws_crypto_ticker_5min
    ads: ads_crypto_market_overview
  
  # Stream Load 配置
  stream-load:
    batch-size: 1000
    batch-interval-ms: 5000
    max-retries: 3
```

---

## 六、技术要点

### 1. 方法重载

**优点**:
- 向后兼容（原有代码无需修改）
- 提供多种使用方式
- 代码复用（单参数方法调用三参数方法）

**实现**:
```java
// 单参数方法（向后兼容）
public DorisSink<String> createDorisSink(String tableType) {
    String database = config.getString("doris.database", "crypto_dw");
    String table = getDorisTableName(tableType);
    return createDorisSink(database, table, tableType);
}

// 三参数方法（实际实现）
public DorisSink<String> createDorisSink(String database, String table, String labelPrefix) {
    // 实际创建 Sink 的逻辑
}
```

### 2. Label 前缀

**作用**: 用于 Doris Stream Load 的去重

**格式**: `{labelPrefix}-{timestamp}`

**示例**:
- 表类型方式：`ods-1711209600000`
- 手动指定方式：`custom-1711209600000`

### 3. 配置优先级

**表类型方式**:
1. 从配置文件读取 `doris.database`
2. 从配置文件读取 `doris.tables.{tableType}`

**手动指定方式**:
1. 直接使用传入的 `database` 参数
2. 直接使用传入的 `table` 参数

---

## 七、测试验证

### 编译测试

```bash
mvn clean compile -DskipTests
```

**预期结果**:
```
[INFO] BUILD SUCCESS
```

### 单元测试

```java
@Test
public void testCreateDorisSinkWithTableType() {
    ConfigLoader config = ConfigLoader.getInstance();
    DorisSinkFactory factory = new DorisSinkFactory(config);
    
    // 测试表类型方式
    DorisSink<String> odsSink = factory.createDorisSink("ods");
    assertNotNull(odsSink);
}

@Test
public void testCreateDorisSinkWithCustomTable() {
    ConfigLoader config = ConfigLoader.getInstance();
    DorisSinkFactory factory = new DorisSinkFactory(config);
    
    // 测试手动指定方式
    DorisSink<String> customSink = factory.createDorisSink(
        "test_db",
        "test_table",
        "test"
    );
    assertNotNull(customSink);
}
```

---

## 八、使用场景

### 场景 1: 标准数据仓库分层

```java
// ODS 层
DorisSink<String> odsSink = factory.createDorisSink("ods");

// DWD 层
DorisSink<String> dwdSink = factory.createDorisSink("dwd");

// DWS 层
DorisSink<String> dwsSink = factory.createDorisSink("dws-1min");
```

### 场景 2: 测试环境

```java
// 写入到测试数据库，不影响生产数据
DorisSink<String> testSink = factory.createDorisSink(
    "test_db",
    "test_table",
    "test"
);
```

### 场景 3: 数据归档

```java
// 按月归档数据
String month = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyyMM"));
DorisSink<String> archiveSink = factory.createDorisSink(
    "archive_db",
    "crypto_ticker_" + month,
    "archive-" + month
);
```

### 场景 4: A/B 测试

```java
// A 组数据
DorisSink<String> groupASink = factory.createDorisSink(
    "crypto_dw",
    "ods_crypto_ticker_group_a",
    "group-a"
);

// B 组数据
DorisSink<String> groupBSink = factory.createDorisSink(
    "crypto_dw",
    "ods_crypto_ticker_group_b",
    "group-b"
);
```

---

## 九、注意事项

### 1. 表必须提前创建

**重要**: Doris Sink 不会自动创建表，必须提前创建好表结构

```sql
-- 创建自定义表
CREATE TABLE my_database.my_custom_table (
    inst_id STRING,
    `timestamp` BIGINT,
    last_price DECIMAL(20, 8),
    -- ... 其他字段
) DUPLICATE KEY(inst_id, `timestamp`)
DISTRIBUTED BY HASH(inst_id) BUCKETS 10;
```

### 2. Label 前缀唯一性

**建议**: 不同的 Sink 使用不同的 Label 前缀，避免冲突

```java
// 好的做法
DorisSink<String> sink1 = factory.createDorisSink("db1", "table1", "sink1");
DorisSink<String> sink2 = factory.createDorisSink("db2", "table2", "sink2");

// 不好的做法（Label 可能冲突）
DorisSink<String> sink1 = factory.createDorisSink("db1", "table1", "common");
DorisSink<String> sink2 = factory.createDorisSink("db2", "table2", "common");
```

### 3. 权限检查

**确保**: Doris 用户有权限访问指定的数据库和表

```sql
-- 授权
GRANT ALL ON my_database.* TO 'root'@'%';
```

---

## 十、总结

### 完成的工作

1. ✅ 增加方法重载，支持手动指定库名和表名
2. ✅ 保持向后兼容，原有代码无需修改
3. ✅ 更新类文档注释，说明新的使用方式
4. ✅ 编译成功

### 优势

- ✅ 更灵活：支持动态指定库名和表名
- ✅ 向后兼容：原有代码无需修改
- ✅ 代码复用：单参数方法调用三参数方法
- ✅ 易于测试：可以写入到测试数据库

### 使用建议

- 标准场景：使用表类型方式（简单）
- 特殊场景：使用手动指定方式（灵活）
- 测试场景：使用手动指定方式（隔离）

---

**文档创建时间**: 2026-03-23 21:40  
**作者**: Kiro AI Assistant  
**状态**: ✅ 已完成
