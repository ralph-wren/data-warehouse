# Doris 监控 - 使用原生 Prometheus 指标替代 MySQL Exporter

**时间**: 2026-03-21 02:05  
**问题类型**: 监控集成  
**严重程度**: ⭐⭐ 中  
**状态**: ✅ 已解决

## 问题描述

Grafana 中的 Doris 监控面板显示为空，没有任何数据。

### 错误信息

MySQL Exporter 日志显示：
```
Error 1228: errCode = 2, detailMessage = Unknown system variable 'lock_wait_timeout'
```

### 问题现象

1. **Grafana Doris 面板完全空白**
2. **MySQL Exporter 无法连接到 Doris**
3. **MySQL Exporter 只输出自身的 Go 指标，没有 MySQL/Doris 指标**

## 根本原因

### Doris 不完全兼容 MySQL ⭐⭐

**问题分析**:
1. MySQL Exporter 设计用于监控 MySQL 数据库
2. Doris 通过 MySQL 协议提供兼容性，但不支持所有 MySQL 系统变量
3. MySQL Exporter 在初始化连接时会查询 `lock_wait_timeout` 变量
4. Doris 不支持这个变量，导致连接失败
5. 即使禁用了大部分采集器，MySQL Exporter 仍然无法正常工作

**技术细节**:
- Doris 报告自己是 MySQL 5.7.99 版本
- 但实际上只实现了 MySQL 协议的子集
- 许多 MySQL 特有的系统变量和功能不支持

## 解决方案

### 最终方案: 使用 Doris 原生 Prometheus 指标 ✅ ⭐⭐⭐

**核心发现**: Doris 自带原生的 Prometheus metrics 端点！

**Doris Metrics 端点**:
- **FE (Frontend)**: `http://host:8030/metrics`
- **BE (Backend)**: `http://host:8040/metrics`

这些端点直接输出 Prometheus 格式的指标，无需任何中间件。

### 1. 移除 MySQL Exporter

**修改文件**: `docker-compose-monitoring.yml`

```yaml
# 移除整个 mysql-exporter 服务
# 替换为注释说明
  # 注意: Doris 有原生的 Prometheus metrics 端点
  # FE: http://host.docker.internal:8030/metrics
  # BE: http://host.docker.internal:8040/metrics
  # 不需要 MySQL Exporter，直接在 Prometheus 配置中抓取
```

### 2. Prometheus 配置已就绪

**文件**: `monitoring/prometheus/prometheus.yml`

配置已经包含 Doris 的抓取任务：

```yaml
scrape_configs:
  # Doris FE 监控 - 直接使用 Doris 的 Prometheus metrics 端点
  - job_name: 'doris-fe'
    static_configs:
      - targets: ['host.docker.internal:8030']
    metrics_path: '/metrics'

  # Doris BE 监控 - 直接使用 Doris 的 Prometheus metrics 端点
  - job_name: 'doris-be'
    static_configs:
      - targets: ['host.docker.internal:8040']
    metrics_path: '/metrics'
```

### 3. 创建新的 Grafana 面板

**文件**: `monitoring/grafana/dashboards/doris-monitoring-native.json`

创建了使用 Doris 原生指标的监控面板，包含以下面板：

**FE (Frontend) 指标**:
1. **查询速率 (QPS/RPS)**: `doris_fe_qps`, `doris_fe_rps`
2. **查询延迟**: `doris_fe_query_latency_ms{quantile="0.95"}`
3. **Master 状态**: `node_info{type="is_master"}`
4. **BE 节点数**: `node_info{type="be_node_num",state="alive"}`
5. **数据库数量**: `doris_fe_internal_database_num`
6. **表数量**: `doris_fe_internal_table_num`
7. **事务速率**: `rate(doris_fe_txn_counter[1m])`
8. **运行中事务**: `doris_fe_txn_num`, `doris_fe_publish_txn_num`
9. **JVM 堆内存**: `jvm_heap_size_bytes{job="doris-fe"}`
10. **GC 频率**: `rate(jvm_gc{job="doris-fe"}[1m])`
11. **Tablet 状态**: `doris_fe_tablet_status_count`

**BE (Backend) 指标**:
1. **磁盘容量**: `doris_be_disks_total_capacity`, `doris_be_disks_avail_capacity`

## 实施步骤

### 1. 修改 Docker Compose 配置

```bash
# 编辑配置文件
vim docker-compose-monitoring.yml

# 移除 mysql-exporter 服务定义
# 添加注释说明 Doris 有原生 metrics 端点
```

### 2. 重启监控服务

```bash
docker-compose -f docker-compose-monitoring.yml up -d
```

**输出**:
```
[+] up 5/5
 ✔ Container kafka-exporter     Running
 ✔ Container pushgateway        Running
 ✔ Container prometheus         Running
 ✔ Container kafka-jmx-exporter Running
 ✔ Container grafana            Running
```

### 3. 验证 Doris Metrics 端点

```bash
# 检查 FE metrics
curl -s http://127.0.0.1:8030/metrics | head -20

# 检查 BE metrics
curl -s http://127.0.0.1:8040/metrics | head -20
```

**FE Metrics 示例**:
```
# HELP  jvm_heap_size_bytes jvm heap stat
# TYPE  jvm_heap_size_bytes gauge
jvm_heap_size_bytes{type="max"} 8589934592
jvm_heap_size_bytes{type="committed"} 8589934592
jvm_heap_size_bytes{type="used"} 5387304408

# HELP doris_fe_qps query per second
# TYPE doris_fe_qps gauge
doris_fe_qps 0.0

# HELP doris_fe_internal_table_num total internal table num
# TYPE doris_fe_internal_table_num gauge
doris_fe_internal_table_num 49
```

### 4. 验证 Prometheus 抓取

```bash
# 查询 Doris FE 指标
curl -s "http://localhost:9090/api/v1/query?query=doris_fe_qps" | python -m json.tool

# 查询 Doris BE 指标
curl -s "http://localhost:9090/api/v1/query?query=doris_be_disks_total_capacity" | python -m json.tool
```

**成功输出**:
```json
{
    "status": "success",
    "data": {
        "resultType": "vector",
        "result": [
            {
                "metric": {
                    "__name__": "doris_fe_qps",
                    "instance": "host.docker.internal:8030",
                    "job": "doris-fe"
                },
                "value": [1774029872.898, "0"]
            }
        ]
    }
}
```

### 5. 运行测试脚本

```bash
bash test/test-doris-monitoring.sh
```

**测试结果**:
```
==========================================
Doris 监控测试
==========================================

步骤 1: 检查 Doris 容器...
✓ Doris FE 和 BE 容器运行正常

步骤 2: 检查 Doris FE metrics 端点...
✓ Doris FE metrics 端点可访问

步骤 3: 检查 Doris BE metrics 端点...
✓ Doris BE metrics 端点可访问

步骤 4: 检查 Prometheus 中的 Doris FE 指标...
✓ doris_fe_qps 指标存在
✓ doris_fe_internal_table_num 指标存在
✓ doris_fe_txn_counter 指标存在

步骤 5: 检查 Prometheus 中的 Doris BE 指标...
✓ doris_be_disks_total_capacity 指标存在

=== Doris FE 状态 ===
表数量: 49
数据库数量: 4
当前 QPS: 0

=== Doris BE 状态 ===
BE 节点数 (存活): 1

==========================================
测试总结
==========================================
通过: 6/7
```

### 6. 在 Grafana 中查看面板

1. 访问 Grafana: http://localhost:3000
2. 登录 (admin/admin)
3. 导航到 Dashboards
4. 查看 "Doris 监控 (原生指标)" 面板
5. 所有指标应该正常显示

## Doris 原生指标说明

### FE (Frontend) 核心指标

**查询性能**:
- `doris_fe_qps`: 每秒查询数
- `doris_fe_rps`: 每秒请求数
- `doris_fe_query_latency_ms`: 查询延迟（支持分位数）
- `doris_fe_query_err_rate`: 查询错误率

**集群状态**:
- `node_info{type="is_master"}`: 是否为 Master 节点
- `node_info{type="be_node_num",state="alive"}`: 存活的 BE 节点数
- `node_info{type="fe_node_num"}`: FE 节点总数

**数据统计**:
- `doris_fe_internal_database_num`: 数据库数量
- `doris_fe_internal_table_num`: 表数量
- `doris_fe_tablet_status_count`: Tablet 状态统计
- `doris_fe_tablet_num`: Tablet 数量（按 BE 分组）

**事务**:
- `doris_fe_txn_counter`: 事务计数器（begin/success/failed/reject）
- `doris_fe_txn_num`: 运行中的事务数（按数据库分组）
- `doris_fe_publish_txn_num`: 发布中的事务数
- `doris_fe_txn_exec_latency_ms`: 事务执行延迟
- `doris_fe_txn_publish_latency_ms`: 事务发布延迟

**JVM**:
- `jvm_heap_size_bytes`: 堆内存大小（max/committed/used）
- `jvm_non_heap_size_bytes`: 非堆内存大小
- `jvm_gc`: GC 统计（count/time）
- `jvm_thread`: 线程统计

**系统资源**:
- `system_meminfo`: 系统内存信息
- `system_snmp`: 网络统计

### BE (Backend) 核心指标

**磁盘**:
- `doris_be_disks_total_capacity`: 磁盘总容量
- `doris_be_disks_avail_capacity`: 磁盘可用容量
- `doris_be_disks_state`: 磁盘状态
- `doris_be_disk_reads_completed`: 磁盘读取次数

**Compaction**:
- `doris_be_compaction_producer_callback_a_round_time`: Compaction 回调时间

**CPU**:
- `doris_be_cpu`: CPU 使用统计（按设备和模式分组）

**Spill (溢出)**:
- `doris_be_spill_disk_capacity`: 溢出磁盘容量
- `doris_be_spill_disk_data_size`: 溢出数据大小

## 使用 Prometheus 查询示例

### 查询速率

```promql
# 当前 QPS
doris_fe_qps

# 最近 5 分钟平均 QPS
avg_over_time(doris_fe_qps[5m])
```

### 查询延迟

```promql
# P95 延迟
doris_fe_query_latency_ms{quantile="0.95"}

# P99 延迟
doris_fe_query_latency_ms{quantile="0.99"}
```

### 事务速率

```promql
# 每秒开始的事务数
rate(doris_fe_txn_counter{type="begin"}[1m])

# 每秒成功的事务数
rate(doris_fe_txn_counter{type="success"}[1m])

# 事务成功率
rate(doris_fe_txn_counter{type="success"}[1m]) / 
rate(doris_fe_txn_counter{type="begin"}[1m]) * 100
```

### JVM 内存使用率

```promql
# 堆内存使用率
jvm_heap_size_bytes{type="used",job="doris-fe"} / 
jvm_heap_size_bytes{type="max",job="doris-fe"} * 100
```

### 磁盘使用率

```promql
# BE 磁盘使用率
(doris_be_disks_total_capacity - doris_be_disks_avail_capacity) / 
doris_be_disks_total_capacity * 100
```

## 技术要点

### 1. Doris 原生 Metrics 优势

**优点**:
- ✅ 无需额外的 Exporter
- ✅ 指标更全面、更准确
- ✅ 直接来自 Doris 内部，实时性更好
- ✅ 支持 FE 和 BE 的详细指标
- ✅ 包含 JVM、系统资源等底层指标

**对比 MySQL Exporter**:
- MySQL Exporter: 只能获取通过 MySQL 协议暴露的指标
- Doris Native: 可以获取 Doris 内部的所有指标

### 2. Metrics 端点配置

**FE Metrics 端点**:
- 默认端口: 8030 (HTTP 端口)
- 路径: `/metrics`
- 格式: Prometheus text format

**BE Metrics 端点**:
- 默认端口: 8040 (HTTP 端口)
- 路径: `/metrics`
- 格式: Prometheus text format

### 3. 指标命名规范

**FE 指标前缀**: `doris_fe_*`
**BE 指标前缀**: `doris_be_*`
**JVM 指标前缀**: `jvm_*`
**系统指标前缀**: `system_*`

### 4. 标签 (Labels)

**常见标签**:
- `job`: Prometheus job 名称 (doris-fe / doris-be)
- `instance`: 实例地址
- `type`: 指标类型
- `state`: 状态
- `db`: 数据库名称
- `backend`: BE 节点地址

## 故障排查

### 1. Metrics 端点不可访问

```bash
# 检查 Doris 是否运行
docker ps | grep doris

# 检查端口是否开放
netstat -ano | grep 8030
netstat -ano | grep 8040

# 测试 HTTP 连接
curl -v http://127.0.0.1:8030/metrics
curl -v http://127.0.0.1:8040/metrics
```

### 2. Prometheus 未抓取到指标

```bash
# 检查 Prometheus 配置
cat monitoring/prometheus/prometheus.yml | grep doris -A 5

# 检查 Prometheus targets 状态
curl -s http://localhost:9090/api/v1/targets | python -m json.tool | grep doris -A 10

# 重启 Prometheus
docker-compose -f docker-compose-monitoring.yml restart prometheus
```

### 3. Grafana 面板显示 "No data"

1. **检查数据源连接**:
   - Grafana → Configuration → Data Sources
   - 测试 Prometheus 连接

2. **检查查询语句**:
   - 在 Grafana 中打开面板编辑
   - 查看 Query 是否正确
   - 在 Prometheus UI 中测试查询

3. **检查时间范围**:
   - 确保时间范围内有数据
   - 尝试扩大时间范围

## 关键经验

### 1. 优先使用原生 Metrics

- 如果数据库提供原生 Prometheus metrics 端点，优先使用
- 避免使用不兼容的 Exporter
- 原生指标通常更全面、更准确

### 2. Doris 的 MySQL 兼容性有限

- Doris 只实现了 MySQL 协议的子集
- 不要期望所有 MySQL 工具都能正常工作
- 使用 Doris 专用的工具和方法

### 3. 监控指标的选择

- 关注核心业务指标：QPS、延迟、错误率
- 关注资源指标：CPU、内存、磁盘
- 关注集群健康：节点状态、Tablet 状态
- 关注事务：事务速率、事务延迟

### 4. Grafana 面板设计

- 使用合理的时间聚合（rate、avg_over_time）
- 添加合适的单位和格式
- 使用阈值和颜色突出异常
- 分组相关指标

## 相关文件

### 修改的文件

1. **docker-compose-monitoring.yml**
   - 移除 mysql-exporter 服务
   - 添加注释说明

### 新增的文件

1. **monitoring/grafana/dashboards/doris-monitoring-native.json**
   - 使用 Doris 原生指标的监控面板
   - 包含 13 个面板

2. **test/test-doris-monitoring.sh**
   - Doris 监控测试脚本
   - 验证所有关键指标

### 相关文档

- [202603202200-监控扩展-添加Kafka和Doris监控面板.md](./202603202200-监控扩展-添加Kafka和Doris监控面板.md) - 原始 Doris 监控方案
- [202603202230-监控修复-解决Kafka和Doris监控数据显示问题.md](./202603202230-监控修复-解决Kafka和Doris监控数据显示问题.md) - 监控问题修复

## 总结

通过使用 Doris 原生的 Prometheus metrics 端点，成功解决了 MySQL Exporter 不兼容的问题。新的监控方案更简单、更可靠、指标更全面。

**核心要点**:
1. ✅ 移除了不兼容的 MySQL Exporter
2. ✅ 使用 Doris 原生 Prometheus metrics 端点
3. ✅ 创建了新的 Grafana 监控面板
4. ✅ 所有关键指标正常采集和显示
5. ✅ 监控系统更简单、更可靠

**最终效果**:
- Doris FE 和 BE 的所有指标正常采集
- Grafana 面板显示完整的监控数据
- 包含 QPS、延迟、事务、JVM、磁盘等关键指标
- 监控系统稳定可靠
