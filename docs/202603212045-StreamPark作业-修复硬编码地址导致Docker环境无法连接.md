# StreamPark 作业提交 - 修复硬编码地址导致 Docker 环境无法连接

## 问题描述

在 StreamPark 中提交 Flink 作业时，即使设置了 `APP_ENV=docker`，仍然出现以下错误：

```
org.apache.doris.flink.exception.DorisRuntimeException: No Doris FE is available, please check configuration or cluster status.
```

**问题现象**:
- 本地运行正常（使用 `APP_ENV=dev`）
- Docker 环境运行失败（使用 `APP_ENV=docker`）
- 网络连通性测试通过（`docker exec flink-jobmanager curl http://doris-fe:8030` 成功）

## 根本原因

**代码中硬编码了 localhost 地址** ⭐⭐⭐

在 `FlinkODSJobDataStream.java` 中，有多处硬编码的 `localhost`：

```java
// 问题代码 1：硬编码 REST API 地址
flinkConfig.setString("rest.address", "localhost");  // ❌ 硬编码

// 问题代码 2：硬编码 Pushgateway 地址
MetricsConfig.configurePushgatewayReporter(
    flinkConfig,
    "localhost",  // ❌ 硬编码
    9091,
    "flink-ods-job"
);
```

**为什么会导致问题**:

1. **在 Docker 容器中运行时**：
   - `localhost` 指向容器自身，而不是宿主机
   - Pushgateway 在另一个容器中，无法通过 `localhost` 访问
   - REST API 绑定到 `localhost` 导致外部无法访问

2. **配置文件无法覆盖硬编码**：
   - 即使在 `application-docker.yml` 中配置了正确的地址
   - 代码中的硬编码会覆盖配置文件
   - `APP_ENV=docker` 无法生效

## 解决方案

### 1. 修改代码，从配置文件读取地址 ✅

**修改 FlinkODSJobDataStream.java**:

```java
// 修复前：硬编码
flinkConfig.setString("rest.address", "localhost");  // ❌

// 修复后：从配置文件读取
flinkConfig.setString("rest.address", 
    config.getString("flink.web.address", "0.0.0.0"));  // ✅
```

```java
// 修复前：硬编码 Pushgateway 地址
MetricsConfig.configurePushgatewayReporter(
    flinkConfig,
    "localhost",  // ❌ 硬编码
    9091,
    "flink-ods-job"
);

// 修复后：从配置文件读取
String pushgatewayHost = config.getString("application.metrics.pushgateway.host", "localhost");
int pushgatewayPort = config.getInt("application.metrics.pushgateway.port", 9091);

MetricsConfig.configurePushgatewayReporter(
    flinkConfig,
    pushgatewayHost,  // ✅ 从配置读取
    pushgatewayPort,  // ✅ 从配置读取
    "flink-ods-job"
);
```

### 2. 更新配置文件 ✅

**application-dev.yml（本地开发环境）**:

```yaml
# Flink Web UI 配置
flink:
  web:
    address: 0.0.0.0  # 监听所有网络接口
    port: 8081

# 应用配置
application:
  logging:
    level: DEBUG
  
  # 监控配置
  metrics:
    pushgateway:
      host: localhost  # 本地 Pushgateway 地址
      port: 9091
```

**application-docker.yml（Docker 环境）**:

```yaml
# Flink Web UI 配置
flink:
  web:
    address: 0.0.0.0  # 监听所有网络接口
    port: 8081

# 应用配置
application:
  logging:
    level: INFO
  
  # 监控配置
  metrics:
    pushgateway:
      host: pushgateway  # Docker 容器名称
      port: 9091
```

### 3. 重新编译项目 ✅

```bash
mvn clean package -DskipTests
```

### 4. 在 StreamPark 中重新提交作业 ✅

- Main Class: `com.crypto.dw.jobs.FlinkODSJobDataStream`
- **Dynamic Properties**: `APP_ENV=docker`  ← 关键配置
- 上传新编译的 JAR 包

## 验证结果

1. ✅ 代码已修复，不再硬编码地址
2. ✅ 配置文件已更新，支持本地和 Docker 环境
3. ✅ 项目重新编译成功
4. ⏳ 等待在 StreamPark 中验证

## 关键经验

### 1. 避免硬编码 ⭐⭐⭐

**❌ 错误做法**:
```java
// 硬编码地址
String host = "localhost";
int port = 9091;
```

**✅ 正确做法**:
```java
// 从配置文件读取，提供默认值
String host = config.getString("application.metrics.pushgateway.host", "localhost");
int port = config.getInt("application.metrics.pushgateway.port", 9091);
```

### 2. 配置文件分层 ⭐⭐

```
application.yml          # 基础配置（默认值）
├── application-dev.yml      # 开发环境（localhost）
└── application-docker.yml   # Docker 环境（容器名称）
```

### 3. 环境变量切换 ⭐

```bash
# 本地开发
export APP_ENV=dev

# Docker 环境
export APP_ENV=docker

# 或在 StreamPark 中设置
Dynamic Properties: APP_ENV=docker
```

### 4. 容器间通信 ⭐⭐⭐

**在 Docker 网络中**:
- ✅ 使用容器名称：`pushgateway`、`doris-fe`、`kafka`
- ❌ 不要使用 `localhost` 或 `127.0.0.1`
- ❌ 不要硬编码 IP 地址

**REST API 绑定地址**:
- ✅ 使用 `0.0.0.0`：监听所有网络接口
- ❌ 使用 `localhost`：只能本地访问

## 常见错误和解决方法

### 错误 1: No Doris FE is available

**原因**: 代码中硬编码了 `localhost`，配置文件无法覆盖

**解决**: 
1. 检查代码中是否有硬编码的地址
2. 确保所有地址都从配置文件读取
3. 重新编译项目

### 错误 2: Connection refused

**原因**: REST API 绑定到 `localhost`，外部无法访问

**解决**:
```yaml
flink:
  web:
    address: 0.0.0.0  # 监听所有网络接口
```

### 错误 3: 配置文件不生效

**原因**: 
1. 没有设置 `APP_ENV` 环境变量
2. 配置文件没有打包到 JAR 中
3. 代码中硬编码覆盖了配置

**解决**:
```bash
# 1. 检查环境变量
echo $APP_ENV

# 2. 检查 JAR 包中的配置文件
jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep config

# 3. 检查代码中是否有硬编码
grep -r "localhost" src/main/java/
```

## 最佳实践

### 1. 配置优先级

```
代码硬编码 > 环境变量 > 配置文件 > 默认值
```

**推荐顺序**:
1. 配置文件（最灵活）
2. 环境变量（敏感信息）
3. 默认值（兜底）
4. ❌ 避免硬编码

### 2. 配置读取模式

```java
// 推荐：提供默认值
String host = config.getString("key", "default");

// 不推荐：没有默认值，配置缺失时报错
String host = config.getString("key");
```

### 3. 日志输出

```java
logger.info("Pushgateway 配置:");
logger.info("  Host: {}", pushgatewayHost);
logger.info("  Port: {}", pushgatewayPort);
```

**好处**:
- 方便调试
- 快速定位配置问题
- 验证配置是否正确加载

## 相关文件

- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - 修复硬编码
- `src/main/resources/config/application-dev.yml` - 开发环境配置
- `src/main/resources/config/application-docker.yml` - Docker 环境配置
- `target/realtime-crypto-datawarehouse-1.0.0.jar` - 重新编译的 JAR 包

## 参考文档

- [配置管理 - 添加 Docker 环境配置文件](202603212040-配置管理-添加Docker环境配置文件.md)
- [Docker 网络 - 解决 Flink 无法访问 Doris 的网络隔离问题](202603212030-Docker网络-解决Flink无法访问Doris的网络隔离问题.md)
- [StreamPark 作业提交 - 配置主类和参数](202603212005-StreamPark作业提交-配置主类和参数.md)

---

**创建时间**: 2026-03-21 20:45  
**问题类型**: 代码硬编码  
**解决状态**: ✅ 已修复，等待验证
