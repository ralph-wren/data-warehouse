# 实时加密货币数据仓库

基于 Kafka + Flink + Doris 的实时数据仓库系统，用于采集、处理和分析加密货币实时行情数据。

## 技术栈

- **数据采集**: Java WebSocket Client (OKX API)
- **消息队列**: Apache Kafka 7.6.0
- **流处理**: Apache Flink 1.18.0
- **数据仓库**: Apache Doris 2.0.4
- **开发语言**: Java 11

## 快速开始

### 1. 环境准备

```bash
# 复制环境变量配置
cp .env.example .env

# 设置环境变量
source setup-env.sh
```

### 2. 启动服务

```bash
# 启动所有基础服务（Kafka, Doris 表等）
bash start-all.sh
```

### 3. 运行数据流

```bash
# 终端1: 启动数据采集（WebSocket -> Kafka）
bash run-collector.sh

# 终端2: 启动 Flink ODS 作业（Kafka -> Doris ODS）
bash run-flink-ods-sql.sh

# 终端3: 启动 Flink DWD 作业（数据清洗）
bash run-flink-dwd-sql.sh

# 终端4: 启动 Flink DWS 作业（1分钟 K 线聚合）
bash run-flink-dws-1min-sql.sh
```

### 4. 查询数据

```bash
# 查询各层数据
bash query-doris.sh
```

## 系统架构

```
OKX WebSocket → Kafka → Flink → Doris
                          ├── ODS 层（原始数据）
                          ├── DWD 层（明细数据）
                          └── DWS 层（汇总数据）
```

## 数据仓库分层

- **ODS 层**: 原始行情数据
- **DWD 层**: 清洗后的明细数据，包含衍生指标
- **DWS 层**: 1分钟 K 线聚合数据

## 主要功能

✅ 实时采集 OKX 加密货币行情数据  
✅ Kafka 消息队列缓冲  
✅ Flink 流式处理（DataStream 和 SQL 两种方式）  
✅ 数据清洗和字段补充  
✅ 窗口聚合生成 K 线数据  
✅ Doris 数据仓库存储  
✅ 配置系统（支持环境变量）  

## 文档

详细文档请查看：
- [完整设置文档](SETUP_COMPLETE.md)
- [配置说明](docs/CONFIGURATION.md)
- [设计文档](.kiro/specs/realtime-crypto-datawarehouse/design.md)

## 测试脚本

```bash
# 测试 Kafka 消费
bash test-kafka-consumer.sh

# 测试 Doris 连接
bash test-doris-connection.sh

# 测试配置加载
bash test-config.sh
```

## 项目结构

```
.
├── config/              # 配置文件
├── sql/                 # SQL 脚本
├── src/main/java/       # Java 源代码
│   ├── collector/       # 数据采集
│   ├── flink/           # Flink 作业
│   ├── kafka/           # Kafka 管理
│   └── model/           # 数据模型
├── *.sh                 # 运行脚本
└── README.md            # 本文档
```

## 监控

```bash
# 查看 Kafka Topic
docker exec -it kafka kafka-topics --bootstrap-server localhost:9092 --list

# 查看 Kafka 消息
bash test-kafka-consumer.sh

# 查看 Doris 数据
bash query-doris.sh
```

## License

MIT License
