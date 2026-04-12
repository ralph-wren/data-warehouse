# StreamPark 使用指南

## 快速开始

### 1. 访问 StreamPark

打开浏览器访问: http://localhost:10000

- 默认账号: `admin`
- 默认密码: `streampark`

### 2. 首次登录配置

#### 2.1 修改密码（推荐）

登录后建议修改默认密码：
1. 点击右上角用户头像
2. 选择"修改密码"
3. 输入新密码并保存

#### 2.2 配置 Flink Home

StreamPark 需要知道 Flink 的安装位置：

1. 进入"设置中心" -> "Flink Home"
2. 添加 Flink 版本：
   - Flink Version: 1.17.2（根据你的项目版本）
   - Flink Home: `/opt/flink`（容器内路径）

## 管理 Flink 作业

### 1. 添加现有作业

#### 方式一：通过 JAR 包添加

1. 进入"应用管理" -> "添加应用"
2. 选择"Custom Code"
3. 填写基本信息：
   - 应用名称: 如 `FlinkODSJob`
   - 开发模式: `Apache Flink`
   - 执行模式: `local`（本地测试）或 `remote`（远程集群）
4. 上传 JAR 包或指定 JAR 路径
5. 配置主类: 如 `com.crypto.dw.jobs.FlinkODSJobDataStream`
6. 配置程序参数和 Flink 配置

#### 方式二：通过 Flink SQL 添加

1. 进入"应用管理" -> "添加应用"
2. 选择"Flink SQL"
3. 填写基本信息
4. 编写 Flink SQL 语句
5. 配置依赖的 Connector（如 Kafka、Doris）

### 2. 配置作业参数

#### 基本配置

```yaml
# Checkpoint 配置
execution.checkpointing.interval: 60000
execution.checkpointing.mode: EXACTLY_ONCE
state.backend: rocksdb

# 并行度配置
parallelism.default: 2

# 重启策略
restart-strategy: fixed-delay
restart-strategy.fixed-delay.attempts: 3
restart-strategy.fixed-delay.delay: 10s
```

#### Kafka 连接配置

```properties
# Kafka 配置
bootstrap.servers=kafka:9092
group.id=flink-consumer-group
auto.offset.reset=latest
```

#### Doris 连接配置

```properties
# Doris 配置
fenodes=doris-fe:8030
username=root
password=
database=crypto_dw
table=ods_trades
```

### 3. 启动作业

1. 在应用列表中找到作业
2. 点击"启动"按钮
3. 选择启动模式：
   - 从最新位置启动
   - 从 Savepoint 恢复
4. 确认启动

### 4. 监控作业

#### 实时监控

- 查看作业运行状态
- 查看 TaskManager 和 JobManager 状态
- 查看作业拓扑图
- 查看实时指标（吞吐量、延迟等）

#### 日志查看

1. 点击作业名称进入详情页
2. 选择"日志"标签
3. 查看 JobManager 和 TaskManager 日志

### 5. Savepoint 管理

#### 手动触发 Savepoint

1. 在作业详情页点击"Savepoint"
2. 选择 Savepoint 路径
3. 点击"触发"

#### 从 Savepoint 恢复

1. 停止作业
2. 点击"启动"
3. 选择"从 Savepoint 恢复"
4. 选择 Savepoint 路径
5. 确认启动

## 项目集成示例

### 示例 1: 添加 ODS 层作业

```yaml
# 应用名称
name: FlinkODSJob

# 主类
main-class: com.crypto.dw.jobs.FlinkODSJobDataStream

# JAR 路径
jar: /path/to/data-warehouse-1.0-SNAPSHOT.jar

# 程序参数
program-args: --config /path/to/application-dev.yml

# Flink 配置
flink-conf:
  parallelism.default: 2
  execution.checkpointing.interval: 60000
  state.backend: rocksdb
```

### 示例 2: 添加 DWD 层 SQL 作业

```sql
-- 创建 Kafka 源表
CREATE TABLE kafka_source (
    `symbol` STRING,
    `price` DECIMAL(18, 8),
    `size` DECIMAL(18, 8),
    `side` STRING,
    `timestamp` BIGINT,
    `trade_id` STRING,
    `event_time` TIMESTAMP(3) METADATA FROM 'timestamp',
    WATERMARK FOR event_time AS event_time - INTERVAL '5' SECOND
) WITH (
    'connector' = 'kafka',
    'topic' = 'crypto-trades',
    'properties.bootstrap.servers' = 'kafka:9092',
    'properties.group.id' = 'flink-dwd-group',
    'scan.startup.mode' = 'latest-offset',
    'format' = 'json'
);

-- 创建 Doris 结果表
CREATE TABLE doris_sink (
    `symbol` STRING,
    `price` DECIMAL(18, 8),
    `size` DECIMAL(18, 8),
    `side` STRING,
    `timestamp` BIGINT,
    `trade_id` STRING,
    `date` STRING,
    `hour` INT
) WITH (
    'connector' = 'doris',
    'fenodes' = 'doris-fe:8030',
    'table.identifier' = 'crypto_dw.dwd_trades',
    'username' = 'root',
    'password' = '',
    'sink.properties.format' = 'json',
    'sink.properties.read_json_by_line' = 'true'
);

-- 插入数据
INSERT INTO doris_sink
SELECT 
    symbol,
    price,
    size,
    side,
    timestamp,
    trade_id,
    DATE_FORMAT(event_time, 'yyyy-MM-dd') as date,
    HOUR(event_time) as hour
FROM kafka_source;
```

## 告警配置

### 1. 配置告警规则

1. 进入"告警中心" -> "告警配置"
2. 添加告警规则：
   - 作业失败告警
   - Checkpoint 失败告警
   - 延迟过高告警

### 2. 配置通知方式

支持多种通知方式：
- 邮件通知
- 钉钉通知
- 企业微信通知
- Webhook

## 常见问题

### 1. 作业启动失败

检查项：
- Flink Home 配置是否正确
- JAR 包路径是否正确
- 主类名称是否正确
- 依赖的服务（Kafka、Doris）是否可访问

### 2. 无法连接 Kafka

确保 StreamPark 容器可以访问 Kafka：
```bash
docker exec -it streampark ping kafka
```

### 3. 无法连接 Doris

确保 StreamPark 容器可以访问 Doris：
```bash
docker exec -it streampark ping doris-fe
```

## 最佳实践

### 1. 作业命名规范

- ODS 层: `FlinkODS_{数据源}`
- DWD 层: `FlinkDWD_{业务域}`
- DWS 层: `FlinkDWS_{聚合粒度}_{业务域}`

### 2. 配置管理

- 使用配置文件管理作业参数
- 不同环境使用不同配置（dev、test、prod）
- 敏感信息使用环境变量

### 3. 监控告警

- 为所有生产作业配置告警
- 定期检查作业运行状态
- 关注 Checkpoint 成功率

### 4. Savepoint 策略

- 定期触发 Savepoint
- 升级作业前先触发 Savepoint
- 保留最近 3-5 个 Savepoint

## 参考资料

- [StreamPark 官方文档](https://streampark.apache.org/zh-CN/docs/intro)
- [Flink 官方文档](https://flink.apache.org/)
- [项目部署文档](./202603211652-StreamPark部署-Docker方式部署Flink作业管理平台.md)
