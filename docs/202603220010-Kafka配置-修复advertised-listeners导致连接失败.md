# Kafka 配置 - 修复 advertised.listeners 导致连接失败

**时间**: 2026-03-22 00:10  
**问题**: Flink 配置正确但仍无法连接 Kafka，尝试连接 localhost:9092  
**状态**: ✅ 已解决

---

## 问题描述

虽然 Flink 配置文件中设置了 `kafka.bootstrap-servers: kafka:9092`，但日志显示:

```
AdminClientConfig values:
bootstrap.servers = [kafka:9092]  ✅ 配置正确

但后续连接时:
Connection to node 1 (localhost/127.0.0.1:9092) could not be established.  ❌ 连接错误
```

**问题现象**:
- Flink 初始连接使用 `kafka:9092` ✅
- Kafka 返回元数据后，Flink 尝试连接 `localhost:9092` ❌
- 连接失败，提示 "Broker may not be available"

---

## 根本原因

**Kafka advertised.listeners 配置错误** ⭐⭐⭐

### Kafka 客户端连接流程

1. **Bootstrap 连接**: 客户端连接 `kafka:9092`（bootstrap.servers）
2. **获取元数据**: Kafka 返回 broker 列表和地址
3. **实际连接**: 客户端使用元数据中的地址连接 broker

### 问题配置

```yaml
# 错误配置
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:9092
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092,PLAINTEXT_HOST://0.0.0.0:9092
```

**问题分析**:
- `PLAINTEXT://kafka:9092`: Docker 内部访问，监听 29092，广播 kafka:9092
- `PLAINTEXT_HOST://localhost:9092`: 宿主机访问，监听 9092，广播 localhost:9092
- Flink 连接 `kafka:9092` 时，Kafka 可能返回 `localhost:9092`（因为端口冲突）
- Flink 尝试连接 `localhost:9092`，失败

### 为什么会返回 localhost:9092

Kafka 的监听器选择逻辑:
1. 客户端连接到某个监听器
2. Kafka 返回该监听器对应的 advertised.listener
3. 如果端口配置有冲突，可能返回错误的 advertised.listener

在原配置中:
- `PLAINTEXT` 监听 29092，广播 kafka:9092
- `PLAINTEXT_HOST` 监听 9092，广播 localhost:9092
- 端口映射 `9092:9092` 映射到容器的 9092（PLAINTEXT_HOST）
- Docker 内部访问 `kafka:9092` 时，可能被路由到 PLAINTEXT_HOST 监听器
- 因此返回 `localhost:9092`

---

## 解决方案

### 修改 Kafka 配置

修改 `docker-compose-kafka.yml`，分离 Docker 内部和宿主机访问的端口:

```yaml
services:
  kafka:
    ports:
      - "9092:9092"  # Docker 内部访问端口
      - "9093:9093"  # 宿主机访问端口
      - "9999:9999"  # JMX 端口
    environment:
      # 监听器配置（支持内外部访问）
      # PLAINTEXT: Docker 内部通信，监听 9092，广播为 kafka:9092
      # PLAINTEXT_HOST: 宿主机访问，监听 9093，广播为 localhost:9093
      KAFKA_LISTENER_SECURITY_PROTOCOL_MAP: PLAINTEXT:PLAINTEXT,PLAINTEXT_HOST:PLAINTEXT
      KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:9093
      KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,PLAINTEXT_HOST://0.0.0.0:9093
      KAFKA_INTER_BROKER_LISTENER_NAME: PLAINTEXT
```

**关键改动**:
1. `PLAINTEXT` 监听 9092（不是 29092）
2. `PLAINTEXT_HOST` 监听 9093（不是 9092）
3. 端口映射: `9092:9092` 和 `9093:9093`

### 重启 Kafka

```bash
bash manage-kafka.sh restart
```

---

## 验证结果

### 1. 测试 Docker 内部访问

```bash
# 从 Kafka 容器内部测试
docker exec kafka kafka-broker-api-versions --bootstrap-server kafka:9092

# 预期输出
kafka:9092 (id: 1 rack: null) -> (
    Produce(0): 0 to 9 [usable: 9],
    Fetch(1): 0 to 15 [usable: 15],
    ...
)
```

### 2. 测试 Flink 连接

```bash
# 从 Flink 容器测试 Kafka 连接
docker exec flink-jobmanager bash -c "timeout 5 bash -c '</dev/tcp/kafka/9092' && echo 'Kafka connection successful' || echo 'Kafka connection failed'"

# 预期输出
Kafka connection successful
```

### 3. 测试宿主机访问

```bash
# 从宿主机测试（使用 9093 端口）
kafka-console-consumer.sh --bootstrap-server localhost:9093 --topic crypto_ticker --from-beginning

# 或者使用 Docker 命令
docker exec kafka kafka-console-consumer --bootstrap-server localhost:9093 --topic crypto_ticker --from-beginning
```

---

## 关键技术点

### 1. Kafka 监听器机制

**监听器类型**:
- `LISTENERS`: Kafka 实际监听的地址和端口
- `ADVERTISED_LISTENERS`: Kafka 广播给客户端的地址和端口
- `LISTENER_SECURITY_PROTOCOL_MAP`: 监听器名称到安全协议的映射

**工作流程**:
```
1. 客户端连接 bootstrap.servers (kafka:9092)
   ↓
2. Kafka 返回 advertised.listeners (kafka:9092)
   ↓
3. 客户端使用 advertised.listeners 连接 broker
```

### 2. 端口分离策略

**为什么需要分离端口**:
- Docker 内部访问: 使用容器名称 `kafka:9092`
- 宿主机访问: 使用 `localhost:9093`
- 避免端口冲突和路由错误

**端口映射**:
```
宿主机端口 → 容器端口 → 监听器
9092       → 9092      → PLAINTEXT (Docker 内部)
9093       → 9093      → PLAINTEXT_HOST (宿主机)
```

### 3. 常见错误配置

**错误 1: 所有监听器使用同一个端口**
```yaml
# ❌ 错误
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:9092
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:9092,PLAINTEXT_HOST://0.0.0.0:9092
```
问题: 端口冲突，Kafka 无法启动

**错误 2: 监听端口和广播端口不一致**
```yaml
# ❌ 错误
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092
KAFKA_LISTENERS: PLAINTEXT://0.0.0.0:29092
```
问题: 客户端连接 kafka:9092，但 Kafka 实际监听 29092，连接失败

**错误 3: 广播 localhost 给 Docker 容器**
```yaml
# ❌ 错误
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://localhost:9092
```
问题: Docker 容器内的 localhost 指向容器自己，无法访问 Kafka

### 4. 最佳实践

**生产环境配置**:
```yaml
# Docker 环境
KAFKA_ADVERTISED_LISTENERS: PLAINTEXT://kafka:9092,PLAINTEXT_HOST://localhost:9093

# Kubernetes 环境
KAFKA_ADVERTISED_LISTENERS: INTERNAL://kafka-0.kafka-headless:9092,EXTERNAL://kafka.example.com:9093

# 多网卡环境
KAFKA_ADVERTISED_LISTENERS: INTERNAL://10.0.1.10:9092,EXTERNAL://203.0.113.10:9093
```

---

## 访问方式总结

| 访问方式 | 地址 | 端口 | 用途 |
|---------|------|------|------|
| Docker 内部 | `kafka:9092` | 9092 | Flink、Doris 等容器访问 |
| 宿主机 | `localhost:9093` | 9093 | 本地开发、测试工具 |
| JMX 监控 | `kafka:9999` | 9999 | Prometheus、Grafana 监控 |

---

## 相关配置更新

### 1. Flink 配置

`application-dev.yml`:
```yaml
kafka:
  bootstrap-servers: kafka:9092  # ✅ Docker 内部访问
```

### 2. 数据采集器配置

如果数据采集器在宿主机运行:
```yaml
kafka:
  bootstrap-servers: localhost:9093  # ✅ 宿主机访问
```

如果数据采集器在 Docker 容器运行:
```yaml
kafka:
  bootstrap-servers: kafka:9092  # ✅ Docker 内部访问
```

### 3. 监控配置

Kafka Exporter (`docker-compose-monitoring.yml`):
```yaml
kafka-exporter:
  command:
    - '--kafka.server=kafka:9092'  # ✅ Docker 内部访问
```

---

## 故障排查

### 问题 1: 仍然连接 localhost

**检查步骤**:
```bash
# 1. 查看 Kafka 配置
docker exec kafka env | grep KAFKA_ADVERTISED_LISTENERS

# 2. 查看 Kafka 日志
docker logs kafka | grep "advertised.listeners"

# 3. 测试连接
docker exec kafka kafka-broker-api-versions --bootstrap-server kafka:9092
```

### 问题 2: 宿主机无法访问

**检查步骤**:
```bash
# 1. 检查端口映射
docker ps --filter "name=kafka" --format "table {{.Names}}\t{{.Ports}}"

# 2. 测试端口
telnet localhost 9093

# 3. 查看防火墙
# Windows: 检查 Windows Defender 防火墙
# Linux: sudo iptables -L
```

### 问题 3: Topic 不存在

**解决方法**:
```bash
# 创建 Topic
docker exec kafka kafka-topics --create \
  --bootstrap-server kafka:9092 \
  --topic crypto_ticker \
  --partitions 9 \
  --replication-factor 1
```

---

## 修改文件

- `docker-compose-kafka.yml` - Kafka 监听器配置

---

## 参考资料

- [Kafka Listeners 官方文档](https://kafka.apache.org/documentation/#listeners)
- [Docker 网络最佳实践](https://docs.docker.com/network/)
- [Kafka Docker 部署指南](https://docs.confluent.io/platform/current/installation/docker/config-reference.html)

---

## 总结

**问题根源**: Kafka 的 `advertised.listeners` 配置错误，导致客户端获取到错误的 broker 地址

**解决方案**: 分离 Docker 内部和宿主机访问的端口，使用不同的监听器

**关键经验**:
- Kafka 客户端连接分为两步：bootstrap 连接和实际连接
- `advertised.listeners` 决定客户端实际连接的地址
- Docker 环境需要区分内部访问和外部访问
- 端口分离可以避免路由冲突和配置错误
- 监听器配置是 Kafka Docker 部署的关键难点
