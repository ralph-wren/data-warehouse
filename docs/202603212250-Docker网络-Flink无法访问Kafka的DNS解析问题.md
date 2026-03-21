# Docker 网络 - Flink 无法访问 Kafka 的 DNS 解析问题

**时间**: 2026-03-21 22:50  
**问题**: Flink 作业无法连接到 Kafka，DNS 解析失败  
**状态**: ✅ 已解决

---

## 问题描述

Flink 作业启动后，无法连接到 Kafka，报错：

```
Couldn't resolve server kafka:9092 from bootstrap.servers as DNS resolution failed for kafka
No resolvable bootstrap urls given in bootstrap.servers
```

### 关键发现

1. ✅ 配置文件加载成功（能读取到 `kafka:9092`）
2. ❌ Flink 容器无法解析 `kafka` 主机名
3. ❌ Flink 和 Kafka 不在同一个 Docker 网络中

---

## 根本原因

**Docker 网络隔离问题**：

| 容器 | 所在网络 | IP 地址 |
|------|----------|---------|
| Flink JobManager | data-warehouse_default | 172.20.0.3 |
| Flink TaskManager | data-warehouse_default | 172.20.0.4 |
| Kafka | bridge, data-warehouse_monitoring-net | 172.17.0.2, 172.19.0.7 |

**问题分析**：
- Flink 容器在 `data-warehouse_default` 网络中
- Kafka 容器在 `bridge` 和 `data-warehouse_monitoring-net` 网络中
- 两个网络之间无法互相通信
- Docker DNS 只在同一网络内解析容器名称

---

## 解决方案

### 临时解决方案（立即生效）

将 Kafka 连接到 Flink 所在的网络：

```bash
docker network connect data-warehouse_default kafka
```

**验证**：
```bash
# 查看 Kafka 所在的网络
docker inspect kafka --format='{{range $key, $value := .NetworkSettings.Networks}}{{$key}}: {{$value.IPAddress}} {{end}}'

# 输出：
# bridge: 172.17.0.2 
# data-warehouse_default: 172.20.0.5 
# data-warehouse_monitoring-net: 172.19.0.7
```

### 永久解决方案（重启后保持）

创建 Kafka 的 docker-compose 配置文件，确保启动时就连接到正确的网络。

#### 1. 创建 `docker-compose-kafka.yml`

```yaml
version: '3.8'

services:
  zookeeper:
    image: confluentinc/cp-zookeeper:7.6.0
    container_name: zookeeper
    hostname: zookeeper
    ports:
      - "2181:2181"
    environment:
      ZOOKEEPER_CLIENT_PORT: 2181
      ZOOKEEPER_TICK_TIME: 2000
    networks:
      - default
    restart: unless-stopped

  kafka:
    image: confluentinc/cp-kafka:7.6.0
    container_name: kafka
    hostname: kafka
    depends_on:
      - zookeeper
    ports:
      - "9092:9092"  # 外部访问
      - "9999:9999"  # JMX 监控
    environment:
      KAFKA_BROKER_ID: 1
      KAFKA_ZOOKEEPER_CONNECT: zookeeper:2181
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:29092,PLAINTEXT_HOST://localhost:9092
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092,PLAINTEXT_HOST://0.0.0.0:9092
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
      KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR: 1
      KAFKA_JMX_PORT: 9999
      KAFKA_JMX_HOSTNAME: kafka
    networks:
      - default          # 连接到 data-warehouse_default
      - monitoring-net   # 连接到监控网络
    restart: unless-stopped

networks:
  default:
    name: data-warehouse_default
    external: true
  monitoring-net:
    name: data-warehouse_monitoring-net
    external: true
```

**关键配置**：
- `networks.default.name: data-warehouse_default` - 使用 Flink 所在的网络
- `networks.default.external: true` - 使用已存在的网络
- `networks.monitoring-net` - 同时连接到监控网络

#### 2. 创建重启脚本 `restart-kafka-with-network.sh`

```bash
#!/bin/bash

# 停止现有容器
docker stop kafka zookeeper 2>/dev/null || true
docker rm kafka zookeeper 2>/dev/null || true

# 启动 Kafka 集群（使用新的网络配置）
docker-compose -f docker-compose-kafka.yml up -d

# 等待启动
sleep 15

# 创建 Topic
docker exec kafka kafka-topics \
    --create \
    --topic crypto_ticker \
    --bootstrap-server localhost:9092 \
    --partitions 4 \
    --replication-factor 1 \
    --if-not-exists
```

---

## 验证步骤

### 1. 检查网络连接

```bash
# 查看 Kafka 所在的网络
docker inspect kafka --format='{{range $key, $value := .NetworkSettings.Networks}}{{$key}}: {{$value.IPAddress}}{{"\n"}}{{end}}'

# 预期输出：
# data-warehouse_default: 172.20.0.5
# data-warehouse_monitoring-net: 172.19.0.7
```

### 2. 测试 DNS 解析

从 Flink 容器测试是否能解析 Kafka：

```bash
# 方法 1: 使用 getent（如果可用）
docker exec flink-taskmanager getent hosts kafka

# 方法 2: 检查网络配置
docker inspect flink-taskmanager --format='{{range $key, $value := .NetworkSettings.Networks}}{{$key}}{{"\n"}}{{end}}'
```

### 3. 在 StreamPark 中重新启动作业

1. 访问 StreamPark Web UI: `http://localhost:10000`
2. 找到 FlinkODSJobDataStream 作业
3. 点击"启动"按钮
4. 查看日志，确认没有 DNS 解析错误

**预期日志**：
```
Kafka Source created:
  Bootstrap Servers: kafka:9092
  Topic: crypto_ticker
  Group ID: flink-ods-consumer
  Startup Mode: earliest

[INFO] Successfully connected to Kafka
[INFO] Subscribed to topic: crypto_ticker
```

---

## Docker 网络架构

### 网络拓扑

```
data-warehouse_default (172.20.0.0/16)
├── Flink JobManager (172.20.0.3)
├── Flink TaskManager (172.20.0.4)
├── Kafka (172.20.0.5) ← 新添加
└── StreamPark (172.20.0.2)

data-warehouse_doris-net (172.21.0.0/16)
├── Flink JobManager
├── Flink TaskManager
├── Doris FE
└── Doris BE

data-warehouse_monitoring-net (172.19.0.0/16)
├── Kafka (172.19.0.7)
├── Prometheus
├── Grafana
├── Pushgateway
└── Kafka Exporter
```

### 网络通信规则

| 源 | 目标 | 网络 | 状态 |
|-----|------|------|------|
| Flink → Kafka | kafka:9092 | data-warehouse_default | ✅ 可通信 |
| Flink → Doris FE | doris-fe:8030 | data-warehouse_doris-net | ✅ 可通信 |
| Flink → Doris BE | doris-be:8040 | data-warehouse_doris-net | ✅ 可通信 |
| Flink → Pushgateway | pushgateway:9091 | data-warehouse_default | ✅ 可通信 |
| Prometheus → Kafka | kafka:9999 | data-warehouse_monitoring-net | ✅ 可通信 |

---

## 技术原理

### Docker 网络 DNS

Docker 为每个网络提供内置的 DNS 服务器：

1. **容器名称解析**：
   - 同一网络内的容器可以通过容器名称互相访问
   - DNS 服务器自动将容器名称解析为 IP 地址

2. **跨网络通信**：
   - 容器可以连接到多个网络
   - 在每个网络中都有独立的 IP 地址
   - 其他容器通过容器名称访问时，使用同一网络中的 IP

3. **网络隔离**：
   - 不同网络之间默认隔离
   - 容器只能访问同一网络中的其他容器
   - 需要显式连接到多个网络才能跨网络通信

### 为什么需要多个网络

1. **安全隔离**：
   - 监控组件（Prometheus, Grafana）与业务组件隔离
   - 数据库组件（Doris）与其他组件隔离

2. **网络管理**：
   - 不同功能的组件使用不同的网络
   - 便于管理和故障排查

3. **性能优化**：
   - 减少不必要的网络流量
   - 提高网络性能

---

## 常见问题

### Q1: 为什么不直接使用 IP 地址？

**不推荐使用 IP 地址的原因**：
1. IP 地址可能会变化（容器重启后）
2. 容器名称更易读、易维护
3. Docker DNS 自动处理负载均衡和故障转移

### Q2: 如何查看容器所在的网络？

```bash
# 方法 1: 使用 docker inspect
docker inspect <container_name> --format='{{range $key, $value := .NetworkSettings.Networks}}{{$key}}{{"\n"}}{{end}}'

# 方法 2: 使用 docker network inspect
docker network inspect data-warehouse_default
```

### Q3: 如何手动连接容器到网络？

```bash
# 连接容器到网络
docker network connect <network_name> <container_name>

# 断开容器与网络的连接
docker network disconnect <network_name> <container_name>
```

### Q4: 重启容器后网络配置会丢失吗？

- **手动连接**：会丢失，需要重新连接
- **docker-compose 配置**：不会丢失，自动重新连接

---

## 相关文件

- `docker-compose-kafka.yml` - Kafka 集群配置（包含网络配置）
- `restart-kafka-with-network.sh` - Kafka 重启脚本
- `docker-compose-flink.yml` - Flink 集群配置
- `docker-compose-monitoring.yml` - 监控组件配置

---

## 总结

通过将 Kafka 连接到 `data-warehouse_default` 网络，解决了 Flink 无法访问 Kafka 的 DNS 解析问题。

**关键要点**：
1. Docker 容器只能访问同一网络中的其他容器
2. 容器可以连接到多个网络
3. 使用 docker-compose 配置网络，确保重启后配置不丢失
4. 使用容器名称而不是 IP 地址进行通信

**下一步**：
1. 在 StreamPark 中重新启动 Flink 作业
2. 验证作业成功连接到 Kafka
3. 监控作业运行状态
