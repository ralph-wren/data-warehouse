# 代码重构 - 封装 Flink Environment 创建逻辑

**时间**: 2026-03-22 06:45  
**类型**: 代码重构  
**状态**: ✅ 完成

---

## 问题背景

在之前的开发中,每个 Flink 作业都需要手动创建和配置 Flink Environment,存在大量重复代码:
- Web UI 配置 (约 10 行)
- Prometheus Metrics 配置 (约 10 行)
- 并行度配置 (约 2 行)
- Checkpoint 配置 (约 2 行)
- Table Environment 创建 (约 2 行)
- 时区配置 (约 1 行)

每个作业都需要重复这些配置,总计约 30 行重复代码。

### 旧代码问题

**FlinkDWDJobSQL.java** (约 40 行配置代码):
```java
// 创建 Flink 执行环境(启用 Web UI 和 Metrics)
Configuration flinkConfig = new Configuration();

// 启用 Web UI(注意:端口参数必须是 int 类型)
flinkConfig.setBoolean("web.submit.enable", true);
flinkConfig.setBoolean("web.cancel.enable", true);
flinkConfig.setInteger("rest.port", 8082);
flinkConfig.setString("rest.address", "localhost");
flinkConfig.setString("rest.bind-port", "8082-8090");

// 配置 Prometheus Metrics(推送到 Pushgateway)
MetricsConfig.configurePushgatewayReporter(
    flinkConfig,
    "localhost",
    9091,
    "flink-dwd-job"
);

// 配置通用 Metrics 选项
MetricsConfig.configureCommonMetrics(flinkConfig);

// 创建 Flink 执行环境
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);

// 设置并行度
int parallelism = config.getInt("flink.execution.parallelism", 4);
env.setParallelism(parallelism);

// 启用 Checkpoint
long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
env.enableCheckpointing(checkpointInterval);

// 创建 Table Environment
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);

// 打印环境信息
logger.info("Flink Environment:");
logger.info("  Parallelism: " + parallelism);
logger.info("  Checkpoint Interval: " + checkpointInterval + " ms");
```

**FlinkDWSJob1MinSQL.java** (类似的 30+ 行重复代码)

---

## 解决方案

### 创建 FlinkEnvironmentFactory 工厂类

封装 Flink Environment 的创建逻辑,提供统一的创建接口。

**文件位置**: `src/main/java/com/crypto/dw/flink/factory/FlinkEnvironmentFactory.java`

**核心功能**:
1. **创建 DataStream Environment**: 用于 DataStream API 作业
2. **创建 Table Environment**: 用于 Table API / SQL 作业
3. **配置 Web UI**: 端口、地址、Flamegraph
4. **配置 Prometheus Metrics**: Pushgateway Reporter
5. **配置并行度**: 从配置文件读取
6. **配置 Checkpoint**: 从配置文件读取
7. **配置时区**: 自动设置为东八区(Asia/Shanghai)

**关键代码**:

```java
/**
 * Flink Environment 工厂类
 * 
 * 封装 Flink 执行环境的创建逻辑,避免重复代码
 */
public class FlinkEnvironmentFactory {
    
    private final ConfigLoader config;
    
    public FlinkEnvironmentFactory(ConfigLoader config) {
        this.config = config;
    }
    
    /**
     * 创建 StreamExecutionEnvironment (DataStream API)
     * 
     * @param jobName 作业名称(用于 Metrics 标识)
     * @param webUIPort Web UI 端口(避免端口冲突)
     * @return 配置好的 StreamExecutionEnvironment
     */
    public StreamExecutionEnvironment createStreamEnvironment(String jobName, int webUIPort) {
        // 创建 Flink 配置
        Configuration flinkConfig = createFlinkConfiguration(jobName, webUIPort);
        
        // 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        
        // 配置并行度
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        // 配置 Checkpoint
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 30000);
        env.enableCheckpointing(checkpointInterval);
        
        // 打印环境信息
        logEnvironmentInfo(parallelism, checkpointInterval, webUIPort);
        
        return env;
    }
    
    /**
     * 创建 StreamTableEnvironment (Table API / SQL)
     * 
     * @param jobName 作业名称(用于 Metrics 标识)
     * @param webUIPort Web UI 端口(避免端口冲突)
     * @return 配置好的 StreamTableEnvironment
     */
    public StreamTableEnvironment createTableEnvironment(String jobName, int webUIPort) {
        // 创建 Flink 配置
        Configuration flinkConfig = createFlinkConfiguration(jobName, webUIPort);
        
        // 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        
        // 配置并行度和 Checkpoint
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000);
        env.enableCheckpointing(checkpointInterval);
        
        // 创建 Table Environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        
        // 设置时区为中国时区(东八区) - 重要:确保日期计算正确
        tableEnv.getConfig().setLocalTimeZone(java.time.ZoneId.of("Asia/Shanghai"));
        
        // 打印环境信息
        logEnvironmentInfo(parallelism, checkpointInterval, webUIPort);
        
        return tableEnv;
    }
    
    /**
     * 创建 Flink Configuration
     * 配置 Web UI 和 Prometheus Metrics
     */
    private Configuration createFlinkConfiguration(String jobName, int webUIPort) {
        Configuration flinkConfig = new Configuration();
        
        // 配置 Web UI
        configureWebUI(flinkConfig, webUIPort);
        
        // 配置 Prometheus Metrics
        configureMetrics(flinkConfig, jobName);
        
        return flinkConfig;
    }
}
```

---

## 使用示例

### 1. 更新 FlinkDWDJobSQL.java

**修改前** (约 40 行配置代码):
```java
// 创建 Flink 执行环境(启用 Web UI 和 Metrics)
Configuration flinkConfig = new Configuration();
// ... 30+ 行配置代码
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
// ... 10+ 行配置代码
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
```

**修改后** (只需 3 行代码):
```java
// 使用工厂类创建 Flink Table Environment(减少重复代码)
FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dwd-job", 8082);
```

**代码减少**: 从 40 行减少到 3 行,减少了 92% 的代码量

### 2. 更新 FlinkDWSJob1MinSQL.java

**修改前** (约 30 行配置代码):
```java
// 创建 Flink 执行环境
StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment();
StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
// ... 20+ 行配置代码
```

**修改后** (只需 3 行代码):
```java
// 使用工厂类创建 Flink Table Environment(减少重复代码)
FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dws-1min-job", 8083);
```

**代码减少**: 从 30 行减少到 3 行,减少了 90% 的代码量

---

## 重构优势

### 1. 代码量大幅减少
- FlinkDWDJobSQL: 从 40 行减少到 3 行 (减少 92%)
- FlinkDWSJob1MinSQL: 从 30 行减少到 3 行 (减少 90%)
- 总计减少约 70 行重复代码

### 2. 配置统一管理
- Web UI 配置集中在工厂类中
- Metrics 配置集中在工厂类中
- 时区配置自动设置(东八区)
- 修改一处,所有作业生效

### 3. 端口冲突自动避免
- 每个作业指定不同的 Web UI 端口
- ODS 作业: 8081
- DWD 作业: 8082
- DWS 作业: 8083

### 4. 时区问题自动解决
- Table Environment 自动设置时区为 Asia/Shanghai
- 避免日期计算错误(如分区不匹配问题)
- 确保 Flink SQL 日期函数正确工作

### 5. 可维护性提升
- 新增作业更简单: 只需调用工厂方法
- 配置修改更容易: 只需修改工厂类
- 代码更清晰: 业务逻辑与环境配置分离

---

## 验证结果

### 编译检查
```bash
$ getDiagnostics("src/main/java/com/crypto/dw/flink/factory/FlinkEnvironmentFactory.java")
✅ No errors found (只有 Lombok 警告,可忽略)

$ getDiagnostics("src/main/java/com/crypto/dw/flink/FlinkDWDJobSQL.java")
✅ No errors found

$ getDiagnostics("src/main/java/com/crypto/dw/flink/FlinkDWSJob1MinSQL.java")
✅ No errors found (只有 Lombok 警告,可忽略)
```

### 代码对比

| 作业 | 修改前行数 | 修改后行数 | 减少行数 | 减少比例 |
|------|-----------|-----------|---------|---------|
| FlinkDWDJobSQL | 40 | 3 | 37 | 92% |
| FlinkDWSJob1MinSQL | 30 | 3 | 27 | 90% |
| **总计** | **70** | **6** | **64** | **91%** |

---

## 工厂类特性

### 1. 支持两种 Environment

**DataStream Environment** (用于 DataStream API):
```java
StreamExecutionEnvironment env = envFactory.createStreamEnvironment("flink-ods-job", 8081);
```

**Table Environment** (用于 Table API / SQL):
```java
StreamTableEnvironment tableEnv = envFactory.createTableEnvironment("flink-dwd-job", 8082);
```

### 2. 自动配置功能

- ✅ Web UI (端口、地址、Flamegraph)
- ✅ Prometheus Metrics (Pushgateway Reporter)
- ✅ 并行度 (从配置文件读取)
- ✅ Checkpoint (从配置文件读取)
- ✅ 时区 (自动设置为 Asia/Shanghai)
- ✅ 日志输出 (环境信息)

### 3. 灵活的端口配置

每个作业可以指定不同的 Web UI 端口,避免端口冲突:
```java
// ODS 作业: 8081
envFactory.createStreamEnvironment("flink-ods-job", 8081);

// DWD 作业: 8082
envFactory.createTableEnvironment("flink-dwd-job", 8082);

// DWS 作业: 8083
envFactory.createTableEnvironment("flink-dws-1min-job", 8083);
```

### 4. 详细的日志输出

工厂类会自动输出环境配置信息:
```
==========================================
创建 Flink Table Environment
作业名称: flink-dwd-job
==========================================
Web UI 配置:
  端口: 8082
  地址: http://localhost:8082
Metrics 配置:
  Pushgateway: localhost:9091
  作业名称: flink-dwd-job
==========================================
Flink Environment 配置:
  并行度: 4
  Checkpoint 间隔: 60000 ms
  Web UI: http://localhost:8082
  时区: Asia/Shanghai (东八区)
==========================================
```

---

## 技术要点

### 1. 工厂模式
- 封装对象创建逻辑
- 提供统一的创建接口
- 隐藏复杂的配置细节

### 2. DRY 原则
- Don't Repeat Yourself
- 避免代码重复
- 提高代码复用性

### 3. 单一职责
- 工厂类负责创建 Environment
- 业务代码只关注业务逻辑
- 配置与业务分离

### 4. 配置集中
- 所有配置在一个地方管理
- 易于维护和修改
- 减少配置错误

### 5. 时区处理
- 自动设置为 Asia/Shanghai (东八区)
- 避免 Flink SQL 日期计算错误
- 解决分区不匹配问题

---

## 相关文件

### 新增文件
- `src/main/java/com/crypto/dw/flink/factory/FlinkEnvironmentFactory.java` - Environment 工厂类

### 已更新的 Flink 作业
- `src/main/java/com/crypto/dw/flink/FlinkDWDJobSQL.java` - DWD 作业(已使用工厂类)
- `src/main/java/com/crypto/dw/flink/FlinkDWSJob1MinSQL.java` - DWS 作业(已使用工厂类)

### 待更新的作业
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - ODS 作业(可选,DataStream API)

---

## 后续优化建议

### 1. 扩展工厂类支持更多配置
- 支持自定义 Checkpoint 策略
- 支持自定义 State Backend
- 支持自定义 Restart Strategy

### 2. 添加配置验证
- 验证端口是否可用
- 验证配置参数是否合法
- 提供友好的错误提示

### 3. 支持更多 Environment 类型
- Batch Environment
- Hybrid Environment (Batch + Stream)

### 4. 添加性能监控
- 自动配置 JMX Metrics
- 自动配置 System Metrics
- 自动配置 Custom Metrics

---

## 总结

通过创建 `FlinkEnvironmentFactory` 工厂类,我们成功地:

1. ✅ **减少了 91% 的环境配置代码** (64 行)
2. ✅ **统一了 Environment 创建方式**
3. ✅ **自动配置了时区** (解决日期计算问题)
4. ✅ **避免了端口冲突** (每个作业独立端口)
5. ✅ **提升了代码的可维护性和可读性**
6. ✅ **为后续扩展打下了良好的基础**

这次重构继续践行 **"DRY (Don't Repeat Yourself)"** 原则,通过工厂模式封装复杂的配置逻辑,大幅提升了代码质量和开发效率。

**配合之前的重构**:
- Table 工厂类: 减少表创建代码 45%
- Environment 工厂类: 减少环境配置代码 91%
- **总体效果**: 代码更简洁、更易维护、更易扩展
