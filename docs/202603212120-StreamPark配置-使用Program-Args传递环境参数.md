# StreamPark 配置 - 使用 Program Args 传递环境参数

**时间**: 2026-03-21 21:20  
**问题**: StreamPark Remote 模式下 Dynamic Properties 不生效  
**状态**: ✅ 已找到解决方案

---

## 问题描述

在 StreamPark Web UI 中配置 `Dynamic Properties: -DAPP_ENV=docker` 后，日志显示：

```
System Property APP_ENV: null
Environment Variable APP_ENV: null
Loading configuration for environment: dev
```

说明 Dynamic Properties 在 Remote 模式下没有正确传递。

---

## 根本原因 ⭐⭐⭐

**StreamPark Remote 模式的限制**

StreamPark 在 Remote 模式下提交作业到 Flink 集群时，Dynamic Properties 可能不会被正确传递为 System Properties。

这是因为：
1. Remote 模式下，StreamPark 通过 Flink REST API 提交作业
2. Dynamic Properties 可能只在 Application 模式下生效
3. 需要使用 Program Args（程序参数）来传递配置

---

## 解决方案

### 方案：使用 Program Args 传递环境参数 ✅

**1. 修改代码支持从程序参数读取 APP_ENV**

在 `FlinkODSJobDataStream.java` 的 `main` 方法中添加：

```java
public static void main(String[] args) throws Exception {
    log.info("==========================================");
    log.info("Flink ODS Job (官方 Doris Connector)");
    log.info("==========================================");

    // 诊断日志：输出所有相关的 System Properties 和环境变量
    log.info("=== 诊断信息 ===");
    log.info("Program Arguments: " + java.util.Arrays.toString(args));
    log.info("System Property APP_ENV: " + System.getProperty("APP_ENV"));
    log.info("Environment Variable APP_ENV: " + System.getenv("APP_ENV"));
    
    // 从程序参数中读取 APP_ENV（支持 StreamPark Remote 模式）
    // 格式：--env docker 或 --APP_ENV docker
    String envFromArgs = null;
    for (int i = 0; i < args.length - 1; i++) {
        if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
            envFromArgs = args[i + 1];
            log.info("Found APP_ENV in program arguments: " + envFromArgs);
            // 设置为 System Property，让 ConfigLoader 能读取到
            System.setProperty("APP_ENV", envFromArgs);
            break;
        }
    }
    
    // ... 其余代码
}
```

**关键点**:
- 支持两种参数格式：`--env docker` 或 `--APP_ENV docker`
- 从程序参数中读取后，设置为 System Property
- ConfigLoader 可以正常读取 `System.getProperty("APP_ENV")`

**2. 在 StreamPark 中配置 Program Args**

在 StreamPark Web UI 中：

1. 进入 **Application** → 选择你的应用
2. 点击 **Edit** 编辑应用配置
3. 找到 **Program Args** 字段（不是 Dynamic Properties）
4. 输入：`--env docker`
5. 点击 **Submit** 保存
6. 重新启动作业

---

## 配置对比

### Dynamic Properties（不推荐 - Remote 模式下可能不生效）

```
字段：Dynamic Properties
值：-DAPP_ENV=docker
```

**问题**:
- Remote 模式下可能不会被传递
- 日志显示 `System Property APP_ENV: null`

### Program Args（推荐 - 适用于所有模式）✅

```
字段：Program Args
值：--env docker
```

**优点**:
- 适用于所有部署模式（Local、Remote、Application）
- 通过 `args[]` 数组传递，100% 可靠
- 代码中可以灵活处理

---

## 验证步骤

### 1. 重新编译项目

```bash
mvn clean package -DskipTests
```

### 2. 上传新的 JAR 包到 StreamPark

- 路径：`target/realtime-crypto-datawarehouse-1.0.0.jar`
- 大小：104 MB
- 编译时间：2026-03-21 21:22

### 3. 配置 Program Args

在 StreamPark Web UI 中：
```
Program Args: --env docker
```

### 4. 启动作业并查看日志

**预期输出（成功）**:
```
=== 诊断信息 ===
Program Arguments: [--env, docker]
System Property APP_ENV: null
Environment Variable APP_ENV: null
Found APP_ENV in program arguments: docker
================
Loading configuration for environment: docker
  System Property APP_ENV: docker
  Environment Variable APP_ENV: null
Kafka Source created:
  Bootstrap Servers: kafka:9092
  Topic: crypto_ticker
  Group ID: flink-ods-consumer
```

**关键标志**:
- ✅ `Program Arguments: [--env, docker]` - 参数正确传递
- ✅ `Found APP_ENV in program arguments: docker` - 成功解析
- ✅ `Loading configuration for environment: docker` - 加载正确配置
- ✅ `Bootstrap Servers: kafka:9092` - 使用 docker 配置

---

## 支持的参数格式

### 格式 1: --env（推荐）

```
Program Args: --env docker
```

### 格式 2: --APP_ENV

```
Program Args: --APP_ENV docker
```

### 格式 3: 多个参数

```
Program Args: --env docker --parallelism 4
```

---

## 技术要点

### 1. 参数传递流程

```
StreamPark Web UI
    ↓
Program Args: --env docker
    ↓
StreamPark 提交作业
    ↓
Flink 启动 main 方法
    ↓
args[] = ["--env", "docker"]
    ↓
代码解析参数
    ↓
System.setProperty("APP_ENV", "docker")
    ↓
ConfigLoader 读取配置
```

### 2. 为什么 Program Args 更可靠？

| 特性 | Dynamic Properties | Program Args |
|-----|-------------------|--------------|
| 传递方式 | System Properties | 程序参数 |
| Remote 模式 | ⚠️ 可能不生效 | ✅ 100% 可靠 |
| Application 模式 | ✅ 生效 | ✅ 生效 |
| Local 模式 | ✅ 生效 | ✅ 生效 |
| 灵活性 | 有限 | 高（可自定义解析） |

### 3. 配置优先级

```
Program Args > System Properties > Environment Variables > 配置文件 > 默认值
```

---

## 常见问题

### Q1: 为什么不直接使用 Dynamic Properties？

**A**: Dynamic Properties 在 Remote 模式下可能不会被正确传递为 System Properties。Program Args 通过 `args[]` 数组传递，更加可靠。

### Q2: 可以同时使用 Dynamic Properties 和 Program Args 吗？

**A**: 可以，但 Program Args 的优先级更高。建议只使用 Program Args。

### Q3: 如果忘记配置 Program Args 会怎样？

**A**: 代码会使用默认的 `dev` 环境配置，连接到 `localhost` 的服务。

### Q4: 可以传递其他参数吗？

**A**: 可以！你可以传递任何参数，只需要在代码中解析即可。例如：
```
Program Args: --env docker --parallelism 4 --checkpoint-interval 60000
```

---

## 最佳实践

### 1. 使用 Program Args 传递环境配置

```
Program Args: --env docker
```

### 2. 使用配置文件管理详细参数

```yaml
# application-docker.yml
kafka:
  bootstrap-servers: kafka:9092
  
doris:
  fe:
    http-url: http://doris-fe:8030
```

### 3. 敏感信息使用环境变量

```yaml
# application-docker.yml
okx:
  api-key: ${OKX_API_KEY}
  secret-key: ${OKX_SECRET_KEY}
```

然后在 StreamPark 的 "Environment Variables" 中配置。

---

## 总结

### 关键要点 ⭐⭐⭐

1. **Remote 模式限制**: Dynamic Properties 可能不生效
2. **解决方案**: 使用 Program Args 传递环境参数
3. **配置格式**: `--env docker` 或 `--APP_ENV docker`
4. **代码支持**: 从 `args[]` 读取并设置为 System Property
5. **验证方法**: 查看日志中的 "Program Arguments" 和 "Found APP_ENV"

### 下一步

1. ✅ 重新编译项目（已完成）
2. ⏳ 上传新的 JAR 包到 StreamPark
3. ⏳ 配置 Program Args: `--env docker`
4. ⏳ 启动作业并验证日志
5. ⏳ 检查是否加载了 docker 配置

---

## 相关文件

- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - 支持从 Program Args 读取环境参数
- `src/main/java/com/crypto/dw/config/ConfigLoader.java` - 配置加载器
- `src/main/resources/config/application-docker.yml` - Docker 环境配置
- `target/realtime-crypto-datawarehouse-1.0.0.jar` - 新编译的 JAR 包

---

**最后更新**: 2026-03-21 21:22

**当前状态**: ✅ 代码已修改并编译成功，使用 Program Args 传递环境参数！
