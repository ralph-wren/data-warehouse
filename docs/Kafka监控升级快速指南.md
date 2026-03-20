# Kafka 监控升级快速指南

## 概述

本指南帮助你快速将 Kafka 监控从简化版升级到完整版，获得详细的监控指标。

## 升级前后对比

### 简化版监控 (当前)
- ✅ Broker 数量
- ✅ Topic 数量和分区数
- ✅ 副本同步状态
- ❌ 消息速率
- ❌ 吞吐量
- ❌ 请求延迟
- ❌ JVM 指标

### 完整版监控 (升级后)
- ✅ 所有简化版指标
- ✅ 消息流入/流出速率 (实时)
- ✅ 字节吞吐量 (流入/流出)
- ✅ 请求延迟 (P50/P95/P99)
- ✅ 副本健康状态
- ✅ JVM 指标 (CPU、内存、GC)
- ✅ 控制器状态

## 一键升级 (推荐)

```bash
# 运行升级脚本
./upgrade-kafka-monitoring.sh
```

脚本会自动完成:
1. ✅ 重启 Kafka 并启用 JMX
2. ✅ 重启监控服务
3. ✅ 验证服务状态
4. ✅ 测试 JMX 指标
5. ✅ 显示访问信息

## 手动升级步骤

如果需要分步执行:

### 步骤 1: 重启 Kafka 并启用 JMX

```bash
./restart-kafka-with-jmx.sh
```

这会:
- 停止现有 Kafka 容器
- 启动新容器并启用 JMX (端口 9999)
- 重新创建 Topic

### 步骤 2: 重启监控服务

```bash
# 停止现有监控服务
docker-compose -f docker-compose-monitoring.yml down

# 启动新的监控服务 (包含 JMX Exporter)
docker-compose -f docker-compose-monitoring.yml up -d
```

### 步骤 3: 等待服务启动

```bash
# 等待 30 秒让服务完全启动
sleep 30
```

### 步骤 4: 测试 JMX 指标

```bash
./test/test-kafka-jmx-monitoring.sh
```

应该看到所有测试通过 ✅

### 步骤 5: 导入 Grafana 面板

1. 访问 Grafana: http://localhost:3000
2. 登录 (admin/admin)
3. 导航到 Dashboards → Import
4. 点击 "Upload JSON file"
5. 选择文件: `monitoring/grafana/dashboards/kafka-monitoring-advanced.json`
6. 选择 Prometheus 数据源
7. 点击 Import

## 验证升级结果

### 1. 检查 JMX Exporter

```bash
# 查看 JMX 指标
curl http://localhost:5556/metrics | grep kafka_server

# 应该看到大量指标输出
```

### 2. 检查 Prometheus

访问 http://localhost:9090 并执行查询:

```promql
# 消息流入速率
rate(kafka_server_brokertopicmetrics_messagesinpersec_total[1m])

# 字节吞吐量
rate(kafka_server_brokertopicmetrics_bytesinpersec_total[1m])

# 请求延迟
kafka_network_requestmetrics_totaltimems{request="Produce",quantile="Mean"}
```

应该看到有数据返回 ✅

### 3. 检查 Grafana 面板

访问 http://localhost:3000 并打开 "Kafka 监控 (完整版)" 面板

应该看到:
- ✅ 所有 20 个面板都有数据
- ✅ 实时更新 (5 秒刷新)
- ✅ 图表显示正常

## 故障排查

### 问题 1: JMX Exporter 无法连接

**检查**:
```bash
# 检查 Kafka JMX 端口
netstat -ano | grep 9999

# 检查 Kafka 环境变量
docker exec kafka env | grep JMX
```

**解决**:
```bash
# 重启 Kafka
./restart-kafka-with-jmx.sh
```

### 问题 2: Prometheus 没有抓取到指标

**检查**:
```bash
# 查看 Prometheus targets
curl http://localhost:9090/api/v1/targets | grep kafka-jmx

# 检查 JMX Exporter 是否可访问
curl http://localhost:5556/metrics
```

**解决**:
```bash
# 重启 Prometheus
docker-compose -f docker-compose-monitoring.yml restart prometheus
```

### 问题 3: Grafana 面板显示 "No data"

**检查步骤**:

1. 在 Prometheus 中验证指标存在
2. 在 Grafana Explore 中测试查询
3. 检查时间范围设置
4. 检查数据源配置

**解决**:
- 确保 Kafka 有流量 (运行数据采集器)
- 等待 1-2 分钟让数据积累
- 刷新 Grafana 页面

## 监控面板说明

### 集群概览 (6 个面板)
- Broker 数量
- 分区总数
- Leader 分区数
- 未充分复制的分区
- 离线分区数
- 活跃控制器

### 消息吞吐量 (4 个面板)
- 消息流入速率 (按 Topic)
- 字节吞吐量 (按 Topic)
- 总消息流入速率
- 总字节吞吐量

### 请求性能 (4 个面板)
- 请求总时间 (Produce/Fetch)
- Produce 请求时间分解
- 请求速率 (按类型)
- 失败请求速率

### 副本和分区 (2 个面板)
- crypto_ticker 同步副本数
- ISR 变化速率

### JVM 和系统资源 (4 个面板)
- CPU 使用率
- JVM 内存使用
- GC 频率
- GC 时间

## 常用查询

### 消息吞吐量

```promql
# 消息流入速率 (每秒)
rate(kafka_server_brokertopicmetrics_messagesinpersec_total[1m])

# 字节流入速率 (每秒)
rate(kafka_server_brokertopicmetrics_bytesinpersec_total[1m])

# 字节流出速率 (每秒)
rate(kafka_server_brokertopicmetrics_bytesoutpersec_total[1m])
```

### 请求性能

```promql
# Produce 请求平均延迟
kafka_network_requestmetrics_totaltimems{request="Produce",quantile="Mean"}

# Produce 请求 P99 延迟
kafka_network_requestmetrics_totaltimems{request="Produce",quantile="99thPercentile"}

# Fetch 请求平均延迟
kafka_network_requestmetrics_totaltimems{request="Fetch",quantile="Mean"}
```

### 集群健康

```promql
# 未充分复制的分区
kafka_server_replicamanager_underreplicatedpartitions

# 离线分区数
kafka_controller_kafkacontroller_offlinepartitionscount

# 活跃控制器数 (应该为 1)
kafka_controller_kafkacontroller_activecontrollercount
```

### JVM 指标

```promql
# 进程 CPU 使用率
jvm_process_cpu_load

# 堆内存使用
jvm_memory_heap_used

# 堆内存使用率
jvm_memory_heap_used / jvm_memory_heap_max

# GC 频率
rate(jvm_gc_collection_count_total[1m])

# GC 时间
rate(jvm_gc_collection_time_ms_total[1m])
```

## 性能影响

### JMX Exporter 资源消耗
- CPU: < 5%
- 内存: ~100MB
- 网络: 可忽略

### Kafka JMX 开销
- CPU: < 2%
- 内存: ~50MB
- 对 Kafka 性能影响: 可忽略

## 后续优化

### 1. 添加告警规则

创建 `monitoring/prometheus/alerts/kafka.yml`:

```yaml
groups:
  - name: kafka
    rules:
      - alert: KafkaUnderReplicatedPartitions
        expr: kafka_server_replicamanager_underreplicatedpartitions > 0
        for: 5m
        labels:
          severity: warning
        annotations:
          summary: "Kafka 有未充分复制的分区"
```

### 2. 添加消费者 LAG 监控

使用 Kafka Lag Exporter 监控消费者延迟

### 3. 集成 Alertmanager

配置告警通知 (邮件、Slack、钉钉等)

### 4. 添加日志监控

集成 Loki + Promtail 收集 Kafka 日志

## 相关文档

- [详细文档](./202603202320-监控升级-Kafka-JMX详细监控实现.md)
- [问题解决汇总](../问题解决汇总.md)
- [监控系统使用指南](./监控系统使用指南.md)

## 总结

通过升级到 JMX Exporter，你现在拥有了完整的 Kafka 监控能力:

- ✅ 实时消息速率和吞吐量
- ✅ 详细的请求性能指标
- ✅ 副本健康状态监控
- ✅ JVM 和系统资源监控
- ✅ 20 个专业监控面板

监控系统现已完整，可以全面监控 Kafka 集群的健康状态和性能表现！
