# StreamPark 编译 - 解决 Java 版本不兼容问题

## 问题描述

在 StreamPark Web UI 中编译项目时失败，报错：

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile 
(default-compile) on project realtime-crypto-datawarehouse: 
Fatal error compiling: invalid target release: 11
```

## 问题现象

1. **Maven 编译失败**：无法编译 Java 11 项目
2. **目标版本无效**：`invalid target release: 11`
3. **StreamPark 构建失败**：无法在 StreamPark 中构建和部署 Flink 作业

## 根本原因 ⭐⭐

**StreamPark 容器内置 Java 8，无法编译 Java 11 项目**

1. **容器 Java 版本**：StreamPark Docker 镜像内置 Java 8 (OpenJDK 1.8.0_462)
2. **项目要求**：pom.xml 配置要求 Java 11
   ```xml
   <maven.compiler.source>11</maven.compiler.source>
   <maven.compiler.target>11</maven.compiler.target>
   ```
3. **版本不匹配**：Java 8 无法编译目标版本为 Java 11 的代码
4. **挂载 Java 失败**：尝试挂载主机 Java 21，但主机是 Windows 版本，无法在 Linux 容器中运行

## 技术细节

### 错误信息

```
[ERROR] Failed to execute goal org.apache.maven.plugins:maven-compiler-plugin:3.11.0:compile 
(default-compile) on project realtime-crypto-datawarehouse: 
Fatal error compiling: invalid target release: 11 -> [Help 1]
```

### 容器 Java 版本

```bash
$ docker exec streampark java -version
Picked up JAVA_TOOL_OPTIONS: -Dfile.encoding=UTF-8
openjdk version "1.8.0_462"
OpenJDK Runtime Environment (build 1.8.0_462-8u462-ga~us1-0ubuntu2~22.04.2-b08)
OpenJDK 64-Bit Server VM (build 25.462-b08, mixed mode)
```

### 项目配置

```xml
<!-- pom.xml -->
<properties>
    <maven.compiler.source>11</maven.compiler.source>
    <maven.compiler.target>11</maven.compiler.target>
</properties>
```

### 尝试的方案（失败）

#### 方案 1：挂载主机 Java 21 ❌

```yaml
# docker-compose-streampark.yml
volumes:
  - E:\DataFiles\Java\amazon_corretto_java21:/opt/java21:ro
environment:
  - MAVEN_OPTS=-Djava.home=/opt/java21
```

**失败原因**：
- 主机 Java 是 Windows 版本（包含 .dll 文件）
- 无法在 Linux 容器中运行
- 需要 Linux 版本的 JDK

#### 方案 2：设置 JAVA_HOME 和 PATH ❌

```yaml
environment:
  - JAVA_HOME=/opt/java21
  - PATH=/opt/java21/bin:$PATH
```

**失败原因**：
- 覆盖 PATH 导致系统命令（tty, uname, dirname）无法找到
- StreamPark 启动脚本依赖这些系统命令
- 容器无法正常启动

## 解决方案

### 方案 1：降级项目到 Java 8（推荐）✅

**修改 `pom.xml`**：

```xml
<properties>
    <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    <!-- 使用 Java 8，兼容 StreamPark 容器 -->
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
    <!-- 使用稳定的 Flink 1.17 版本,兼容性更好 -->
    <flink.version>1.17.2</flink.version>
</properties>
```

**优点**：
- 简单直接，无需修改容器配置
- Flink 1.17 完全支持 Java 8
- 项目代码无需修改（没有使用 Java 11 特性）
- StreamPark 容器可以直接编译

**缺点**：
- 无法使用 Java 11+ 的新特性（如 var、switch 表达式等）
- 但项目当前没有使用这些特性，所以影响不大

### 方案 2：使用自定义 StreamPark 镜像（复杂）❌

构建包含 Java 11 的 StreamPark 镜像：

```dockerfile
FROM apache/streampark:v2.1.5

# 安装 Java 11
RUN apt-get update && \
    apt-get install -y openjdk-11-jdk && \
    update-alternatives --set java /usr/lib/jvm/java-11-openjdk-amd64/bin/java

ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
```

**缺点**：
- 需要构建和维护自定义镜像
- 增加部署复杂度
- 镜像体积增大

### 方案 3：在容器内安装 Java 11（不推荐）❌

```bash
docker exec streampark bash -c "apt-get update && apt-get install -y openjdk-11-jdk"
```

**缺点**：
- 容器重启后配置丢失
- 需要每次手动安装
- 不符合容器化最佳实践

## 实施步骤

### 1. 修改 pom.xml

```bash
# 编辑 pom.xml
vim pom.xml

# 修改 Java 版本
<maven.compiler.source>8</maven.compiler.source>
<maven.compiler.target>8</maven.compiler.target>
```

### 2. 提交更改

```bash
git add pom.xml
git commit -m "fix: 降级 Java 版本到 8，兼容 StreamPark 容器编译"
git push
```

### 3. 重启 StreamPark

```bash
docker-compose -f docker-compose-streampark.yml down
docker-compose -f docker-compose-streampark.yml up -d
```

### 4. 在 StreamPark 中重新添加项目

1. 访问 StreamPark：http://localhost:10000
2. 登录（admin/streampark）
3. 进入 "Project" → 删除旧项目（如果存在）
4. 点击 "Add New" 添加项目
5. 填写 Git URL 和分支
6. 等待克隆和编译完成

## 验证步骤

### 1. 验证 Java 版本

```bash
# 检查容器 Java 版本
docker exec streampark java -version

# 预期输出
openjdk version "1.8.0_462"
```

### 2. 验证项目配置

```bash
# 检查 pom.xml
grep -A 2 "maven.compiler" pom.xml

# 预期输出
<maven.compiler.source>8</maven.compiler.source>
<maven.compiler.target>8</maven.compiler.target>
```

### 3. 测试本地编译

```bash
# 在本地编译项目
mvn clean compile -DskipTests

# 应该成功编译
[INFO] BUILD SUCCESS
```

### 4. 在 StreamPark 中测试编译

1. 在 StreamPark Web UI 中添加项目
2. 等待编译完成
3. 检查日志，应该看到 "BUILD SUCCESS"

## 技术要点

### Java 版本兼容性

| Java 版本 | Flink 1.17 支持 | StreamPark 支持 | 推荐 |
|----------|----------------|----------------|------|
| Java 8   | ✅ 完全支持      | ✅ 内置         | ✅ 推荐 |
| Java 11  | ✅ 完全支持      | ❌ 需要自定义    | ⚠️ 复杂 |
| Java 17  | ✅ 完全支持      | ❌ 需要自定义    | ⚠️ 复杂 |
| Java 21  | ⚠️ 实验性支持    | ❌ 需要自定义    | ❌ 不推荐 |

### Maven Compiler Plugin

```xml
<plugin>
    <groupId>org.apache.maven.plugins</groupId>
    <artifactId>maven-compiler-plugin</artifactId>
    <version>3.11.0</version>
    <configuration>
        <source>8</source>  <!-- 源代码版本 -->
        <target>8</target>  <!-- 目标字节码版本 -->
    </configuration>
</plugin>
```

### Flink 与 Java 版本

Flink 1.17 支持的 Java 版本：
- Java 8：完全支持，推荐用于生产环境
- Java 11：完全支持，推荐用于新项目
- Java 17：完全支持，但部分第三方库可能不兼容

### Docker 容器中的 Java

**Linux 容器需要 Linux 版本的 JDK**：
- Windows JDK：包含 .dll 文件，无法在 Linux 容器中运行
- Linux JDK：包含 .so 文件，可以在 Linux 容器中运行
- 挂载主机 JDK 时必须确保平台匹配

## 常见问题

### Q1: 降级到 Java 8 会影响项目功能吗？

**A**: 
- 不会，项目代码没有使用 Java 11+ 的特性
- Flink 1.17 完全支持 Java 8
- 所有依赖库都兼容 Java 8

### Q2: 如果必须使用 Java 11 怎么办？

**A**: 
- 方案 1：构建自定义 StreamPark 镜像（包含 Java 11）
- 方案 2：不使用 StreamPark，直接使用 Flink CLI 提交作业
- 方案 3：使用 StreamPark 的 Kubernetes 模式（可以自定义 Flink 镜像）

### Q3: 为什么不能挂载主机的 Java？

**A**:
- 主机是 Windows，JDK 是 Windows 版本
- 容器是 Linux，需要 Linux 版本的 JDK
- 二进制文件格式不兼容（.exe vs ELF，.dll vs .so）

### Q4: Java 8 是否过时？

**A**:
- Java 8 仍然是大数据生态系统的主流版本
- Flink、Kafka、Hadoop 等都完全支持 Java 8
- 许多生产环境仍在使用 Java 8
- Oracle 提供长期支持（LTS）到 2030 年

## 相关配置

### pom.xml 完整配置

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 
         http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.crypto.dw</groupId>
    <artifactId>realtime-crypto-datawarehouse</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
        <!-- 使用 Java 8，兼容 StreamPark 容器 -->
        <maven.compiler.source>8</maven.compiler.source>
        <maven.compiler.target>8</maven.compiler.target>
        <!-- 使用稳定的 Flink 1.17 版本 -->
        <flink.version>1.17.2</flink.version>
    </properties>
</project>
```

### docker-compose-streampark.yml 配置

```yaml
version: '3.8'

services:
  streampark:
    image: apache/streampark:v2.1.5
    container_name: streampark
    environment:
      - TZ=Asia/Shanghai
      - DATASOURCE_DIALECT=h2
      # 字符编码配置 - 支持中文文件名
      - LANG=C.UTF-8
      - LC_ALL=C.UTF-8
      - JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - ./streampark-data:/streampark/data
      - E:\DataFiles\flink-1.17.0:/opt/flink:ro
      - ~/.m2/repository:/root/.m2/repository
```

## 最佳实践

### 1. Java 版本选择

**开发环境**：
- 使用与生产环境相同的 Java 版本
- 避免版本不一致导致的问题

**生产环境**：
- 优先选择 LTS 版本（Java 8, 11, 17, 21）
- 考虑生态系统兼容性（Flink、Kafka 等）
- Java 8 是大数据领域最稳定的选择

### 2. 容器化最佳实践

**镜像选择**：
- 使用官方镜像（如 apache/streampark）
- 避免过度定制，保持简单

**Java 配置**：
- 使用容器内置的 Java
- 避免挂载主机 Java（平台兼容性问题）

### 3. 项目配置

**pom.xml**：
- 明确指定 Java 版本
- 使用 properties 统一管理版本号
- 添加注释说明版本选择原因

## 总结

通过将项目 Java 版本从 11 降级到 8，成功解决了 StreamPark 编译失败的问题。

**关键点**：
- StreamPark 容器内置 Java 8
- 项目原本要求 Java 11
- 降级到 Java 8 后可以正常编译

**技术要点**：
- Java 8 完全支持 Flink 1.17
- 项目代码没有使用 Java 11+ 特性
- 容器化环境需要考虑 Java 版本兼容性

**最佳实践**：
- 使用容器内置的 Java
- 避免跨平台挂载 JDK
- 选择生态系统广泛支持的 Java 版本

## 相关文档

- [202603211815-StreamPark项目克隆-解决中文文件名编码错误.md](./202603211815-StreamPark项目克隆-解决中文文件名编码错误.md)
- [202603211800-StreamPark连接Flink-网络配置和集群添加指南.md](./202603211800-StreamPark连接Flink-网络配置和集群添加指南.md)
- [202603211652-StreamPark部署-Docker方式部署Flink作业管理平台.md](./202603211652-StreamPark部署-Docker方式部署Flink作业管理平台.md)

## 时间记录

- 创建时间：2026-03-21 18:25
- 时区：中国（东八区）
- 问题发现：2026-03-21 18:17
- 问题解决：2026-03-21 18:24
- 解决用时：约 7 分钟
