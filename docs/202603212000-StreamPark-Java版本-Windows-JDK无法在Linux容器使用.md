# StreamPark Java 版本 - Windows JDK 无法在 Linux 容器使用

## 更新时间

2026-03-21 20:00 (东八区)

## 问题描述

尝试将宿主机的 Java 11 挂载到 StreamPark 容器时，容器启动失败并报错：

```
The JAVA_HOME environment variable is not defined correctly
JAVA_HOME=/opt/java11
This environment variable is needed to run this program
NB: JAVA_HOME should point to a JDK not a JRE
```

同时还有基本命令找不到的错误：
```
/streampark/bin/streampark.sh: line 103: uname: command not found
/streampark/bin/streampark.sh: line 126: dirname: command not found
Cannot find //bin/setclasspath.sh
```

## 根本原因

### 问题 1: Windows JDK 无法在 Linux 容器中运行 ⭐⭐⭐

**关键发现**:
- 宿主机的 Java 11 是 Windows 版本 (`E:\DataFiles\Java\jdk-11`)
- StreamPark 容器是 Linux 系统
- Windows 版本的 JDK 包含 `.dll` 文件，无法在 Linux 中运行

**验证**:
```bash
$ ls -la /e/DataFiles/Java/jdk-11/bin/ | head -20
-rwxr-xr-x 1 ralph 197609   14216 Jan 17  2024 api-ms-win-core-console-l1-1-0.dll*
-rwxr-xr-x 1 ralph 197609   13704 Jan 17  2024 api-ms-win-core-datetime-l1-1-0.dll*
# ... 更多 .dll 文件
```

**结论**: 
- ❌ Windows JDK 包含 Windows 特定的 DLL 文件
- ❌ Linux 容器无法加载和运行这些 DLL
- ❌ 即使挂载成功，Java 也无法启动

### 问题 2: PATH 环境变量配置错误

**错误配置**:
```yaml
environment:
  - PATH=/opt/java11/bin:$PATH
```

**问题**:
- Docker Compose 中的 `$PATH` 不会展开为容器的原始 PATH
- 导致系统命令路径丢失
- `uname`, `dirname`, `tty` 等基本命令找不到

**正确配置**:
```yaml
environment:
  - PATH=/opt/java11/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
```

## 解决方案

### 方案 1: 使用容器内置的 Java 8（当前方案）✅

**优点**:
- 无需额外配置
- 稳定可靠
- StreamPark 官方支持

**缺点**:
- 只能编译 Java 8 项目
- 无法使用 Java 11+ 特性

**配置**:
```yaml
services:
  streampark:
    image: apache/streampark:v2.1.5
    # 不配置 JAVA_HOME 和 PATH
    # 使用容器内置的 Java 8
```

**项目配置**:
```xml
<properties>
    <!-- 使用 Java 8 -->
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
</properties>
```

### 方案 2: 下载 Linux 版本的 JDK 11（推荐）⭐⭐⭐

**步骤**:

1. **下载 Linux 版本的 OpenJDK 11**:
   ```bash
   # 下载 Linux x64 版本
   curl -L -o /tmp/openjdk11-linux.tar.gz \
     'https://github.com/adoptium/temurin11-binaries/releases/download/jdk-11.0.22%2B7/OpenJDK11U-jdk_x64_linux_hotspot_11.0.22_7.tar.gz'
   
   # 解压到项目目录
   mkdir -p ./jdk-11-linux
   tar -xzf /tmp/openjdk11-linux.tar.gz -C ./jdk-11-linux --strip-components=1
   ```

2. **更新 docker-compose-streampark.yml**:
   ```yaml
   services:
     streampark:
       environment:
         - JAVA_HOME=/opt/java11
         - PATH=/opt/java11/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
       volumes:
         # 挂载 Linux 版本的 JDK 11
         - ./jdk-11-linux:/opt/java11:ro
   ```

3. **重启容器**:
   ```bash
   docker-compose -f docker-compose-streampark.yml down
   docker-compose -f docker-compose-streampark.yml up -d
   ```

4. **验证**:
   ```bash
   docker exec streampark java -version
   # 应该显示: openjdk version "11.0.22"
   ```

### 方案 3: 使用多阶段构建自定义镜像

**创建 Dockerfile**:
```dockerfile
FROM apache/streampark:v2.1.5

# 安装 OpenJDK 11
RUN apt-get update && \
    apt-get install -y openjdk-11-jdk && \
    apt-get clean && \
    rm -rf /var/lib/apt/lists/*

# 设置 Java 11 为默认
ENV JAVA_HOME=/usr/lib/jvm/java-11-openjdk-amd64
ENV PATH=$JAVA_HOME/bin:$PATH
```

**构建镜像**:
```bash
docker build -t streampark-java11:v2.1.5 .
```

**使用自定义镜像**:
```yaml
services:
  streampark:
    image: streampark-java11:v2.1.5
    # 其他配置保持不变
```

## 技术要点

### 1. JDK 平台兼容性

| 平台 | 文件格式 | 可执行文件 | 库文件 | Linux 容器 |
|-----|---------|-----------|--------|-----------|
| **Windows** | `.exe`, `.dll` | `java.exe` | `.dll` | ❌ 不兼容 |
| **Linux** | ELF | `java` | `.so` | ✅ 兼容 |
| **macOS** | Mach-O | `java` | `.dylib` | ❌ 不兼容 |

**关键点**:
- Windows JDK 使用 PE (Portable Executable) 格式
- Linux JDK 使用 ELF (Executable and Linkable Format) 格式
- 两者完全不兼容，无法跨平台使用

### 2. Docker 卷挂载的限制

**可以挂载**:
- ✅ 配置文件 (文本文件)
- ✅ 数据文件
- ✅ JAR 文件 (Java 字节码，平台无关)
- ✅ 脚本文件 (如果解释器兼容)

**不能挂载**:
- ❌ 平台特定的可执行文件
- ❌ 平台特定的库文件
- ❌ 包含平台特定代码的程序

### 3. PATH 环境变量配置

**Docker Compose 中的环境变量展开**:

```yaml
# ❌ 错误：$PATH 不会展开
environment:
  - PATH=/opt/java11/bin:$PATH

# ✅ 正确：明确指定完整路径
environment:
  - PATH=/opt/java11/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin
```

**Linux 标准 PATH**:
```
/usr/local/sbin  # 本地系统管理员命令
/usr/local/bin   # 本地用户命令
/usr/sbin        # 系统管理员命令
/usr/bin         # 用户命令
/sbin            # 系统命令
/bin             # 基本命令
```

### 4. Java 版本选择

**Flink 1.17.2 支持的 Java 版本**:
- Java 8 ✅ (最广泛支持)
- Java 11 ✅ (LTS，推荐)
- Java 17 ✅ (最新 LTS)

**StreamPark 2.1.5 内置**:
- Java 8 ✅

**推荐**:
- 开发环境：Java 8 (简单，兼容性好)
- 生产环境：Java 11 (性能更好，长期支持)

## 最佳实践

### 1. 开发环境简化

**推荐配置**:
- 项目使用 Java 8
- StreamPark 使用内置 Java 8
- 无需额外配置

**优点**:
- 配置简单
- 稳定可靠
- 避免平台兼容性问题

### 2. 如果必须使用 Java 11

**方案 A: 下载 Linux 版本的 JDK**
- 下载 Linux x64 版本的 OpenJDK 11
- 解压到项目目录
- 挂载到容器

**方案 B: 使用自定义镜像**
- 基于 StreamPark 镜像
- 安装 OpenJDK 11
- 构建自定义镜像

### 3. 避免跨平台挂载

**不要做**:
- ❌ 挂载 Windows JDK 到 Linux 容器
- ❌ 挂载 macOS JDK 到 Linux 容器
- ❌ 挂载平台特定的可执行文件

**可以做**:
- ✅ 挂载 JAR 文件
- ✅ 挂载配置文件
- ✅ 挂载数据文件

## 当前配置

**docker-compose-streampark.yml**:
```yaml
services:
  streampark:
    image: apache/streampark:v2.1.5
    # 使用容器内置的 Java 8
    # 不配置 JAVA_HOME 和 PATH
    volumes:
      # 不挂载 Windows 版本的 JDK
      # 注释掉 Java 11 挂载
      # - E:\DataFiles\Java\jdk-11:/opt/java11:ro
```

**项目 pom.xml**:
```xml
<properties>
    <!-- 使用 Java 8，兼容 StreamPark 容器 -->
    <maven.compiler.source>8</maven.compiler.source>
    <maven.compiler.target>8</maven.compiler.target>
</properties>
```

## 相关文档

- [OpenJDK 11 下载 (Linux)](https://adoptium.net/temurin/releases/?os=linux&arch=x64&version=11)
- [Docker 卷挂载文档](https://docs.docker.com/storage/volumes/)
- [Flink Java 版本兼容性](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/java_compatibility/)

## 总结

**关键教训**:
1. ❌ Windows JDK 无法在 Linux 容器中使用
2. ❌ 不能跨平台挂载可执行文件
3. ✅ 使用容器内置的 Java 8 是最简单的方案
4. ✅ 如果需要 Java 11，必须使用 Linux 版本

**当前方案**:
- 使用 StreamPark 内置的 Java 8
- 项目降级到 Java 8
- 稳定可靠，无需额外配置

**未来优化**:
- 如果需要 Java 11，下载 Linux 版本的 OpenJDK 11
- 或者构建包含 Java 11 的自定义 StreamPark 镜像
