# Docker 网络配置 - 解决 Flink 无法访问 Doris 的网络隔离问题

## 问题描述

在 StreamPark 中提交 Flink 作业时，出现以下错误：

```
org.apache.doris.flink.exception.DorisRuntimeException: No Doris FE is available, please check configuration or cluster status.
```

## 问题原因

1. **Docker 网络隔离**：不同 Docker Compose 项目的容器默认在不同的网络中
   - Doris 容器在 `data-warehouse_doris-net` 网络
   - Flink 容器在 `data-warehouse_flink-net` 网络
   - 两个网络之间无法直接通信

2. **配置文件地址错误**：`application-dev.yml` 中使用 `127.0.0.1` 作为 Doris 地址
   - 在容器内，`127.0.0.1` 指向容器自身，而不是宿主机
   - 需要使用容器名称（如 `doris-fe`）进行容器间通信

## 解决方案

### 1. 修改 Flink 网络配置

编辑 `docker-compose-flink.yml`，将 Flink 集群连接到 Doris 网络：

```yaml
services:
  jobmanager:
    networks:
      - flink-net
      - default  # 连接到默认网络（Kafka）
      - doris-net  # 连接到 Doris 网络
  
  taskmanager:
    networks:
      - flink-net
      - default  # 连接到默认网络（Kafka）
      - doris-net  # 连接到 Doris 网络

networks:
  flink-net:
    driver: bridge
  default:
    external: true
    name: data-warehouse_default
  doris-net:  # 添加 Doris 网络
    external: true
    name: data-warehouse_doris-net
```

### 2. 修改配置文件中的 Doris 地址

编辑 `src/main/resources/config/application-dev.yml`：

```yaml
# Doris 配置
# 注意：在 Docker 容器中运行时，使用容器名称 doris-fe 而不是 127.0.0.1
doris:
  fe:
    http-url: http://doris-fe:8030
    jdbc-url: jdbc:mysql://doris-fe:9030
    username: root
    password: ""
```

### 3. 重启 Flink 集群

```bash
# 停止并删除旧容器
docker-compose -f docker-compose-flink.yml down

# 启动新容器（应用新的网络配置）
docker-compose -f docker-compose-flink.yml up -d
```

### 4. 验证网络连通性

```bash
# 验证 Flink JobManager 可以访问 Doris FE
docker exec flink-jobmanager curl -s http://doris-fe:8030/api/bootstrap

# 应该返回 JSON 响应，包含 Doris 集群信息
```

### 5. 重新编译项目

```bash
# 重新编译，将新的配置文件打包到 JAR 中
mvn clean package -DskipTests
```

### 6. 在 StreamPark 中重新提交作业

- Main Class: `com.crypto.dw.jobs.FlinkODSJobDataStream`
- Dynamic Properties: `APP_ENV=dev`

## 验证结果

1. ✅ Flink JobManager 可以访问 Doris FE
   ```bash
   $ docker exec flink-jobmanager curl -s http://doris-fe:8030/api/bootstrap
   {"msg":"success","code":0,"data":{"version":"doris-3.0.3"},"count":0}
   ```

2. ✅ 配置文件已更新
   ```bash
   $ jar -xf target/realtime-crypto-datawarehouse-1.0.0.jar config/application-dev.yml
   $ cat config/application-dev.yml | grep -A 3 "doris:"
   doris:
     fe:
       http-url: http://doris-fe:8030
       jdbc-url: jdbc:mysql://doris-fe:9030
   ```

## 关键技术点

### Docker 容器间通信

1. **使用容器名称**：在 Docker 网络中，容器可以通过容器名称相互访问
   - ✅ 正确：`http://doris-fe:8030`
   - ❌ 错误：`http://127.0.0.1:8030`（容器内指向自身）
   - ❌ 错误：`http://localhost:8030`（容器内指向自身）

2. **跨网络通信**：容器需要连接到相同的 Docker 网络才能通信
   - 使用 `networks` 配置将容器连接到多个网络
   - 使用 `external: true` 引用其他 Docker Compose 项目创建的网络

3. **网络命名规则**：Docker Compose 会自动添加项目名称前缀
   - 项目名称：`data-warehouse`
   - 网络名称：`doris-net`
   - 完整名称：`data-warehouse_doris-net`

### 配置文件加载

1. **环境变量**：通过 `APP_ENV` 环境变量指定配置文件
   - `APP_ENV=dev` → 加载 `application-dev.yml`
   - `APP_ENV=prod` → 加载 `application-prod.yml`

2. **配置覆盖**：`application-dev.yml` 会覆盖 `application.yml` 中的配置

3. **打包到 JAR**：配置文件需要打包到 JAR 的 `config/` 目录中
   - Maven 会自动将 `src/main/resources/config/` 下的文件打包

## 相关文件

- `docker-compose-flink.yml` - Flink 集群 Docker Compose 配置
- `src/main/resources/config/application-dev.yml` - 开发环境配置
- `test/streampark-submit-guide.sh` - StreamPark 作业提交指南

## 参考文档

- [Docker 网络配置 - 解决 BE 地址映射问题](202603190030-Docker网络-解决BE地址映射问题.md)
- [StreamPark 作业提交 - 配置主类和参数](202603212005-StreamPark作业提交-配置主类和参数.md)

---

**创建时间**: 2026-03-21 20:30  
**问题类型**: Docker 网络配置  
**解决状态**: ✅ 已解决
