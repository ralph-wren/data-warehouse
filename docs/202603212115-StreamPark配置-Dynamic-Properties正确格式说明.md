# StreamPark 配置 - Dynamic Properties 正确格式说明

**时间**: 2026-03-21 21:15  
**问题**: StreamPark Dynamic Properties 配置格式不正确，导致 APP_ENV 无法读取  
**状态**: ✅ 已找到正确格式

---

## 问题描述

在 StreamPark Web UI 中配置 `Dynamic Properties: APP_ENV=docker` 后，Flink 作业仍然加载 `dev` 配置。

**错误配置**:
```
APP_ENV=docker
```

**日志显示**:
```
Loading configuration for environment: dev
Bootstrap Servers: localhost:9092
```

---

## 根本原因

**Dynamic Properties 格式不正确** ⭐⭐

根据 StreamPark 官方文档，Dynamic Properties 应该使用 Flink 的标准 `-D` 参数格式。

StreamPark 会将 Dynamic Properties 转换为 Flink 的 `-D` 参数，然后传递给 Flink 作业。这些参数会被设置为 Java System Properties（`System.getProperty()`），而不是环境变量（`System.getenv()`）。

---

## 正确配置格式

### 格式 1: 使用 -D 前缀（推荐）✅

在 StreamPark Web UI 的 Dynamic Properties 字段中输入：

```
-DAPP_ENV=docker
```

或者多个属性（每行一个）：

```
-DAPP_ENV=docker
-Dparallelism.default=4
-Dtaskmanager.numberOfTaskSlots=2
```

### 格式 2: 不使用 -D 前缀（可选）

```
APP_ENV=docker
```

StreamPark 会自动添加 `-D` 前缀。

### 格式 3: 多个属性用空格分隔

```
-DAPP_ENV=docker -Dparallelism.default=4
```

---

## StreamPark Dynamic Properties 工作原理

### 1. 配置流程

```
StreamPark Web UI
    ↓
Dynamic Properties: -DAPP_ENV=docker
    ↓
StreamPark 后端处理
    ↓
转换为 Flink 启动参数: flink run -DAPP_ENV=docker ...
    ↓
Flink 将参数设置为 System Properties
    ↓
Java 程序: System.getProperty("APP_ENV") = "docker"
```

### 2. 参数类型

| 参数类型 | 读取方式 | StreamPark 支持 |
|---------|---------|----------------|
| System Property | `System.getProperty("key")` | ✅ 支持（Dynamic Properties） |
| Environment Variable | `System.getenv("key")` | ❌ 不支持 |
| Program Arguments | `args[]` | ✅ 支持（Program Args） |

### 3. 配置位置

在 StreamPark Web UI 中：

1. 进入 "Application" → 选择你的应用
2. 点击 "Edit" 编辑应用配置
3. 找到 "Dynamic Properties" 字段
4. 输入：`-DAPP_ENV=docker`
5. 点击 "Submit" 保存
6. 重新启动作业

---

## 验证步骤

### 1. 配置 Dynamic Properties

在 StreamPark Web UI 中配置：
```
-DAPP_ENV=docker
```

### 2. 上传最新的 JAR 包

确保使用包含诊断日志的最新 JAR 包：
```bash
# 路径
target/realtime-crypto-datawarehouse-1.0.0.jar

# 大小
104 MB

# 编译时间
2026-03-21 21:10
```

### 3. 提交作业

在 StreamPark 中点击 "Start" 启动作业。

### 4. 查看日志

在 StreamPark 的 "Log" 标签页中查看作业日志，搜索 "=== 诊断信息 ==="。

**预期输出（成功）**:
```
=== 诊断信息 ===
System Property APP_ENV: docker
Environment Variable APP_ENV: null
所有 System Properties:
  APP_ENV = docker
================
Loading configuration for environment: docker
  System Property APP_ENV: docker
  Environment Variable APP_ENV: null
Kafka Source created:
  Bootstrap Servers: kafka:9092
  Topic: crypto_ticker
  Group ID: flink-ods-consumer
```

**错误输出（失败）**:
```
Loading configuration for environment: dev
Kafka Source created:
  Bootstrap Servers: localhost:9092
```

---

## 常见问题排查

### Q1: 配置了 -DAPP_ENV=docker，但仍然加载 dev 配置

**可能原因**:
1. ❌ 使用了旧的 JAR 包（没有诊断日志）
2. ❌ StreamPark 缓存了旧的配置
3. ❌ 格式不正确（有多余的空格或特殊字符）

**解决方法**:
```bash
# 1. 重新编译项目
mvn clean package -DskipTests

# 2. 在 StreamPark 中删除旧的 JAR 包
# 3. 上传新的 JAR 包
# 4. 重新配置 Dynamic Properties: -DAPP_ENV=docker
# 5. 重启作业
```

### Q2: 诊断日志显示 System Property APP_ENV: null

**可能原因**:
1. ❌ Dynamic Properties 格式不正确
2. ❌ StreamPark 版本不支持 Dynamic Properties
3. ❌ 配置没有保存

**解决方法**:
```
1. 检查 Dynamic Properties 格式：-DAPP_ENV=docker
2. 确保点击了 "Submit" 保存配置
3. 尝试不使用 -D 前缀：APP_ENV=docker
4. 检查 StreamPark 版本（建议 2.1.0+）
```

### Q3: 可以配置多个 Dynamic Properties 吗？

**可以！** 有两种方式：

**方式 1: 每行一个属性（推荐）**
```
-DAPP_ENV=docker
-Dparallelism.default=4
-Dtaskmanager.numberOfTaskSlots=2
```

**方式 2: 用空格分隔**
```
-DAPP_ENV=docker -Dparallelism.default=4 -Dtaskmanager.numberOfTaskSlots=2
```

### Q4: Dynamic Properties 和 Program Args 有什么区别？

| 特性 | Dynamic Properties | Program Args |
|-----|-------------------|--------------|
| 用途 | 设置 System Properties | 传递命令行参数 |
| 读取方式 | `System.getProperty()` | `args[]` 数组 |
| 格式 | `-Dkey=value` | 任意字符串 |
| 适用场景 | 配置参数、环境变量 | 输入文件、业务参数 |

---

## StreamPark 配置最佳实践

### 1. 使用 Dynamic Properties 配置环境

```
-DAPP_ENV=docker
```

**优点**:
- 不需要修改代码
- 可以在 StreamPark UI 中动态修改
- 支持不同环境（dev、test、prod）

### 2. 使用配置文件管理参数

```yaml
# application-docker.yml
kafka:
  bootstrap-servers: kafka:9092
  
doris:
  fe:
    http-url: http://doris-fe:8030
```

**优点**:
- 配置集中管理
- 易于维护和版本控制
- 支持复杂的配置结构

### 3. 配置优先级

```
Dynamic Properties (-D) > 配置文件 > 默认值
```

### 4. 敏感信息处理

对于敏感信息（如密码、API Key），建议使用环境变量：

```yaml
# application-docker.yml
okx:
  api-key: ${OKX_API_KEY}
  secret-key: ${OKX_SECRET_KEY}
```

然后在 StreamPark 的 "Environment Variables" 中配置。

---

## 参考文档

### StreamPark 官方文档

- **配置文档**: https://streampark.apache.org/docs/framework/config
- **部署文档**: https://streampark.apache.org/docs/user-guide/deployment
- **变量管理**: https://streampark.apache.org/docs/platform/variable

### Flink 官方文档

- **配置参数**: https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/config/
- **应用参数**: https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/dev/datastream/application_parameters/

---

## 总结

### 关键要点 ⭐⭐⭐

1. **Dynamic Properties 格式**: 使用 `-DAPP_ENV=docker`（带 -D 前缀）
2. **参数类型**: Dynamic Properties 会被设置为 System Properties
3. **读取方式**: 使用 `System.getProperty("APP_ENV")`
4. **配置位置**: StreamPark Web UI → Application → Edit → Dynamic Properties
5. **验证方法**: 查看作业日志中的诊断信息

### 下一步

1. ✅ 在 StreamPark 中配置 Dynamic Properties: `-DAPP_ENV=docker`
2. ✅ 上传最新的 JAR 包（包含诊断日志）
3. ✅ 启动作业并查看日志
4. ✅ 验证是否加载了 docker 配置
5. ✅ 检查 Kafka 和 Doris 连接是否正常

---

## 相关文件

- `src/main/java/com/crypto/dw/config/ConfigLoader.java` - 配置加载器（支持 System Property）
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - 主程序（包含诊断日志）
- `src/main/resources/config/application-docker.yml` - Docker 环境配置
- `test/test-streampark-dynamic-properties.sh` - Dynamic Properties 格式测试脚本

---

**最后更新**: 2026-03-21 21:15

**当前状态**: ✅ 已找到正确的 Dynamic Properties 格式，等待验证！
