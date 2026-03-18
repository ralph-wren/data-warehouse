# 实时加密货币数据仓库 - 项目完成

## 项目概述

本项目是一个基于 Kafka + Flink + Doris 的实时数据仓库系统，用于采集、处理和分析加密货币实时行情数据。

## 技术栈

- **数据采集**: Java WebSocket Client (OKX API)
- **消息队列**: Apache Kafka 7.6.0 (Confluent, KRaft 模式)
- **流处理**: Apache Flink 1.18.0
- **数据仓库**: Apache Doris 2.0.4
- **开发语言**: Java 11
- **构建工具**: Maven 3.6+

## 系统架构

```
OKX WebSocket API
       ↓
Data Collector (Java)
       ↓
Apache Kafka (crypto_ticker topic)
       ↓
   ┌───┴───┬───────┬────────┐
   ↓       ↓       ↓        ↓
Flink   Flink   Flink    Flink
ODS     DWD     DWS      ADS
Job     Job     Job      Job
   ↓       ↓       ↓        ↓
   └───┬───┴───────┴────────┘
       ↓
Apache Doris (crypto_dw database)
   ├── ODS 层: ods_crypto_ticker_rt
   ├── DWD 层: dwd_crypto_ticker_detail
   └── DWS 层: dws_crypto_ticker_1min
```

## 数据仓库分层

### ODS 层（原始数据层）
- **表名**: `ods_crypto_ticker_rt`
- **功能**: 存储从 Kafka 采集的原始行情数据
- **字段**: inst_id, timestamp, last_price, bid_price, ask_price, volume_24h, high_24h, low_24h, open_24h

### DWD 层（明细数据层）
- **表名**: `dwd_crypto_ticker_detail`
- **功能**: 数据清洗和字段补充
- **新增字段**: 
  - spread (买卖价差)
  - spread_rate (价差率)
  - price_change_24h (24小时涨跌额)
  - price_change_rate_24h (24小时涨跌幅)
  - amplitude_24h (24小时振幅)
  - trade_date, trade_hour (时间维度)

### DWS 层（汇总数据层）
- **表名**: `dws_crypto_ticker_1min`
- **功能**: 1分钟窗口聚合，生成 K 线数据
- **字段**: open_price, high_price, low_price, close_price, volume, avg_price, price_change, price_change_rate, tick_count

## 已实现的功能

### 1. 数据采集程序
- ✅ WebSocket 连接 OKX 公开 API
- ✅ 订阅 BTC-USDT 实时行情
- ✅ 自动重连机制（指数退避）
- ✅ 数据写入 Kafka

**运行命令**: `bash run-collector.sh`

### 2. Flink ODS 作业
提供两种实现方式：

#### DataStream API 方式
- ✅ 从 Kafka 消费数据
- ✅ JSON 解析和转换
- ✅ 写入 Doris ODS 层

**运行命令**: `bash run-flink-ods-datastream.sh`

#### Flink SQL 方式
- ✅ 使用 Flink SQL 定义数据流
- ✅ 声明式数据处理
- ✅ 写入 Doris ODS 层

**运行命令**: `bash run-flink-ods-sql.sh`

### 3. Flink DWD 作业
- ✅ 从 Kafka 读取数据
- ✅ 数据清洗（过滤无效数据）
- ✅ 字段补充（计算衍生指标）
- ✅ 写入 Doris DWD 层

**运行命令**: `bash run-flink-dwd-sql.sh`

### 4. Flink DWS 作业
- ✅ 1分钟滚动窗口聚合
- ✅ 生成 K 线数据（OHLC）
- ✅ 计算窗口内统计指标
- ✅ 写入 Doris DWS 层

**运行命令**: `bash run-flink-dws-1min-sql.sh`

## 配置系统

### 配置文件
- `config/application.yml` - 基础配置
- `config/application-dev.yml` - 开发环境配置
- `config/application-prod.yml` - 生产环境配置

### 环境变量
创建 `.env` 文件（参考 `.env.example`）：

```bash
# 应用环境
APP_ENV=dev

# OKX API 配置（可选，公开 WebSocket 不需要）
OKX_API_KEY=your_api_key
OKX_SECRET_KEY=your_secret_key
OKX_PASSPHRASE=your_passphrase
```

### 配置加载
- 支持 `${ENV_VAR_NAME}` 语法从环境变量读取
- 配置优先级: application.yml → application-{env}.yml → 环境变量

## 快速启动

### 1. 环境准备
```bash
# 复制环境变量配置
cp .env.example .env

# 编辑 .env 文件（如需要）
# vim .env

# 设置环境变量
source setup-env.sh
```

### 2. 启动所有服务
```bash
bash start-all.sh
```

这个脚本会：
- 启动 Kafka（如果未运行）
- 创建 Kafka Topic
- 检查 Doris 连接
- 创建 Doris 表
- 编译项目

### 3. 启动数据采集
```bash
bash run-collector.sh
```

### 4. 启动 Flink 作业

选择一种方式启动 ODS 作业：
```bash
# 方式1: Flink SQL
bash run-flink-ods-sql.sh

# 方式2: DataStream API
bash run-flink-ods-datastream.sh
```

启动 DWD 作业：
```bash
bash run-flink-dwd-sql.sh
```

启动 DWS 作业：
```bash
bash run-flink-dws-1min-sql.sh
```

### 5. 查询数据
```bash
bash query-doris.sh
```

## 测试和验证

### 1. 测试 Kafka 消费
```bash
bash test-kafka-consumer.sh
```

### 2. 测试 Doris 连接
```bash
bash test-doris-connection.sh
```

### 3. 测试配置加载
```bash
bash test-config.sh
```

### 4. 查询各层数据

#### ODS 层
```sql
USE crypto_dw;
SELECT * FROM ods_crypto_ticker_rt ORDER BY timestamp DESC LIMIT 10;
```

#### DWD 层
```sql
SELECT * FROM dwd_crypto_ticker_detail ORDER BY timestamp DESC LIMIT 10;
```

#### DWS 层
```sql
SELECT * FROM dws_crypto_ticker_1min ORDER BY window_start DESC LIMIT 10;
```

## 项目结构

```
.
├── config/                          # 配置文件
│   ├── application.yml
│   ├── application-dev.yml
│   └── application-prod.yml
├── docs/                            # 文档
│   └── CONFIGURATION.md
├── sql/                             # SQL 脚本
│   └── create_tables.sql
├── src/main/java/com/crypto/dw/
│   ├── collector/                   # 数据采集
│   │   ├── DataCollectorMain.java
│   │   └── OKXWebSocketClient.java
│   ├── config/                      # 配置管理
│   │   └── ConfigLoader.java
│   ├── flink/                       # Flink 作业
│   │   ├── FlinkODSJobDataStream.java
│   │   ├── FlinkODSJobSQL.java
│   │   ├── FlinkDWDJobSQL.java
│   │   └── FlinkDWSJob1MinSQL.java
│   ├── kafka/                       # Kafka 管理
│   │   └── KafkaProducerManager.java
│   └── model/                       # 数据模型
│       └── TickerData.java
├── docker-compose.yml               # Kafka Docker 配置
├── pom.xml                          # Maven 配置
├── .env.example                     # 环境变量示例
├── start-all.sh                     # 启动所有服务
├── run-collector.sh                 # 运行数据采集
├── run-flink-ods-datastream.sh      # 运行 ODS 作业（DataStream）
├── run-flink-ods-sql.sh             # 运行 ODS 作业（SQL）
├── run-flink-dwd-sql.sh             # 运行 DWD 作业
├── run-flink-dws-1min-sql.sh        # 运行 DWS 作业
├── query-doris.sh                   # 查询 Doris 数据
└── SETUP_COMPLETE.md                # 本文档
```

## 监控和运维

### 查看 Kafka Topic
```bash
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### 查看 Kafka 消息
```bash
docker exec -it kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic crypto_ticker \
  --from-beginning \
  --max-messages 10
```

### 查看 Doris 表结构
```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "USE crypto_dw; SHOW TABLES;"
mysql -h 127.0.0.1 -P 9030 -u root -e "USE crypto_dw; DESC ods_crypto_ticker_rt;"
```

### 查看 Flink 作业日志
日志输出到控制台，可以重定向到文件：
```bash
bash run-flink-ods-sql.sh > logs/flink-ods.log 2>&1 &
```

## 性能优化建议

### Kafka
- 调整 `batch.size` 和 `linger.ms` 平衡吞吐量和延迟
- 使用 `lz4` 压缩减少网络传输

### Flink
- 根据数据量调整并行度 (`flink.execution.parallelism`)
- 启用 Checkpoint 保证数据一致性
- 使用 RocksDB 状态后端处理大状态

### Doris
- 使用 Bloom Filter 加速点查询
- 合理设置分桶数量 (BUCKETS)
- 使用动态分区管理历史数据
- 定期清理过期数据

## 扩展功能（待实现）

- [ ] 5分钟、15分钟、1小时 K 线聚合
- [ ] ADS 层市场概览指标
- [ ] 多币种支持（ETH, SOL, BNB 等）
- [ ] 实时告警（价格异常、成交量异常）
- [ ] 数据质量监控
- [ ] Grafana 可视化大屏
- [ ] 历史数据回填
- [ ] 数据备份和恢复

## 故障排查

### 问题1: Kafka 连接失败
```bash
# 检查 Kafka 容器状态
docker ps | grep kafka

# 查看 Kafka 日志
docker logs kafka

# 重启 Kafka
docker-compose restart
```

### 问题2: Doris 连接失败
```bash
# 检查 Doris 端口
netstat -an | grep 9030
netstat -an | grep 8030

# 测试连接
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1"
```

### 问题3: Flink 作业失败
- 检查配置文件路径和内容
- 检查 Kafka Topic 是否存在
- 检查 Doris 表是否创建
- 查看详细错误日志

### 问题4: 数据未写入
- 检查 WebSocket 连接是否正常
- 检查 Kafka 是否有数据（使用 `test-kafka-consumer.sh`）
- 检查 Flink 作业是否运行
- 查询 Doris 表确认数据

## 联系和支持

如有问题，请检查：
1. 所有服务是否正常运行
2. 配置文件是否正确
3. 网络连接是否正常
4. 日志中的错误信息

## 更新日志

### v1.0.0 (2024-03-18)
- ✅ 完成数据采集程序（WebSocket -> Kafka）
- ✅ 完成 Flink ODS 作业（DataStream 和 SQL 两种方式）
- ✅ 完成 Flink DWD 作业（数据清洗和字段补充）
- ✅ 完成 Flink DWS 作业（1分钟窗口聚合）
- ✅ 完成配置系统（支持环境变量）
- ✅ 完成 Doris 建表脚本
- ✅ 完成运行脚本和测试脚本
- ✅ 完成项目文档

---

**项目状态**: ✅ 核心功能已完成，可以正常运行

**下一步**: 根据实际需求扩展功能和优化性能
