# StreamPark 连接 Flink 集群 - 网络配置和集群添加指南

## 问题描述

在 StreamPark Web UI 中添加 Flink 集群时，提示 "Be remote cluster connection failed, please check!"

## 问题分析

1. **网络连通性**：StreamPark 和 Flink 必须在同一个 Docker 网络中
2. **地址配置**：需要使用容器内部的主机名，而不是 localhost
3. **端口配置**：确保使用正确的 RPC 端口（6123）和 REST 端口（8081）

## 解决方案

### 1. 网络配置验证

已经在 `docker-compose-streampark.yml` 中添加了 `flink-net` 网络：

```yaml
networks:
  - streampark-net
  - doris-net
  - flink-net  # ✅ 已添加
  - default
```

验证网络连通性：
```bash
# 测试 ping
docker exec streampark ping -c 3 jobmanager

# 测试 REST API
docker exec streampark curl -s http://jobmanager:8081/overview
```

### 2. StreamPark Web UI 配置步骤

访问 StreamPark：http://localhost:10000
- 用户名：admin
- 密码：streampark

#### 步骤 1：进入集群管理
1. 登录后，点击左侧菜单 "Setting" → "Flink Cluster"
2. 点击右上角 "Add New" 按钮

#### 步骤 2：填写集群配置

**基本信息**：
- **Cluster Name**（集群名称）：`Flink Docker Cluster`
- **Cluster Type**（集群类型）：选择 `Standalone`
- **Execution Mode**（执行模式）：选择 `Remote`

**连接配置**（重要！）：
- **Address**（地址）：`http://jobmanager:8081`
  - ⚠️ 不要使用 `localhost:8081`
  - ⚠️ 不要使用 `jobmanager:8081`（缺少协议前缀）
  - ✅ 必须使用完整 URL：`http://jobmanager:8081`

**高级配置**（可选）：
- **Description**（描述）：`Docker Standalone Flink Cluster for StreamPark`
- **Flink Version**（Flink 版本）：选择 `1.17`

#### 步骤 3：测试连接
1. 填写完成后，点击 "Test Connection" 按钮
2. 如果显示 "Connection successful"，说明配置正确
3. 点击 "Submit" 保存配置

### 3. 常见错误和解决方法

#### 错误 1：Connection failed
**原因**：地址格式错误
**解决**：
- ❌ 错误：`http://jobmanager:8081`
- ❌ 错误：`localhost:8081`
- ✅ 正确：`jobmanager:8081`

#### 错误 2：Network unreachable
**原因**：StreamPark 容器没有连接到 flink-net 网络
**解决**：
```bash
# 重启 StreamPark
docker-compose -f docker-compose-streampark.yml down
docker-compose -f docker-compose-streampark.yml up -d

# 验证网络
docker inspect streampark --format '{{range $key, $value := .NetworkSettings.Networks}}{{$key}} {{end}}'
```

#### 错误 3：Flink 服务未启动
**原因**：Flink JobManager 或 TaskManager 未运行
**解决**：
```bash
# 检查 Flink 状态
docker ps --filter "name=flink"

# 重启 Flink
docker-compose -f docker-compose-flink.yml restart
```

### 4. 验证集群状态

#### 方法 1：StreamPark Web UI
1. 进入 "Setting" → "Flink Cluster"
2. 查看集群状态，应该显示为 "Running"
3. 点击集群名称，可以看到 TaskManager 数量和可用 Slots

#### 方法 2：命令行验证
```bash
# 从 StreamPark 容器访问 Flink REST API
docker exec streampark curl -s http://jobmanager:8081/overview

# 预期输出（JSON 格式）：
# {
#   "taskmanagers": 1,
#   "slots-total": 4,
#   "slots-available": 4,
#   "jobs-running": 0,
#   ...
# }
```

#### 方法 3：浏览器访问
- Flink Web UI：http://localhost:8081
- StreamPark Web UI：http://localhost:10000

### 5. 网络架构说明

```
┌─────────────────────────────────────────────────────────┐
│                    Docker Networks                       │
├─────────────────────────────────────────────────────────┤
│                                                          │
│  ┌──────────────────┐         ┌──────────────────┐     │
│  │   StreamPark     │         │  Flink Cluster   │     │
│  │  (Port 10000)    │◄───────►│  JobManager      │     │
│  │                  │         │  (Port 8081)     │     │
│  └──────────────────┘         │  TaskManager     │     │
│          │                    └──────────────────┘     │
│          │                             │               │
│          │                             │               │
│  ┌───────┴──────────┐         ┌───────┴──────────┐    │
│  │  flink-net       │         │  default         │    │
│  │  (Bridge)        │         │  (Kafka)         │    │
│  └──────────────────┘         └──────────────────┘    │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

### 6. 配置文件参考

#### docker-compose-streampark.yml
```yaml
networks:
  - streampark-net
  - doris-net
  - flink-net  # 连接到 Flink 网络
  - default    # 连接到默认网络（Kafka）
```

#### docker-compose-flink.yml
```yaml
networks:
  - flink-net
  - default  # 连接到默认网络（Kafka）
```

## 测试结果

✅ 网络连通性测试通过
✅ Flink REST API 可访问
✅ StreamPark 容器已连接到 flink-net 网络
✅ Flink 集群运行正常（1 TaskManager，4 Slots）

## 下一步操作

1. 在 StreamPark Web UI 中添加 Flink 集群（按照上述步骤）
2. 创建 Flink 应用程序
3. 部署和管理 Flink 作业

## 相关文档

- [StreamPark 官方文档](https://streampark.apache.org/zh-CN/docs/get-started/installation-docker)
- [Flink Standalone 集群配置](../docker-compose-flink.yml)
- [StreamPark 配置文件](../docker-compose-streampark.yml)

## 时间记录

- 创建时间：2026-03-21 18:00
- 时区：中国（东八区）
