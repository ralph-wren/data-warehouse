# JAR 包更新 - StreamPark 重新上传配置文件

**时间**: 2026-03-21 22:00  
**类型**: 部署更新  
**状态**: ⏳ 待执行

## 问题背景

在 StreamPark 中运行 Flink 作业时，出现配置文件找不到的错误：

```
ERROR com.crypto.dw.config.ConfigLoader - Error: Environment-specific config file not found: application-docker.yml
INFO  com.crypto.dw.config.ConfigLoader - Tried paths: config/application-docker.yml, application-docker.yml, /application-docker.yml, /config/application-docker.yml
```

## 问题分析

**根本原因**:
1. 本地已经重新编译了项目，生成了包含新配置文件的 JAR 包
2. JAR 包中配置文件路径正确：`config/application-docker.yml`
3. 但 StreamPark 中使用的仍然是旧的 JAR 包
4. 旧的 JAR 包中可能没有 `application-docker.yml` 文件（因为之前删除了 `application.yml`）

**验证 JAR 包内容**:
```bash
# 检查 JAR 包中的配置文件
jar tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep "application.*yml"

# 输出：
config/application-dev.yml
config/application-docker.yml
config/application-prod.yml
```

配置文件已经正确打包到 JAR 中。

## 解决方案

### 1. 重新上传 JAR 包到 StreamPark

**步骤**:

1. **访问 StreamPark Web UI**:
   - URL: http://localhost:10000
   - 用户名: admin
   - 密码: streampark

2. **进入应用管理**:
   - 点击左侧菜单 "Application" → "Flink Application"
   - 找到你的应用（如 "Flink ODS Job"）

3. **编辑应用配置**:
   - 点击应用右侧的 "Edit" 按钮
   - 或者点击应用名称进入详情页，然后点击 "Edit"

4. **上传新的 JAR 包**:
   - 在 "Application Jar" 部分，点击 "Upload" 按钮
   - 选择本地编译好的 JAR 包：`target/realtime-crypto-datawarehouse-1.0.0.jar`
   - 等待上传完成

5. **保存配置**:
   - 点击 "Submit" 保存配置
   - StreamPark 会自动使用新的 JAR 包

6. **重启应用**:
   - 如果应用正在运行，先点击 "Stop" 停止应用
   - 然后点击 "Start" 启动应用

### 2. 验证配置加载

启动应用后，查看日志：

**预期输出（成功）**:
```
INFO  com.crypto.dw.flink.FlinkODSJobDataStream - System Property APP_ENV: docker
INFO  com.crypto.dw.config.ConfigLoader - Loading configuration for environment: docker
INFO  com.crypto.dw.config.ConfigLoader - Successfully loaded config file from path: config/application-docker.yml
INFO  com.crypto.dw.config.ConfigLoader - Configuration loaded successfully from: application-docker.yml
INFO  com.crypto.dw.config.ConfigLoader - Total config keys: 50
```

**不应该出现的错误**:
```
ERROR com.crypto.dw.config.ConfigLoader - Error: Environment-specific config file not found: application-docker.yml
```

### 3. 检查配置是否生效

查看日志中的 Kafka 和 Doris 配置：

```
INFO  Kafka Source created:
  Bootstrap Servers: kafka:9092
  Topic: crypto_ticker
  Group ID: flink-ods-consumer

INFO  Doris Sink created:
  FE URL: http://doris-fe:8030
  Database: crypto_dw
  Table: ods_crypto_ticker_rt
```

如果地址是 Docker 容器名称（如 `kafka:9092`, `doris-fe:8030`），说明配置加载成功。

## 配置文件变更说明

### 1. 配置文件重构

**之前的结构**:
- `application.yml` - 基础配置
- `application-dev.yml` - 开发环境配置（部分）
- `application-docker.yml` - Docker 环境配置（部分）

**现在的结构**:
- `application-dev.yml` - 完整的开发环境配置
- `application-docker.yml` - 完整的 Docker 环境配置
- `application-prod.yml` - 完整的生产环境配置

### 2. 配置加载逻辑

**之前**:
```java
// 加载基础配置
config = loadYamlFile("application.yml");
// 加载环境配置
Map<String, Object> envConfig = loadYamlFile("application-" + env + ".yml");
// 合并配置
config = mergeConfig(config, envConfig);
```

**现在**:
```java
// 直接加载完整的环境配置
config = loadYamlFile("application-" + env + ".yml");
```

### 3. 优势

- **配置更直观**: 每个环境配置文件都是完整独立的
- **代码更简洁**: 移除了配置合并逻辑
- **维护更方便**: 不需要在多个文件之间切换
- **部署更灵活**: 可以单独复制一个环境配置文件

## 常见问题

### Q1: 上传 JAR 包后仍然报错找不到配置文件

**可能原因**:
1. StreamPark 缓存了旧的 JAR 包
2. 应用没有重启

**解决方法**:
```bash
# 1. 停止应用
# 2. 清理 StreamPark 缓存
docker exec streampark rm -rf /tmp/streampark/workspace/*
# 3. 重新上传 JAR 包
# 4. 启动应用
```

### Q2: 配置文件路径不对

**检查方法**:
```bash
# 解压 JAR 包查看配置文件路径
unzip -l target/realtime-crypto-datawarehouse-1.0.0.jar | grep application

# 应该看到：
config/application-dev.yml
config/application-docker.yml
config/application-prod.yml
```

### Q3: 环境参数没有传递

**检查 Program Args 配置**:
```
--APP_ENV docker
```

或者使用 Dynamic Properties:
```
-DAPP_ENV=docker
```

## 技术要点

1. **JAR 包资源路径**: Maven 打包时，`src/main/resources/config/` 目录下的文件会被打包到 JAR 的 `config/` 目录
2. **ClassLoader 资源加载**: 使用 `getClass().getClassLoader().getResourceAsStream()` 加载资源
3. **多路径尝试**: ConfigLoader 会尝试多个路径，确保在不同环境下都能加载到配置文件
4. **StreamPark JAR 管理**: StreamPark 会将上传的 JAR 包存储在 `/opt/streampark/workspace/` 目录

## 下一步

1. ✅ 本地编译项目生成新的 JAR 包
2. ⏳ 上传 JAR 包到 StreamPark
3. ⏳ 配置 Program Args: `--APP_ENV docker`
4. ⏳ 启动应用并查看日志
5. ⏳ 验证配置加载成功

## 相关文档

- [202603212145-配置文件重构-合并基础配置到环境配置文件.md](./202603212145-配置文件重构-合并基础配置到环境配置文件.md)
- [202603212135-配置文件加载-修复JAR包中配置文件路径问题.md](./202603212135-配置文件加载-修复JAR包中配置文件路径问题.md)
- [202603212120-StreamPark配置-使用Program-Args传递环境参数.md](./202603212120-StreamPark配置-使用Program-Args传递环境参数.md)
- [StreamPark快速提交指南.md](./StreamPark快速提交指南.md)

## 总结

本次更新主要是配置文件重构后，需要重新上传 JAR 包到 StreamPark。新的 JAR 包包含了完整独立的环境配置文件，不再需要基础配置文件。上传新 JAR 包并重启应用后，配置加载应该能够正常工作。
