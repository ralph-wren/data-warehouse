# Flink 作业 - 修复 Doris Sink 参数和环境配置问题

**时间**: 2026-03-22 00:40  
**问题**: FlinkODSJobSQL 启动失败,Doris Sink 参数不支持  
**状态**: ✅ 已解决

---

## 问题描述

运行 `FlinkODSJobSQL` 时遇到两个主要问题:

### 1. Doris Sink 参数过时

```
Unsupported options:
sink.batch.interval
sink.batch.size

Supported options:
sink.buffer-flush.max-rows
sink.buffer-flush.interval
```

### 2. 环境配置错误

- 在宿主机运行作业时使用了 Docker 环境配置 (`doris-fe:8030`)
- 导致无法连接到 Doris FE: `No Doris FE is available`

### 3. Java 11+ 模块访问问题

```
java.lang.reflect.InaccessibleObjectException: Unable to make field private final byte[] java.lang.String.value accessible: module java.base does not "opens java.lang" to unnamed module
```

---

## 根本原因

### 1. Doris Connector 版本更新

Doris Connector 1.6.2 版本中,参数名称已更改:
- 旧参数: `sink.batch.size`, `sink.batch.interval`
- 新参数: `sink.buffer-flush.max-rows`, `sink.buffer-flush.interval`

### 2. 环境配置混淆

项目有两个环境配置文件:
- `application-dev.yml`: 本地开发环境,使用 `localhost` 或 `127.0.0.1`
- `application-docker.yml`: Docker 环境,使用容器名称 (`doris-fe`, `kafka`)

运行作业时需要根据运行环境选择正确的配置:
- **宿主机运行**: 使用 `APP_ENV=dev`
- **Docker 容器运行**: 使用 `APP_ENV=docker`

### 3. Maven exec 插件 JVM 参数

Java 11+ 需要添加 `--add-opens` 参数才能访问内部 API,但 `MAVEN_OPTS` 环境变量在某些情况下不生效。

---

## 解决方案

### 1. 修复 Doris Sink 参数 (已在代码中修复)

`FlinkODSJobSQL.java` 第 141-142 行已经使用新参数:

```java
"    'sink.buffer-flush.max-rows' = '" + batchSize + "',\n" +
"    'sink.buffer-flush.interval' = '" + batchInterval + "ms',\n" +
```

### 2. 修复运行脚本,支持环境参数

修改 `run-flink-ods-sql.sh` 和 `run-flink-ods-datastream.sh`:

```bash
# 设置应用环境 (支持命令行参数)
if [ -n "$1" ]; then
    export APP_ENV=$1
else
    export APP_ENV=${APP_ENV:-dev}
fi
echo "APP_ENV: $APP_ENV"
```

使用方式:
```bash
# 本地开发环境 (默认)
bash run-flink-ods-sql.sh
bash run-flink-ods-sql.sh dev

# Docker 环境
bash run-flink-ods-sql.sh docker
```

### 3. 添加 JVM 参数支持

在运行脚本中添加 `MAVEN_OPTS`:

```bash
export MAVEN_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"
```

---

## 验证步骤

### 1. 检查 Doris 是否运行

```bash
docker ps --filter "name=doris"
curl http://localhost:8030/api/bootstrap
```

### 2. 检查 Kafka 是否有数据

```bash
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic crypto_ticker \
  --from-beginning \
  --max-messages 5
```

### 3. 运行 Flink 作业

```bash
# 使用 dev 环境 (宿主机运行)
bash run-flink-ods-datastream.sh dev

# 或使用 SQL 作业
bash run-flink-ods-sql.sh dev
```

### 4. 检查作业状态

```bash
# 查看 Flink 集群中的作业
curl http://localhost:8081/jobs | python -m json.tool

# 查看 Flink Web UI
open http://localhost:8081
```

---

## 关键配置对比

### Dev 环境 (application-dev.yml)

```yaml
kafka:
  bootstrap-servers: localhost:9093  # 宿主机 Kafka 地址

doris:
  fe:
    http-url: http://127.0.0.1:8030  # 宿主机 Doris FE
    jdbc-url: jdbc:mysql://127.0.0.1:9030
  be:
    nodes: "127.0.0.1:8040"  # 宿主机 Doris BE
```

### Docker 环境 (application-docker.yml)

```yaml
kafka:
  bootstrap-servers: kafka:9092  # Docker 容器名称

doris:
  fe:
    http-url: http://doris-fe:8030  # Docker 容器名称
    jdbc-url: jdbc:mysql://doris-fe:9030
  be:
    nodes: "doris-be:8040"  # Docker 容器名称
```

---

## 注意事项

### 1. 环境选择原则

- **宿主机运行 Flink 作业**: 使用 `dev` 环境
- **Docker 容器运行 Flink 作业**: 使用 `docker` 环境
- **StreamPark 提交作业**: 使用 `docker` 环境 (StreamPark 在 Docker 容器中)

### 2. Kafka 端口说明

- `9092`: Docker 内部访问端口 (容器之间通信)
- `9093`: 宿主机访问端口 (宿主机连接 Kafka)

### 3. DataStream vs SQL 作业

- **DataStream API** (`FlinkODSJobDataStream`): 更稳定,推荐使用
- **SQL API** (`FlinkODSJobSQL`): 更简洁,但可能有兼容性问题

### 4. Java 11+ 模块访问

如果遇到 `InaccessibleObjectException`,确保设置了 JVM 参数:
```bash
export MAVEN_OPTS="--add-opens=java.base/java.lang=ALL-UNNAMED --add-opens=java.base/java.util=ALL-UNNAMED"
```

---

## 相关文件

- `src/main/java/com/crypto/dw/flink/FlinkODSJobSQL.java` - SQL 作业 (已修复参数)
- `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java` - DataStream 作业
- `src/main/resources/config/application-dev.yml` - 本地开发环境配置
- `src/main/resources/config/application-docker.yml` - Docker 环境配置
- `run-flink-ods-sql.sh` - SQL 作业运行脚本 (已添加环境参数支持)
- `run-flink-ods-datastream.sh` - DataStream 作业运行脚本 (已添加环境参数支持)

---

## 总结

本次解决了三个关键问题:

1. **Doris Sink 参数更新**: 使用新版本参数 `sink.buffer-flush.*`
2. **环境配置分离**: 根据运行环境选择正确的配置文件 (`dev` vs `docker`)
3. **JVM 参数配置**: 添加 `--add-opens` 参数解决 Java 11+ 模块访问问题

现在可以在宿主机上成功运行 Flink 作业,连接 Docker 容器中的 Kafka 和 Doris。
