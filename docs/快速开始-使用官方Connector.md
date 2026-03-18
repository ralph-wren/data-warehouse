# 快速开始 - 使用官方 Doris Connector

## 概述

本文档提供使用官方 Doris Flink Connector 的快速开始指南。

## 为什么使用官方 Connector?

经过测试发现,Doris 4.0.1-rc02 版本的 Stream Load API 存在 bug,自定义 HTTP 实现无法稳定工作。官方 Connector 内部处理了这些兼容性问题,是更可靠的选择。

## 快速开始

### 1. 确保环境正常

```bash
# 检查 Kafka
kafka-topics.sh --bootstrap-server localhost:9092 --list

# 检查 Doris
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW DATABASES;"
```

### 2. 编译项目

```bash
mvn clean package -DskipTests
```

### 3. 运行 Flink 作业

```bash
# 使用官方 Connector 版本 (推荐)
./run-flink-ods-datastream2.sh
```

### 4. 查看日志

```bash
# 实时查看日志
tail -f logs/flink-app.log

# 查看最近的错误
tail -100 logs/flink-app.log | grep ERROR
```

### 5. 验证数据

```bash
# 查询 Doris 中的数据
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT COUNT(*) as total_records 
FROM crypto_dw.ods_crypto_ticker_rt;
"

# 查看最新数据
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT * 
FROM crypto_dw.ods_crypto_ticker_rt 
ORDER BY ingest_time DESC 
LIMIT 10;
"
```

## 两个版本对比

### FlinkODSJobDataStream (自定义实现)

```bash
./run-flink-ods-datastream.sh
```

**特点**:
- ❌ 在 Doris 4.0.1-rc02 上不稳定
- ❌ 需要手动处理 HTTP 协议问题
- ❌ 维护成本高
- ✅ 代码简单,易于理解

**适用场景**: 学习和参考

### FlinkODSJobDataStream2 (官方 Connector) ⭐ 推荐

```bash
./run-flink-ods-datastream2.sh
```

**特点**:
- ✅ 稳定可靠
- ✅ 自动处理版本兼容性
- ✅ 内置重试机制
- ✅ 官方维护和支持

**适用场景**: 生产环境

## 配置说明

### application.yml

```yaml
# Kafka 配置
kafka:
  bootstrap-servers: localhost:9092
  topic:
    crypto-ticker: crypto_ticker
  consumer:
    group-id: flink-ods-consumer

# Doris 配置
doris:
  fe:
    http-url: http://127.0.0.1:8030
    username: root
    password: ""
  database: crypto_dw
  tables:
    ods: ods_crypto_ticker_rt
  stream-load:
    batch-size: 1000
    batch-interval-ms: 5000
    max-retries: 3

# Flink 配置
flink:
  execution:
    parallelism: 4
  checkpoint:
    interval: 60000
```

## 常见问题

### Q1: 编译失败

```bash
# 清理并重新编译
mvn clean compile -DskipTests
```

### Q2: 连接 Kafka 失败

```bash
# 检查 Kafka 是否运行
ps aux | grep kafka

# 检查端口
netstat -an | grep 9092
```

### Q3: 连接 Doris 失败

```bash
# 检查 Doris FE
curl http://127.0.0.1:8030/api/bootstrap

# 检查 Doris BE
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G"
```

### Q4: 数据没有写入

```bash
# 查看 Flink 日志
tail -f logs/flink-app.log | grep -i "error\|exception"

# 查看 Doris Stream Load 历史
mysql -h 127.0.0.1 -P 9030 -u root -e "
SHOW STREAM LOAD FROM crypto_dw 
ORDER BY CreateTime DESC 
LIMIT 10\G
"
```

## 性能调优

### 1. 增加并行度

```yaml
flink:
  execution:
    parallelism: 8  # 根据 CPU 核心数调整
```

### 2. 调整批量大小

```yaml
doris:
  stream-load:
    batch-size: 2000  # 增加批量大小
    batch-interval-ms: 3000  # 减少批量间隔
```

### 3. 优化 Checkpoint

```yaml
flink:
  checkpoint:
    interval: 30000  # 减少 checkpoint 间隔
```

## 监控指标

### 关键指标

- **吞吐量**: records/second
- **延迟**: end-to-end latency
- **错误率**: failed / total
- **Checkpoint 时间**: checkpoint duration

### 查看指标

```bash
# Flink Web UI
http://localhost:8081

# 查看作业列表
flink list

# 查看作业详情
flink info <job-id>
```

## 下一步

1. 启动数据采集器: `./run-collector.sh`
2. 运行 DWD 层作业: `./run-flink-dwd-sql.sh`
3. 运行 DWS 层作业: `./run-flink-dws-1min-sql.sh`

## 相关文档

- [问题解决汇总](../问题解决汇总.md)
- [使用官方 Connector 解决 Stream Load Bug](./202603182345-Doris写入-使用官方Connector解决Stream-Load-Bug.md)
- [配置说明](./CONFIGURATION.md)
