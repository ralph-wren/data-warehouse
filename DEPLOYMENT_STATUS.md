# 部署状态

**更新时间**: 2026-03-19 01:00 (东八区)  
**状态**: ✅ 部署成功

---

## 当前部署

### Doris 数据仓库

- **版本**: 3.0.7-rc01-1204
- **部署方式**: Docker 桥接网络 + 端口映射
- **FE 地址**: 127.0.0.1:9030 (MySQL), 127.0.0.1:8030 (HTTP)
- **BE 地址**: 172.25.0.3:9050 (内部), 127.0.0.1:8040 (HTTP 映射)
- **状态**: ✅ 运行中

### 数据库

- **数据库名**: crypto_dw
- **表**:
  - ods_crypto_ticker_rt (ODS 层 - 原始数据)
  - dwd_crypto_ticker_detail (DWD 层 - 明细数据)
  - dws_crypto_ticker_1min (DWS 层 - 1分钟聚合)

### Kafka

- **版本**: 3.6.1
- **地址**: localhost:9092
- **Topic**: crypto_ticker

---

## 快速开始

### 1. 启动所有服务

```bash
# 启动 Kafka
docker-compose up -d

# 启动 Doris
./start-doris-for-flink.sh
```

### 2. 验证部署

```bash
./test/verify-doris-flink-connection.sh
```

### 3. 运行数据采集

```bash
./run-collector.sh
```

### 4. 运行 Flink 作业

```bash
./run-flink-ods-datastream.sh
```

### 5. 查看数据

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
USE crypto_dw;
SELECT COUNT(*) FROM ods_crypto_ticker_rt;
SELECT * FROM ods_crypto_ticker_rt ORDER BY ingest_time DESC LIMIT 10;
"
```

---

## 常用命令

### 查看服务状态

```bash
# 查看所有容器
docker ps

# 查看 Doris 状态
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G"

# 查看 Kafka Topic
docker exec kafka kafka-topics --bootstrap-server localhost:9092 --list
```

### 查看日志

```bash
# Doris FE 日志
docker logs doris-fe --tail 50

# Doris BE 日志
docker logs doris-be --tail 50

# Kafka 日志
docker logs kafka --tail 50

# Flink 日志
tail -f logs/flink-app.log
```

### 重启服务

```bash
# 重启 Doris
docker-compose -f docker-compose-doris.yml restart

# 重启 Kafka
docker-compose restart kafka
```

### 停止服务

```bash
# 停止 Doris
docker-compose -f docker-compose-doris.yml stop

# 停止 Kafka
docker-compose stop
```

---

## 故障排查

### Doris BE 离线

```bash
# 查看 BE 日志
docker logs doris-be --tail 100

# 重启 BE
docker restart doris-be

# 等待 1 分钟
sleep 60
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G"
```

### Flink 连接失败

```bash
# 检查 BE HTTP 端口
curl http://127.0.0.1:8040/api/health

# 检查配置
cat config/application.yml | grep -A 5 "doris:"

# 查看 Flink 日志
tail -f logs/flink-app.log
```

### 数据未写入

```bash
# 检查 Kafka 消息
docker exec kafka kafka-console-consumer \
  --bootstrap-server localhost:9092 \
  --topic crypto_ticker \
  --from-beginning \
  --max-messages 10

# 检查 Flink 作业状态
flink list

# 查看 Doris 数据
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT COUNT(*) FROM crypto_dw.ods_crypto_ticker_rt"
```

---

## 重要文档

### 部署文档

- [最终成功方案](./docs/202603190100-最终成功-Doris部署和Flink连接完整方案.md) ⭐⭐⭐
- [桥接网络模式方案](./docs/202603190050-Doris部署-桥接网络模式最终方案.md)
- [BE 地址问题解决](./docs/202603190040-Docker网络-Flink连接Doris的BE地址问题完整解决方案.md)

### 问题排查

- [问题解决汇总](./问题解决汇总.md)
- [快速开始指南](./docs/快速开始-解决BE地址问题.md)
- [版本配置参考](./docs/版本配置快速参考.md)

---

## 技术栈

| 组件 | 版本 | 状态 |
|-----|------|------|
| Doris | 3.0.7-rc01-1204 | ✅ 运行中 |
| Flink | 1.17.2 | ✅ 已配置 |
| Kafka | 3.6.1 | ✅ 运行中 |
| Flink Doris Connector | 1.6.2 | ✅ 已配置 |
| Flink Kafka Connector | 3.0.2-1.17 | ✅ 已配置 |
| JDK | 11 | ✅ 已安装 |

---

## 下一步

1. ✅ Doris 部署成功
2. ✅ 数据库和表创建成功
3. ⏳ 运行 Flink 作业验证数据写入
4. ⏳ 测试 DWD 和 DWS 层数据处理
5. ⏳ 性能优化和监控配置

---

**最后更新**: 2026-03-19 01:00 (东八区)
