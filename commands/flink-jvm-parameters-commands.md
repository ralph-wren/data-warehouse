# Flink JVM 参数配置命令

## 问题描述

在 Java 9+ 环境中运行 Flink 作业时，可能会遇到反射访问限制错误：

```
java.lang.reflect.InaccessibleObjectException: Unable to make field private static final long java.util.concurrent.atomic.AtomicBoolean.serialVersionUID accessible: module java.base does not "opens java.util.concurrent.atomic" to unnamed module
```

这是因为 Java 9 引入的模块系统限制了对某些内部类的反射访问，而 Flink 的 `ClosureCleaner` 需要访问这些类。

## 解决方案

### 1. 使用 Maven Exec 插件运行时

通过 `MAVEN_OPTS` 环境变量设置 JVM 参数：

```bash
# 设置 JVM 参数解决 Java 9+ 反射访问限制
# -Dlog4j2.disable.jmx=true 禁用 Log4j2 JMX 功能,避免关闭时的 NoClassDefFoundError
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
-Dorg.apache.flink.shaded.netty4.io.netty.tryReflectionSetAccessible=true \
-Dlog4j2.disable.jmx=true"

# 运行 Flink 作业
mvn exec:java -Dexec.mainClass="com.crypto.dw.jobs.FlinkTickerCollectorJob"
```

### 2. 使用 java 命令直接运行时

```bash
java --add-opens=java.base/java.util=ALL-UNNAMED \
     --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
     --add-opens=java.base/java.lang=ALL-UNNAMED \
     -Dorg.apache.flink.shaded.netty4.io.netty.tryReflectionSetAccessible=true \
     -Dlog4j2.disable.jmx=true \
     -cp target/classes:... \
     com.crypto.dw.jobs.FlinkTickerCollectorJob
```

### 3. 使用 Flink Run 命令提交到集群时

在 `flink-conf.yaml` 中配置：

```yaml
env.java.opts: >
  --add-opens=java.base/java.util=ALL-UNNAMED
  --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED
  --add-opens=java.base/java.lang=ALL-UNNAMED
  -Dorg.apache.flink.shaded.netty4.io.netty.tryReflectionSetAccessible=true
```

或者在提交作业时通过命令行参数指定：

```bash
flink run \
    -Denv.java.opts="--add-opens=java.base/java.util=ALL-UNNAMED --add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED --add-opens=java.base/java.lang=ALL-UNNAMED" \
    -c com.crypto.dw.jobs.FlinkTickerCollectorJob \
    target/realtime-crypto-datawarehouse-1.0.0.jar
```

## JVM 参数说明

| 参数 | 说明 |
|------|------|
| `--add-opens=java.base/java.util=ALL-UNNAMED` | 允许未命名模块访问 java.util 包的内部类 |
| `--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED` | 允许访问 java.util.concurrent.atomic 包（解决 AtomicBoolean 访问问题） |
| `--add-opens=java.base/java.lang=ALL-UNNAMED` | 允许访问 java.lang 包的内部类 |
| `-Dorg.apache.flink.shaded.netty4.io.netty.tryReflectionSetAccessible=true` | 允许 Netty 尝试反射访问 |
| `-Dlog4j2.disable.jmx=true` | 禁用 Log4j2 JMX 功能,避免关闭时的 NoClassDefFoundError |

## 已更新的脚本

以下脚本已添加 JVM 参数配置：

1. `run-flink-collector.sh` - 数据采集作业
2. `run-flink-ods-datastream.sh` - ODS DataStream 作业
3. `run-flink-ods-sql.sh` - ODS SQL 作业
4. `run-flink-dwd-sql.sh` - DWD SQL 作业
5. `run-flink-dws-1min-sql.sh` - DWS 1分钟聚合作业
6. `run-flink-ads-realtime-metrics.sh` - ADS 实时指标作业
7. `run-flink-ads-market-monitor.sh` - ADS 市场监控作业
8. `run-flink-ads-arbitrage.sh` - ADS 套利机会作业

## 验证方法

运行任意 Flink 作业，检查是否还有反射访问错误：

```bash
# 运行数据采集作业
bash run-flink-collector.sh

# 查看日志，确认没有 InaccessibleObjectException 错误
tail -f logs/app/flink-app.log
```

## 注意事项

1. 这些参数仅在 Java 9+ 环境中需要，Java 8 不需要
2. `--add-opens` 参数会降低模块系统的安全性，仅在必要时使用
3. 如果使用 Docker 容器运行，确保容器内的 Java 版本与配置匹配
4. 在生产环境中，建议在 Flink 集群配置文件中统一设置这些参数

## 相关资源

- [JEP 261: Module System](https://openjdk.org/jeps/261)
- [Flink Configuration](https://nightlies.apache.org/flink/flink-docs-stable/docs/deployment/config/)
- [Java Platform Module System](https://www.oracle.com/corporate/features/understanding-java-9-modules.html)
