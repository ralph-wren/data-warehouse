# StreamPark 快速提交指南

## 前提条件

1. ✅ StreamPark 已启动：http://localhost:10000
2. ✅ Flink 集群已添加到 StreamPark
3. ✅ 项目已编译打包：`mvn clean package -DskipTests`
4. ✅ JAR 包位置：`target/realtime-crypto-datawarehouse-1.0.0.jar`

## 提交步骤

### 1. 登录 StreamPark

- URL: http://localhost:10000
- 用户名：`admin`
- 密码：`streampark`

### 2. 创建应用

点击左侧菜单 **Application** → **Add New**

### 3. 填写配置

#### 基本信息

| 字段 | 值 |
|------|-----|
| Application Name | `Flink ODS Job` |
| Development Mode | `Custom Code` |
| Execution Mode | `Remote` |
| Flink Cluster | 选择你的 Flink 集群 |

#### 程序配置

| 字段 | 值 |
|------|-----|
| Program Jar | 上传 `target/realtime-crypto-datawarehouse-1.0.0.jar` |
| Main Class | `com.crypto.dw.flink.FlinkODSJobDataStream` |
| Program Args | 留空 |

#### Flink 配置

```yaml
taskmanager.numberOfTaskSlots: 4
parallelism.default: 4
```

#### 环境变量（重要！）

```
APP_ENV=dev
```

### 4. 保存并启动

1. 点击 **Submit** 保存配置
2. 点击 **Start** 启动作业
3. 查看日志确认启动成功

## 可用的主类

| 作业 | 主类 | 说明 |
|------|------|------|
| ODS (DataStream) | `com.crypto.dw.flink.FlinkODSJobDataStream` | Kafka → Doris ODS |
| ODS (SQL) | `com.crypto.dw.flink.FlinkODSJobSQL` | Kafka → Doris ODS (SQL) |
| DWD (SQL) | `com.crypto.dw.flink.FlinkDWDJobSQL` | ODS → DWD 数据清洗 |
| DWS (1分钟) | `com.crypto.dw.flink.FlinkDWSJob1MinSQL` | DWD → DWS 聚合 |

## 常见问题

### 1. 配置文件加载失败

**错误**：`NullPointerException` 或 `Config file not found`

**解决**：
- 确保环境变量 `APP_ENV=dev` 已设置
- 检查 JAR 包中是否包含配置文件：
  ```bash
  jar tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep config/
  ```

### 2. 连接 Kafka 失败

**错误**：`Connection refused` 或 `Timeout`

**解决**：
- 检查 Kafka 是否运行：`docker ps | grep kafka`
- 检查网络连接：`docker exec streampark ping -c 3 kafka`
- 修改配置文件中的地址为容器名称或 IP

### 3. 连接 Doris 失败

**错误**：`Connection refused` 或 `Unknown database`

**解决**：
- 检查 Doris 是否运行：`docker ps | grep doris`
- 检查数据库是否存在：
  ```bash
  mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW DATABASES;"
  ```
- 创建数据库：
  ```bash
  mysql -h 127.0.0.1 -P 9030 -u root < sql/create_tables.sql
  ```

## 验证作业

### 1. 查看 Flink Web UI

http://localhost:8081

- 检查作业状态
- 查看 Metrics
- 查看日志

### 2. 查看 StreamPark 日志

```bash
docker logs -f streampark
```

### 3. 查看数据写入

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT 
    COUNT(*) as total,
    MAX(event_time) as latest_time
FROM crypto_dw.ods_crypto_ticker_rt;
"
```

## 本地测试（推荐先测试）

在提交到 StreamPark 之前，先在本地测试：

```bash
# 1. 启动所有服务
docker-compose -f docker-compose-doris.yml up -d
docker-compose -f docker-compose-flink.yml up -d

# 2. 启动数据采集
bash run-collector.sh

# 3. 运行 Flink 作业
bash run-flink-ods-datastream.sh

# 4. 检查数据
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT COUNT(*) FROM crypto_dw.ods_crypto_ticker_rt;"
```

## 相关文档

- [202603212005-StreamPark作业提交-配置主类和参数.md](./202603212005-StreamPark作业提交-配置主类和参数.md)
- [StreamPark使用指南.md](./StreamPark使用指南.md)
- [202603211800-StreamPark连接Flink-网络配置和集群添加指南.md](./202603211800-StreamPark连接Flink-网络配置和集群添加指南.md)
