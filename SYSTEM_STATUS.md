# 系统状态总结

**更新时间**: 2026-03-21 23:17 (东八区)

## 服务状态

### ✅ 核心服务（全部运行中）

| 服务 | 状态 | 访问地址 | 说明 |
|-----|------|---------|------|
| Kafka | ✅ 运行中 | localhost:9092 | 消息队列 |
| Zookeeper | ✅ 运行中 | localhost:2181 | Kafka 协调服务 |
| Doris FE | ✅ 运行中 | localhost:9030 (MySQL) | 数据仓库前端 |
| Doris BE | ✅ 运行中 | localhost:8040 (HTTP) | 数据仓库后端 |
| Flink JobManager | ✅ 运行中 | http://localhost:8081 | 流处理引擎 |
| Flink TaskManager | ✅ 运行中 | - | 任务执行器 |
| Prometheus | ✅ 运行中 | http://localhost:9090 | 监控数据收集 |
| Grafana | ✅ 运行中 | http://localhost:3000 | 监控可视化 |

### ❌ 可选服务（未运行）

| 服务 | 状态 | 访问地址 | 说明 |
|-----|------|---------|------|
| StreamPark | ❌ 未运行 | http://localhost:10000 | Flink 作业管理平台 |

## Kafka Topic

| Topic | 分区数 | 副本数 | 保留时间 | 压缩格式 |
|-------|--------|--------|----------|----------|
| crypto_ticker | 4 | 1 | 7 天 | lz4 |

## Docker 网络

| 网络名称 | 用途 | 连接的服务 |
|---------|------|-----------|
| data-warehouse_default | 主网络 | Kafka, Zookeeper, Doris, Flink |
| data-warehouse_monitoring-net | 监控网络 | Kafka, Prometheus, Grafana, Exporters |
| data-warehouse_doris-net | Doris 专用 | Doris FE, Doris BE |
| data-warehouse_flink-net | Flink 专用 | Flink JobManager, TaskManager |

## 管理脚本

### 单个组件管理

```bash
# Kafka
bash manage-kafka.sh [start|stop|restart|clean]

# Doris
bash manage-doris.sh [start|stop|restart]

# Flink
bash manage-flink.sh [start|stop|restart]

# 监控系统
bash manage-monitoring.sh [start|stop|restart]

# StreamPark
bash manage-streampark.sh [start|stop|restart]
```

### 全局管理

```bash
# 启动所有服务
bash start-all.sh

# 停止所有服务
bash stop-all.sh

# 检查服务状态
bash check-services.sh
```

## 最近解决的问题

### 1. 配置文件加载问题 ✅
- **问题**: StreamPark 环境中 JAR 包内配置文件无法加载
- **解决**: 使用 JAR FileSystem API 直接读取
- **文档**: [202603212215-配置加载-使用JAR文件系统解决ChildFirstClassLoader问题.md](./docs/202603212215-配置加载-使用JAR文件系统解决ChildFirstClassLoader问题.md)

### 2. Java 版本不兼容 ✅
- **问题**: Flink 集群使用 Java 8，项目使用 Java 11 编译
- **解决**: 升级 Flink 镜像到 Java 11
- **文档**: [202603212230-Flink集群-升级Java11解决版本不兼容问题.md](./docs/202603212230-Flink集群-升级Java11解决版本不兼容问题.md)

### 3. Docker 网络 DNS 解析 ✅
- **问题**: Flink 无法访问 Kafka
- **解决**: 确保容器在同一网络中
- **文档**: [202603212250-Docker网络-Flink无法访问Kafka的DNS解析问题.md](./docs/202603212250-Docker网络-Flink无法访问Kafka的DNS解析问题.md)

### 4. 脚本整理 ✅
- **问题**: 脚本太多，管理混乱
- **解决**: 每个组件一个统一管理脚本
- **文档**: [202603212258-脚本整理-统一管理脚本简化运维操作.md](./docs/202603212258-脚本整理-统一管理脚本简化运维操作.md)

### 5. Kafka 元数据版本冲突 ✅
- **问题**: Kafka 一直重启，Zookeeper 未启动
- **解决**: 清理数据卷，解决 KRaft vs Zookeeper 模式冲突
- **文档**: [202603212313-Kafka启动-解决元数据版本冲突导致重启问题.md](./docs/202603212313-Kafka启动-解决元数据版本冲突导致重启问题.md)

### 6. 监控系统网络配置 ✅
- **问题**: 监控系统启动失败，网络冲突
- **解决**: 使用 external: true 引用已存在的网络
- **文档**: [202603212317-Docker网络-修复监控系统网络配置问题.md](./docs/202603212317-Docker网络-修复监控系统网络配置问题.md)

## 快速启动指南

### 1. 启动所有核心服务

```bash
# 一键启动
bash start-all.sh
```

这将按顺序启动：
1. Kafka + Zookeeper（包含 Topic 创建）
2. Doris FE + BE
3. Flink JobManager + TaskManager
4. 监控系统（Prometheus + Grafana）
5. 编译项目

### 2. 运行 Flink 作业

```bash
# 数据采集（WebSocket -> Kafka）
bash run-collector.sh

# ODS 层（Kafka -> Doris ODS）
bash run-flink-ods-datastream.sh

# DWD 层（ODS -> DWD）
bash run-flink-dwd-sql.sh

# DWS 层（Kafka -> DWS 1分钟K线）
bash run-flink-dws-1min-sql.sh
```

### 3. 查看监控

- **Flink WebUI**: http://localhost:8081
- **Prometheus**: http://localhost:9090
- **Grafana**: http://localhost:3000 (admin/admin)

### 4. 查询数据

```bash
# 连接 Doris
mysql -h 127.0.0.1 -P 9030 -u root

# 查询 ODS 表
SELECT COUNT(*) FROM crypto_dw.ods_crypto_ticker;

# 查询 DWD 表
SELECT COUNT(*) FROM crypto_dw.dwd_crypto_ticker_detail;
```

## 故障排查

### 服务无法启动

```bash
# 检查服务状态
bash check-services.sh

# 查看容器日志
docker logs <container_name>

# 重启特定服务
bash manage-<component>.sh restart
```

### Kafka 问题

```bash
# 清理 Kafka 数据（解决启动问题）
bash manage-kafka.sh clean
bash manage-kafka.sh start

# 查看 Topic
docker exec kafka kafka-topics --list --bootstrap-server localhost:9092

# 查看消息
docker exec kafka kafka-console-consumer \
    --bootstrap-server localhost:9092 \
    --topic crypto_ticker \
    --from-beginning \
    --max-messages 10
```

### 网络问题

```bash
# 查看网络
docker network ls

# 查看容器网络连接
docker inspect <container> --format '{{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}'

# 手动连接容器到网络
docker network connect <network_name> <container_name>
```

## 系统架构

```
┌─────────────────────────────────────────────────────────────┐
│                    数据流向                                   │
├─────────────────────────────────────────────────────────────┤
│                                                              │
│  OKX WebSocket                                               │
│       ↓                                                      │
│  Data Collector (run-collector.sh)                          │
│       ↓                                                      │
│  Kafka (crypto_ticker topic)                                │
│       ↓                                                      │
│  Flink ODS Job (run-flink-ods-datastream.sh)               │
│       ↓                                                      │
│  Doris ODS 表 (ods_crypto_ticker)                           │
│       ↓                                                      │
│  Flink DWD Job (run-flink-dwd-sql.sh)                      │
│       ↓                                                      │
│  Doris DWD 表 (dwd_crypto_ticker_detail)                    │
│                                                              │
│  Kafka (crypto_ticker topic)                                │
│       ↓                                                      │
│  Flink DWS Job (run-flink-dws-1min-sql.sh)                 │
│       ↓                                                      │
│  Doris DWS 表 (dws_crypto_kline_1min)                       │
│                                                              │
└─────────────────────────────────────────────────────────────┘
```

## 技术栈

| 组件 | 版本 | 说明 |
|-----|------|------|
| Java | 11 | 运行环境 |
| Flink | 1.17.0 | 流处理引擎 |
| Kafka | 7.6.0 (Confluent) | 消息队列 |
| Zookeeper | 7.6.0 (Confluent) | Kafka 协调 |
| Doris | 3.0.7 | 数据仓库 |
| Prometheus | latest | 监控收集 |
| Grafana | latest | 监控可视化 |
| Docker | - | 容器化 |
| Docker Compose | - | 容器编排 |

## 下一步

### 可选操作

1. **启动 StreamPark**（Flink 作业管理平台）:
   ```bash
   bash manage-streampark.sh start
   ```

2. **配置告警**:
   - 在 Grafana 中配置告警规则
   - 设置通知渠道（邮件、钉钉等）

3. **数据备份**:
   - 配置 Doris 数据备份策略
   - 定期备份 Kafka 配置

4. **性能优化**:
   - 调整 Flink 并行度
   - 优化 Doris 表结构
   - 调整 Kafka 分区数

## 相关文档

- [问题解决汇总](./问题解决汇总.md) - 所有问题的详细记录
- [README](./README.md) - 项目说明
- [docs/](./docs/) - 详细文档目录

---

**系统状态**: ✅ 所有核心服务正常运行，可以开始处理数据！
