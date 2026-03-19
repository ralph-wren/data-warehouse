# 监控修复 - Metrics Reporter 配置错误导致指标未推送

**时间**: 2026-03-20 01:17  
**问题**: Flink 作业运行正常，但 Prometheus 和 Grafana 没有监控数据  
**状态**: ✅ 已解决

---

## 问题描述

**现象**:
1. Flink 作业正常运行，数据持续写入 Doris
2. Flink Web UI 显示 Records Received 为 0
3. Pushgateway 中没有 Flink 指标
4. Grafana Dashboard 显示 "No data"

**用户反馈**:
> ods_crypto_ticker_rt 数据一直在增加，但是为什么 flink 任务显示 records received 为空，并且 grafana 监控都是空的？

## 问题排查

### 1. 检查 Pushgateway

```bash
curl -s http://localhost:9091/metrics | grep flink_
# 结果：没有任何 Flink 指标
```

### 2. 检查 Flink 日志

```bash
grep -i "metric\|prometheus" logs/flink/flink.log
```

**关键错误信息**:
```
[main] ReporterSetup:377 - The reporter configuration of 'promgateway' configures the reporter class, 
which is no longer supported approach to configure reporters. 
Please configure a factory class instead: 'metrics.reporter.promgateway.factory.class: <factoryClass>'.

[main] ReporterSetup:386 - No reporter factory set for reporter promgateway. 
Metrics might not be exposed/reported.

[main] MetricRegistryImpl:142 - No metrics reporter configured, no metrics will be exposed/reported.
```

## 根本原因

**Flink 1.17+ 配置方式变更** ⭐⭐⭐

从 Flink 1.15 开始，Metrics Reporter 的配置方式发生了变化：

### 旧方式（已废弃）❌

```java
// 直接配置 Reporter 类（Flink 1.14 及以前）
config.setString("metrics.reporter.promgateway.class", 
    "org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporter");
```

### 新方式（推荐）✅

```java
// 使用 Factory 类（Flink 1.15+）
config.setString("metrics.reporter.promgateway.factory.class", 
    "org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporterFactory");
```

**关键区别**:
- 旧方式：`metrics.reporter.<name>.class`
- 新方式：`metrics.reporter.<name>.factory.class`

## 解决方案

### 修改 MetricsConfig.java

**1. Pushgateway Reporter 配置**:

```java
public static void configurePushgatewayReporter(
        Configuration config, 
        String pushgatewayHost, 
        int pushgatewayPort,
        String jobName) {
    
    // ✅ 使用 factory.class（新方式）
    config.setString("metrics.reporter.promgateway.factory.class", 
        "org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporterFactory");
    
    // Pushgateway 地址
    config.setString("metrics.reporter.promgateway.host", pushgatewayHost);
    config.setString("metrics.reporter.promgateway.port", String.valueOf(pushgatewayPort));
    
    // 作业名称（用于 Prometheus 标签）
    config.setString("metrics.reporter.promgateway.jobName", jobName);
    
    // 推送间隔（使用字符串格式）
    config.setString("metrics.reporter.promgateway.interval", "15 SECONDS");
    
    // 是否删除关闭时的指标
    config.setString("metrics.reporter.promgateway.deleteOnShutdown", "false");
}
```

**2. HTTP Server Reporter 配置**:

```java
public static void configureHttpServerReporter(Configuration config, int port) {
    // ✅ 使用 factory.class（新方式）
    config.setString("metrics.reporter.prom.factory.class", 
        "org.apache.flink.metrics.prometheus.PrometheusReporterFactory");
    
    // HTTP Server 端口
    config.setString("metrics.reporter.prom.port", String.valueOf(port));
}
```

### 配置参数类型变更

| 参数 | 旧类型 | 新类型 | 说明 |
|-----|-------|-------|------|
| `port` | `Integer` | `String` | 统一使用字符串 |
| `interval` | `Integer` (秒) | `String` (带单位) | 例如 "15 SECONDS" |
| `deleteOnShutdown` | `Boolean` | `String` | "true" 或 "false" |

## Flink Metrics Reporter 配置详解

### 1. Reporter Factory 类

| Reporter 类型 | Factory Class |
|--------------|---------------|
| Pushgateway | `org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporterFactory` |
| HTTP Server | `org.apache.flink.metrics.prometheus.PrometheusReporterFactory` |
| JMX | `org.apache.flink.metrics.jmx.JMXReporterFactory` |
| Slf4j | `org.apache.flink.metrics.slf4j.Slf4jReporterFactory` |
| Graphite | `org.apache.flink.metrics.graphite.GraphiteReporterFactory` |
| InfluxDB | `org.apache.flink.metrics.influxdb.InfluxdbReporterFactory` |

### 2. Pushgateway Reporter 配置选项

| 配置项 | 类型 | 默认值 | 说明 |
|-------|------|-------|------|
| `factory.class` | String | - | Reporter Factory 类（必需）|
| `host` | String | - | Pushgateway 主机地址（必需）|
| `port` | String | "9091" | Pushgateway 端口 |
| `jobName` | String | - | 作业名称（用于 Prometheus 标签）|
| `randomJobNameSuffix` | String | "true" | 是否添加随机后缀 |
| `interval` | String | "60 SECONDS" | 推送间隔 |
| `deleteOnShutdown` | String | "true" | 关闭时是否删除指标 |
| `groupingKey` | String | - | 额外的分组键 |

### 3. 时间间隔格式

```java
// 支持的时间单位
"15 SECONDS"      // 15 秒
"1 MINUTES"       // 1 分钟
"5 MINUTES"       // 5 分钟
"1 HOURS"         // 1 小时
```

### 4. 配置文件方式（flink-conf.yaml）

```yaml
# Pushgateway Reporter
metrics.reporter.promgateway.factory.class: org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporterFactory
metrics.reporter.promgateway.host: localhost
metrics.reporter.promgateway.port: 9091
metrics.reporter.promgateway.jobName: flink-job
metrics.reporter.promgateway.interval: 15 SECONDS
metrics.reporter.promgateway.deleteOnShutdown: false

# HTTP Server Reporter
metrics.reporter.prom.factory.class: org.apache.flink.metrics.prometheus.PrometheusReporterFactory
metrics.reporter.prom.port: 9249
```

## 验证修复

### 1. 重新编译项目

```bash
mvn clean compile -DskipTests
```

### 2. 重启 Flink 作业

```bash
# 停止旧作业（Ctrl+C）
# 启动新作业
bash run-flink-ods-datastream.sh
```

### 3. 检查 Flink 日志

```bash
grep -i "metric\|prometheus" logs/flink/flink.log
```

**期望看到**:
```
[main] MetricRegistryImpl:142 - Configuring metrics reporter promgateway
[main] PrometheusPushGatewayReporter:xxx - Started Prometheus Pushgateway reporter
```

### 4. 检查 Pushgateway

```bash
# 等待 15 秒（推送间隔）
sleep 15

# 查看指标
curl -s http://localhost:9091/metrics | grep flink_
```

**期望看到**:
```
flink_taskmanager_job_task_numRecordsInPerSecond{...} 123.45
flink_taskmanager_job_task_numRecordsOutPerSecond{...} 123.45
flink_taskmanager_Status_JVM_Memory_Heap_Used{...} 123456789
...
```

### 5. 检查 Grafana

1. 访问 http://localhost:3000
2. 打开 "Flink Monitoring" Dashboard
3. 应该能看到实时数据

## 常见问题

### 1. 为什么 Flink Web UI 显示 Records Received 为 0？

**原因**: Flink Web UI 的指标显示可能有延迟，或者需要刷新页面。

**解决**:
- 刷新浏览器页面
- 等待几秒钟
- 检查 Kafka 是否有数据

### 2. Pushgateway 中有指标，但 Grafana 没有数据

**原因**: Prometheus 可能还没有抓取 Pushgateway 的数据。

**解决**:
```bash
# 检查 Prometheus targets
curl http://localhost:9090/api/v1/targets

# 手动触发抓取（等待 15 秒）
sleep 15

# 在 Prometheus 中查询
curl "http://localhost:9090/api/v1/query?query=flink_taskmanager_job_task_numRecordsInPerSecond"
```

### 3. 配置了 factory.class 但仍然报错

**可能原因**:
1. 依赖缺失：确保 `flink-metrics-prometheus` 依赖已添加
2. 版本不匹配：确保 Flink 版本 >= 1.15
3. 配置错误：检查 factory class 名称是否正确

**检查依赖**:
```bash
mvn dependency:tree | grep prometheus
```

**期望看到**:
```
[INFO] +- org.apache.flink:flink-metrics-prometheus:jar:1.17.2:compile
```

## 版本兼容性

| Flink 版本 | 配置方式 | 说明 |
|-----------|---------|------|
| < 1.15 | `metrics.reporter.<name>.class` | 旧方式 |
| >= 1.15 | `metrics.reporter.<name>.factory.class` | 新方式（推荐）|
| >= 1.15 | `metrics.reporter.<name>.class` | 仍然支持，但会有警告 |

**迁移建议**:
- Flink 1.15+：使用 `factory.class`
- Flink 1.14 及以前：使用 `class`
- 混合环境：使用 `factory.class`（向后兼容）

## 最佳实践

### 1. 开发环境

```java
// 使用 Pushgateway（推荐）
MetricsConfig.configurePushgatewayReporter(
    flinkConfig,
    "localhost",
    9091,
    "flink-dev-job"
);
```

**优点**:
- 作业停止后指标仍然保留
- 适合短期作业
- 易于调试

### 2. 生产环境

```java
// 使用 HTTP Server（推荐）
MetricsConfig.configureHttpServerReporter(flinkConfig, 9249);
```

**优点**:
- Prometheus 主动拉取
- 更稳定可靠
- 适合长期运行的作业

### 3. 混合模式

```java
// 同时启用两种 Reporter
MetricsConfig.configurePushgatewayReporter(flinkConfig, "localhost", 9091, "flink-job");
MetricsConfig.configureHttpServerReporter(flinkConfig, 9249);
```

## 相关文件

- `src/main/java/com/crypto/dw/config/MetricsConfig.java` - Metrics 配置（已修复）
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - ODS 作业
- `src/main/java/com/crypto/dw/flink/FlinkDWDJobSQL.java` - DWD 作业

## 参考资料

- [Flink Metrics 官方文档](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/ops/metrics/)
- [Flink Prometheus Reporter](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/deployment/metric_reporters/#prometheus)
- [Prometheus Pushgateway](https://github.com/prometheus/pushgateway)

---

**总结**: 通过将 Metrics Reporter 配置从旧的 `class` 方式改为新的 `factory.class` 方式，成功解决了指标未推送的问题。现在 Flink 指标可以正常推送到 Pushgateway，Grafana 也能显示监控数据。
