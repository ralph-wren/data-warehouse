# SQL 文件说明

本目录存放项目中使用的所有 SQL 文件，按功能分类组织。

## 目录结构

```
sql/
├── flink/                          # Flink SQL 文件
│   ├── ddl/                        # DDL 定义文件
│   │   ├── kafka_source.sql        # Kafka Source 表定义
│   │   ├── doris_source.sql        # Doris Source 表定义
│   │   └── doris_sink.sql          # Doris Sink 表定义
│   ├── ods_insert.sql              # ODS 层数据插入
│   ├── dwd_insert.sql              # DWD 层数据插入
│   ├── dws_1min_insert.sql         # DWS 1分钟窗口聚合
│   └── ads_realtime_metrics_insert.sql  # ADS 实时指标计算
├── doris/                          # Doris 建表 SQL
│   ├── create_ads_market_monitor_table.sql  # ADS 市场监控表
│   └── create_ads_arbitrage_table.sql       # ADS 套利机会表
└── README.md                       # 本文件
```

## Flink SQL 文件

### ods_insert.sql

**功能**: ODS 层数据插入

**数据流转**: Kafka → ODS 作业 → Doris ODS 表

**主要功能**:
- 从 Kafka 读取原始行情数据
- 添加数据源标识（data_source）
- 添加数据摄入时间（ingest_time）
- 写入 Doris ODS 层

**使用示例**:
```java
String sql = SqlFileLoader.loadSql("sql/flink/ods_insert.sql");
tableEnv.executeSql(sql);
```

---

### dwd_insert.sql

**功能**: DWD 层数据插入

**数据流转**: Kafka → DWD 作业 → Doris DWD 表

**主要功能**:
- 从 Kafka 读取原始数据
- 进行数据清洗和字段补充
- 计算价差、涨跌幅等衍生字段
- 过滤异常数据
- 写入 Doris DWD 层

**使用示例**:
```java
String sql = SqlFileLoader.loadSql("sql/flink/dwd_insert.sql");
tableEnv.executeSql(sql);
```

---

### dws_1min_insert.sql

**功能**: DWS 1分钟窗口聚合

**数据流转**: Kafka → DWS 作业 → Doris DWS 表

**主要功能**:
- 从 Kafka 读取原始数据
- 进行1分钟滚动窗口聚合
- 生成 K 线数据（开高低收）
- 计算涨跌幅、平均价等指标
- 写入 Doris DWS 层

**使用示例**:
```java
String sql = SqlFileLoader.loadSql("sql/flink/dws_1min_insert.sql");
tableEnv.executeSql(sql);
```

---

### ads_realtime_metrics_insert.sql

**功能**: ADS 实时指标计算

**数据流转**: Doris DWS → ADS 作业 → Doris ADS 表

**主要功能**:
- 从 DWS 层读取 1分钟 K 线数据
- 使用 LAG 函数计算历史价格
- 计算多时间窗口涨跌幅（1分钟/5分钟/15分钟/1小时）
- 计算成交量指标（使用窗口函数聚合）
- 计算波动率指标（使用标准差）
- 判断价格趋势（上涨/下跌/震荡）
- 写入 Doris ADS 层

**使用示例**:
```java
String sql = SqlFileLoader.loadSql("sql/flink/ads_realtime_metrics_insert.sql");
tableEnv.executeSql(sql);
```

---

## SQL 文件编写规范

### 1. 文件命名

- 使用小写字母和下划线
- 格式：`{层级}_{操作}.sql`
- 示例：`dwd_insert.sql`、`dws_1min_insert.sql`

### 2. 文件注释

每个 SQL 文件开头必须包含以下注释：

```sql
-- 功能说明
-- 数据流转说明
-- 注意事项
```

### 3. SQL 格式

- 使用标准 SQL 格式
- 关键字大写（SELECT、FROM、WHERE 等）
- 字段分组，添加注释
- 适当的缩进和换行

**示例**:
```sql
-- DWD 层数据插入 SQL
-- 功能：从 Kafka 读取原始数据，进行清洗和字段补充，写入 DWD 层

INSERT INTO doris_dwd_sink
SELECT 
    -- 基础字段
    instId as inst_id,
    ts as `timestamp`,
    
    -- 时间维度字段
    CAST(TO_TIMESTAMP(FROM_UNIXTIME(ts / 1000)) AS DATE) as trade_date,
    
    -- 价格字段
    CAST(last AS DECIMAL(20, 8)) as last_price
FROM kafka_ticker_source
WHERE CAST(last AS DECIMAL(20, 8)) > 0
```

### 4. 字段注释

- 每组字段添加注释说明
- 复杂计算添加详细注释
- 过滤条件添加说明

---

## SQL 文件加载

### Java 代码加载

```java
import com.crypto.dw.utils.SqlFileLoader;

// 加载 SQL 文件
String sql = SqlFileLoader.loadSql("sql/flink/dwd_insert.sql");

// 执行 SQL
tableEnv.executeSql(sql);
```

### 加载路径说明

- 路径相对于 classpath
- 编译后位于 `target/classes/sql/`
- JAR 包中位于 `sql/` 目录

---

## SQL 文件修改

### 1. 修改 SQL 文件

直接编辑 `sql/flink/*.sql` 文件，无需修改 Java 代码。

### 2. 重新编译

```bash
mvn clean compile -DskipTests
```

SQL 文件会自动打包到 JAR 包中。

### 3. 验证

```bash
# 查看打包后的 SQL 文件
ls -lh target/classes/sql/flink/
```

---

## SQL 编辑器推荐

### DataGrip

- JetBrains 出品的数据库工具
- 支持 SQL 语法高亮和格式化
- 支持多种数据库

### DBeaver

- 开源免费的数据库工具
- 支持 SQL 语法高亮和格式化
- 跨平台支持

### VS Code

- 安装 SQL 插件
- 支持语法高亮
- 轻量级

---

## 注意事项

1. **编码格式**: 所有 SQL 文件使用 UTF-8 编码
2. **换行符**: 使用 Unix 风格换行符（LF）
3. **注释**: 使用 `--` 进行单行注释
4. **路径**: SQL 文件路径区分大小写
5. **打包**: 修改 SQL 文件后需要重新编译

---

## 未来扩展

### 计划添加的 SQL 文件

- `dws_5min_insert.sql` - DWS 5分钟窗口聚合
- `dws_1hour_insert.sql` - DWS 1小时窗口聚合
- `ads_market_overview.sql` - ADS 市场概览

### 计划添加的目录

- `sql/init/` - 初始化数据 SQL
- `sql/test/` - 测试 SQL

---

## 相关文档

- [202603241425-SQL分离-将SQL语句从代码中分离到独立文件.md](../docs/202603241425-SQL分离-将SQL语句从代码中分离到独立文件.md)
- [202603241433-SQL全面分离-所有SQL语句模板化管理.md](../docs/202603241433-SQL全面分离-所有SQL语句模板化管理.md)
- [202603251900-SQL分离完善-ODS和ADS层SQL文件化.md](../docs/202603251900-SQL分离完善-ODS和ADS层SQL文件化.md)
- [SqlFileLoader.java](../src/main/java/com/crypto/dw/utils/SqlFileLoader.java)

---

**最后更新**: 2026-03-25 19:00  
**维护者**: Kiro AI Assistant
