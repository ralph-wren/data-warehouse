---
inclusion: auto
description: 项目代码规范和架构约束 - 自动加载到每次对话中
keywords: 代码规范, 架构约束, 最佳实践, 命名规范, SQL分离, 工厂模式
---
# 项目代码规范和架构约束

## 项目信息
- 项目名称: 实时加密货币数据仓库
- 技术栈: Flink 1.17.2 + Kafka + Doris + Redis
- Java 版本: 11
- 编码: UTF-8
- 时区: Asia/Shanghai (东八区)

---

## 1. 代码规范

### 1.1 Java 代码规范

#### 命名规范
- **类名**: 大驼峰命名法 (PascalCase)
  - 示例: `FlinkEnvironmentFactory`, `KafkaSourceFactory`
  - 工厂类必须以 `Factory` 结尾
  - 作业类必须以 `Job` 结尾

- **方法名**: 小驼峰命名法 (camelCase)
  - 示例: `createStreamEnvironment()`, `loadSql()`
  - 创建方法以 `create` 开头
  - 加载方法以 `load` 开头
  - 配置方法以 `configure` 开头

- **变量名**: 小驼峰命名法 (camelCase)
  - 示例: `kafkaSource`, `tableEnv`, `checkpointInterval`
  - 常量使用全大写下划线分隔: `MAX_RETRIES`, `DEFAULT_TIMEOUT`

- **包名**: 全小写，使用点分隔
  - 示例: `com.crypto.dw.flink.factory`

#### 注释规范
- **类注释**: 必须包含 JavaDoc
  ```java
  /**
   * 类功能说明
   * 
   * 详细描述:
   * - 功能点 1
   * - 功能点 2
   * 
   * 使用示例:
   * <pre>
   * // 示例代码
   * </pre>
   * 
   * @author 作者
   * @date 日期
   */
  ```

- **方法注释**: 公共方法必须包含 JavaDoc
  ```java
  /**
   * 方法功能说明
   * 
   * @param paramName 参数说明
   * @return 返回值说明
   * @throws ExceptionType 异常说明
   */
  ```

- **行内注释**: 关键逻辑必须添加注释
  ```java
  // 重构说明: 使用工厂类创建环境，减少重复代码
  FlinkEnvironmentFactory factory = new FlinkEnvironmentFactory(config);
  ```

#### 代码格式
- **缩进**: 4 个空格（不使用 Tab）
- **行宽**: 建议不超过 120 字符
- **空行**: 方法之间空一行，逻辑块之间空一行
- **导入**: 按字母顺序排列，不使用通配符 `*`

### 1.2 SQL 代码规范

#### SQL 文件规范
- **文件位置**: 所有 SQL 文件必须放在 `sql/` 目录下
  - Flink SQL: `sql/flink/`
  - Doris SQL: `sql/doris/`
  - DDL: `sql/flink/ddl/`

- **文件命名**: 小写字母，下划线分隔
  - 格式: `{层级}_{操作}.sql`
  - 示例: `ods_insert.sql`, `dwd_insert.sql`, `dws_1min_insert.sql`

- **文件注释**: 每个 SQL 文件开头必须包含
  ```sql
  -- 功能说明
  -- 数据流转说明
  -- 计算逻辑说明
  -- 注意事项
  ```

#### SQL 格式规范
- **关键字**: 大写 (SELECT, FROM, WHERE, INSERT, UPDATE)
- **字段**: 小写，下划线分隔
- **缩进**: 4 个空格
- **注释**: 使用 `--` 进行单行注释
- **字段分组**: 添加注释说明每组字段的用途

#### SQL 加载规范
- **禁止硬编码**: 不允许在 Java 代码中硬编码 SQL
- **统一加载**: 必须使用 `SqlFileLoader.loadSql()` 加载
  ```java
  String sql = SqlFileLoader.loadSql("sql/flink/ods_insert.sql");
  ```

---

## 2. 架构约束

### 2.1 分层架构

#### 数据流转
```
OKX API → Kafka → ODS → DWD → DWS → ADS
```

#### 层级职责
- **ODS (操作数据层)**: 原始数据存储，添加数据源和摄入时间
- **DWD (明细数据层)**: 数据清洗、字段补充、衍生字段计算
- **DWS (汇总数据层)**: 窗口聚合、K 线生成、统计指标计算
- **ADS (应用数据层)**: 业务指标计算、实时监控、套利分析

### 2.2 工厂模式

#### 必须使用工厂类
- **FlinkEnvironmentFactory**: 创建 Flink 执行环境
  - 统一管理 Checkpoint、并行度、Web UI 等配置
  - 支持本地模式和远程集群模式

- **FlinkTableFactory**: 创建 Flink 表（Source/Sink）
  - 统一管理 Kafka Source、Doris Source/Sink 的 DDL
  - 支持显式传入库名和表名

- **KafkaSourceFactory**: 创建 Kafka Source
  - 统一管理 Kafka 连接配置
  - 支持不同作业使用不同的 Consumer Group

- **DorisSinkFactory**: 创建 Doris Sink
  - 统一管理 Doris 连接配置
  - 支持显式传入库名和表名

#### 禁止重复代码
- ❌ 禁止在每个作业中重复创建环境
- ❌ 禁止在每个作业中重复创建表
- ✅ 必须使用工厂类创建环境和表

### 2.3 配置管理

#### 配置文件
- **位置**: `src/main/resources/config/`
- **命名**: `application-{env}.yml`
  - `application-dev.yml`: 本地开发环境（宿主机地址）
  - `application-docker.yml`: Docker 容器环境（容器名称）

#### 环境切换
- **参数**: 使用 `--APP_ENV` 参数选择配置文件
  ```bash
  # 本地模式
  java -jar app.jar --APP_ENV dev
  
  # 远程集群模式
  java -jar app.jar --APP_ENV docker
  ```

- **禁止嵌套**: 不允许在配置文件中使用嵌套结构区分环境
- **禁止判断**: 不允许在代码中根据环境判断使用哪个配置

#### 配置读取
- **统一工具**: 必须使用 `ConfigLoader.getInstance()` 读取配置
- **类型安全**: 使用类型安全的方法
  ```java
  String value = config.getString("key");
  int value = config.getInt("key");
  boolean value = config.getBoolean("key");
  ```

### 2.4 SQL 分离

#### 强制要求
- ✅ 所有 SQL 必须分离到独立文件
- ❌ 禁止在 Java 代码中硬编码 SQL
- ✅ 必须使用 `SqlFileLoader` 加载 SQL

#### SQL 文件组织
```
sql/
├── flink/              # Flink SQL
│   ├── ddl/            # DDL 定义
│   ├── ods_insert.sql  # ODS 层
│   ├── dwd_insert.sql  # DWD 层
│   ├── dws_1min_insert.sql  # DWS 层
│   └── ads_realtime_metrics_insert.sql  # ADS 层
└── doris/              # Doris 建表 SQL
```

---

## 3. 最佳实践

### 3.1 Flink 作业

#### 环境创建
```java
// ✅ 正确：使用工厂类
FlinkEnvironmentFactory factory = new FlinkEnvironmentFactory(config);
StreamExecutionEnvironment env = factory.createStreamEnvironment("job-name", 8081);

// ❌ 错误：直接创建环境
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
```

#### 表创建
```java
// ✅ 正确：使用工厂类，显式传入库名和表名
FlinkTableFactory tableFactory = new FlinkTableFactory(config);
String ddl = tableFactory.createDorisSinkTable(
    "sink_table",
    "crypto_dw",      // 显式传入数据库名
    "ods_ticker",     // 显式传入表名
    "label_prefix",
    schema
);

// ❌ 错误：硬编码 DDL
String ddl = "CREATE TABLE ...";
```

#### SQL 加载
```java
// ✅ 正确：从文件加载
String sql = SqlFileLoader.loadSql("sql/flink/ods_insert.sql");

// ❌ 错误：硬编码 SQL
String sql = "INSERT INTO ...";
```

### 3.2 时区设置

#### 必须设置时区
```java
// Flink Table Environment 必须设置时区
tableEnv.getConfig().setLocalTimeZone(java.time.ZoneId.of("Asia/Shanghai"));
```

#### 日期计算
```sql
-- 使用 FROM_UNIXTIME 和 TO_TIMESTAMP 时注意时区
CAST(FROM_UNIXTIME(ts / 1000, 'yyyy-MM-dd') AS DATE) as trade_date
```

### 3.3 Checkpoint 配置

#### 推荐配置
```yaml
flink:
  checkpoint:
    interval: 60000           # 60 秒
    timeout: 300000           # 5 分钟
    mode: EXACTLY_ONCE        # 精准一次
    min-pause: 10000          # 10 秒
    max-concurrent: 1         # 最多 1 个并发
```

#### Doris Sink 配置
```java
// 数据质量配置
'sink.properties.strict_mode' = 'false',        // 关闭严格模式
'sink.properties.max_filter_ratio' = '0.1',     // 允许 10% 过滤
'sink.max-retries' = '5',                       // 重试 5 次
```

### 3.4 日志规范

#### 日志级别
- **DEBUG**: 详细的调试信息
- **INFO**: 关键流程信息（推荐）
- **WARN**: 警告信息
- **ERROR**: 错误信息

#### 日志内容
```java
// ✅ 正确：包含上下文信息
logger.info("Creating Kafka Source for job: {}, topic: {}", jobName, topic);

// ❌ 错误：信息不足
logger.info("Creating Kafka Source");
```

#### 日志配置
- **位置**: `src/main/resources/log4j2.properties`
- **滚动**: 每 3 小时或 100MB
- **保留**: 3 天自动删除
- **分类**: 按组件分目录存储

---

## 4. 禁止事项

### 4.1 代码层面

❌ **禁止硬编码**
- 禁止硬编码 SQL 语句
- 禁止硬编码配置参数
- 禁止硬编码连接地址

❌ **禁止重复代码**
- 禁止在多个作业中重复创建环境
- 禁止在多个作业中重复创建表
- 禁止复制粘贴相同逻辑

❌ **禁止不安全操作**
- 禁止使用 `System.exit()`
- 禁止捕获异常后不处理
- 禁止使用过时的 API

### 4.2 配置层面

❌ **禁止混乱配置**
- 禁止在配置文件中嵌套环境配置
- 禁止在代码中根据环境判断配置
- 禁止使用魔法数字（必须定义常量）

❌ **禁止不规范命名**
- 禁止使用拼音命名
- 禁止使用无意义的变量名（如 a, b, c）
- 禁止使用过长的变量名（超过 30 字符）

### 4.3 SQL 层面

❌ **禁止 SQL 硬编码**
- 禁止在 Java 代码中拼接 SQL
- 禁止在 Java 代码中定义 SQL 常量
- 禁止使用字符串拼接构建 SQL

❌ **禁止不规范 SQL**
- 禁止使用 `SELECT *`（必须明确列出字段）
- 禁止使用不带 WHERE 的 UPDATE/DELETE
- 禁止使用不带 LIMIT 的大表查询

---

## 5. 文档规范

### 5.1 问题解决文档

#### 文件命名
- **格式**: `{日期}-{功能}-{具体问题}.md`
- **示例**: `202603251900-SQL分离完善-ODS和ADS层SQL文件化.md`
- **位置**: `docs/` 目录

#### 文档结构
```markdown
# 标题

**日期**: YYYY-MM-DD HH:MM
**类型**: 问题类型
**状态**: ✅ 已完成 / ⚠️ 进行中 / ❌ 失败

---

## 问题背景
描述问题的背景和现象

## 根本原因
分析问题的根本原因

## 解决方案
详细的解决方案和实施步骤

## 验证结果
验证结果和测试数据

## 技术要点
关键技术点和注意事项

## 相关文档
相关文档链接

---

**作者**: 作者名
**完成时间**: YYYY-MM-DD HH:MM
```

### 5.2 问题汇总

#### 更新规则
- 每次解决问题后必须更新 `问题解决汇总.md`
- 必须包含问题编号、时间、描述、解决方案
- 必须标注问题状态和重要程度（⭐）

#### 分类规则
- 按问题类型分类（配置、代码、架构、部署等）
- 按重要程度标注（⭐⭐⭐ 最重要）
- 按状态标注（✅ 已解决 / ⚠️ 部分解决 / ❌ 未解决）

---

## 6. 版本控制

### 6.1 Git 提交规范

#### 提交信息格式
```
<type>(<scope>): <subject>

<body>

<footer>
```

#### Type 类型
- **feat**: 新功能
- **fix**: 修复 bug
- **refactor**: 重构代码
- **docs**: 文档更新
- **style**: 代码格式调整
- **test**: 测试相关
- **chore**: 构建/工具相关

#### 示例
```
feat(sql): 完成 ODS 和 ADS 层 SQL 分离

- 创建 ods_insert.sql 和 ads_realtime_metrics_insert.sql
- 修改 FlinkODSJobSQL 和 FlinkADSRealtimeMetricsJob 使用 SqlFileLoader
- 更新 SQL README 文档

Closes #72
```

### 6.2 分支管理

#### 分支命名
- **主分支**: `main` / `master`
- **开发分支**: `develop`
- **功能分支**: `feature/功能名称`
- **修复分支**: `fix/问题描述`
- **发布分支**: `release/版本号`

---

## 7. 测试规范

### 7.1 单元测试

#### 测试文件位置
- **位置**: `src/test/java/`
- **命名**: `{类名}Test.java`
- **示例**: `SqlFileLoaderTest.java`

#### 测试方法命名
- **格式**: `test{方法名}_{场景}_{预期结果}`
- **示例**: `testLoadSql_FileExists_ReturnsContent()`

### 7.2 集成测试

#### 测试脚本位置
- **位置**: `test/` 目录
- **命名**: `test-{功能}.sh`
- **示例**: `test-checkpoint-issue.sh`

---

## 8. 部署规范

### 8.1 环境配置

#### 环境类型
- **dev**: 本地开发环境（宿主机）
- **docker**: Docker 容器环境
- **prod**: 生产环境（待定）

#### 配置切换
```bash
# 本地开发
java -jar app.jar --APP_ENV dev

# Docker 容器
java -jar app.jar --APP_ENV docker
```

### 8.2 启动脚本

#### 脚本命名
- **格式**: `run-flink-{层级}-{模式}.sh`
- **示例**: `run-flink-ods-datastream.sh`

#### 脚本内容
```bash
#!/bin/bash
# 脚本说明
# 使用方法

# 设置环境变量
export APP_ENV=dev

# 编译项目
mvn clean compile -DskipTests

# 运行作业
java -cp target/classes:target/lib/* \
  com.crypto.dw.flink.FlinkODSJobDataStream \
  --APP_ENV ${APP_ENV}
```

---

## 9. 监控和运维

### 9.1 日志监控

#### 日志位置
```
logs/
├── app/          # 应用日志
├── flink/        # Flink 框架日志
├── doris/        # Doris 连接器日志
├── kafka/        # Kafka 连接器日志
└── error/        # 错误日志
```

#### 监控命令
```bash
# 查看应用日志
tail -f logs/app/flink-app.log

# 查看错误日志
tail -f logs/error/error.log

# 监控日志大小
du -sh logs/*/
```

### 9.2 性能监控

#### 关键指标
- **吞吐量**: records/second
- **延迟**: end-to-end latency
- **错误率**: failed / total
- **资源使用**: CPU、内存、磁盘

#### 监控工具
- **Flink Web UI**: http://localhost:8081
- **Prometheus**: 指标采集
- **Grafana**: 可视化监控

---

## 10. 更新记录

| 日期 | 版本 | 更新内容 | 作者 |
|------|------|---------|------|
| 2026-03-25 | 1.0.0 | 初始版本，定义代码规范和架构约束 | Kiro AI |

---

## 11. 自动加载配置

### Kiro Steering 文件

为了让 AI 助手自动读取代码规范，本文件的核心内容已转换为 Kiro Steering 文件：

**文件位置**: `.kiro/steering/code-standards.md`

**配置说明**:
```markdown
---
inclusion: auto
description: 项目代码规范和架构约束 - 自动加载到每次对话中
keywords: 代码规范, 架构约束, 最佳实践, 命名规范, SQL分离, 工厂模式
---
```

**自动加载机制**:
- `inclusion: auto` - 每次对话时自动加载到 AI 助手的上下文中
- AI 助手会自动遵循这些规范生成代码
- 无需手动引用或提醒

**文件关系**:
- `.harness` - 完整的代码规范文档（人类阅读）
- `.kiro/steering/code-standards.md` - 精简的规范文件（AI 自动加载）

### 使用建议

**开发者**:
- 阅读完整的 `.harness` 文件了解所有规范
- 代码审查时使用 `.harness` 作为检查清单

**AI 助手**:
- 自动加载 `.kiro/steering/code-standards.md`
- 生成代码时自动遵循规范
- 无需每次对话都提醒规范

---

## 12. 参考资源

### 官方文档
- [Apache Flink 官方文档](https://nightlies.apache.org/flink/flink-docs-release-1.17/)
- [Apache Doris 官方文档](https://doris.apache.org/docs/)
- [Apache Kafka 官方文档](https://kafka.apache.org/documentation/)

### 项目文档
- [问题解决汇总](./问题解决汇总.md)
- [SQL README](./sql/README.md)
- [AGENTS 规范](./AGENTS.md)
- [代码规范 Steering 文件](./.kiro/steering/code-standards.md) ⭐ 自动加载

---

**维护者**: Kiro AI Assistant  
**最后更新**: 2026-03-25 19:15  
**版本**: 1.0.1