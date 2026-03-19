# 快速开始 - 解决 BE 地址问题

**更新时间**: 2026-03-19 00:40 (东八区)  
**适用场景**: Flink 连接 Doris 时出现 `172.19.0.x` 地址错误

---

## 问题症状

如果你看到以下错误:
```
org.apache.http.NoHttpResponseException: 172.19.0.3:8040 failed to respond
```

说明 Doris BE 注册了 Docker 内部 IP,Flink 无法访问。

---

## 快速解决 (3 步)

### 步骤 1: 重新部署 Doris (推荐)

使用 host 网络模式重新部署:

```bash
# 停止并重新启动 Doris
./start-doris-for-flink.sh
```

等待约 1 分钟,让 Doris 完全启动。

---

### 步骤 2: 验证连接

运行验证脚本:

```bash
./test/verify-doris-flink-connection.sh
```

**期望输出**:
```
✓ Doris FE 容器运行中
✓ Doris BE 容器运行中
✓ BE 地址正确 (127.0.0.1)
✓ BE 节点在线
✓ 所有测试通过!
```

如果看到 `BE 地址错误`,继续步骤 3。

---

### 步骤 3: 修复 BE 地址 (如果需要)

如果步骤 2 显示 BE 地址仍然是 `172.x.x.x`:

```bash
# 运行修复脚本
./fix-doris-be-address.sh
```

脚本会自动:
1. 删除旧的 BE 节点 (Docker 内部 IP)
2. 添加新的 BE 节点 (127.0.0.1)
3. 验证修复结果

---

## 验证成功

运行以下命令确认:

```bash
# 1. 检查 BE 地址
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" | grep Host
# 应该显示: Host: 127.0.0.1

# 2. 检查 BE HTTP 端口
curl http://127.0.0.1:8040/api/health
# 应该返回: {"status": "OK"} 或类似响应

# 3. 检查 BE 状态
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" | grep Alive
# 应该显示: Alive: true
```

---

## 运行 Flink 作业

确认 BE 地址正确后,运行 Flink 作业:

```bash
# 使用官方 Connector
./run-flink-ods-datastream.sh
```

**期望结果**: 无 `NoHttpResponseException` 错误,数据成功写入 Doris。

---

## 查看数据

```bash
# 连接 Doris
mysql -h 127.0.0.1 -P 9030 -u root

# 查询数据
USE crypto_dw;
SELECT * FROM ods_crypto_ticker_rt ORDER BY ingest_time DESC LIMIT 10;
```

---

## 故障排查

### 问题 1: BE 节点离线 (Alive: false)

**原因**: BE 容器可能还在启动中

**解决**:
```bash
# 等待 1-2 分钟
sleep 60

# 检查 BE 日志
docker logs doris-be

# 重新检查状态
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G"
```

---

### 问题 2: BE HTTP 端口不可访问

**原因**: BE 服务未完全启动

**解决**:
```bash
# 检查 BE 容器状态
docker ps | grep doris-be

# 查看 BE 日志
docker logs doris-be | tail -50

# 重启 BE 容器
docker restart doris-be
```

---

### 问题 3: 修复脚本执行失败

**原因**: MySQL 客户端未安装或连接失败

**解决**:
```bash
# 安装 MySQL 客户端 (Ubuntu/Debian)
sudo apt-get install mysql-client

# 安装 MySQL 客户端 (CentOS/RHEL)
sudo yum install mysql

# 手动执行 SQL
mysql -h 127.0.0.1 -P 9030 -u root <<EOF
ALTER SYSTEM DROPP BACKEND '172.19.0.3:9050' FORCE;
ALTER SYSTEM ADD BACKEND '127.0.0.1:9050';
SHOW BACKENDS\G
EOF
```

---

### 问题 4: Flink 仍然报错

**原因**: 可能是其他配置问题

**解决**:
```bash
# 1. 检查配置文件
cat config/application.yml | grep -A 5 "doris:"

# 2. 重新编译项目
mvn clean compile -DskipTests

# 3. 查看 Flink 日志
tail -f logs/flink-app.log

# 4. 检查 Doris 表结构
mysql -h 127.0.0.1 -P 9030 -u root -e "DESC crypto_dw.ods_crypto_ticker_rt"
```

---

## 完整流程总结

```bash
# 1. 部署 Doris (host 网络模式)
./start-doris-for-flink.sh

# 2. 等待启动
sleep 60

# 3. 验证连接
./test/verify-doris-flink-connection.sh

# 4. 修复 BE 地址 (如果需要)
./fix-doris-be-address.sh

# 5. 创建数据库和表 (如果未创建)
mysql -h 127.0.0.1 -P 9030 -u root < sql/create_tables.sql

# 6. 启动 Kafka (如果未启动)
docker-compose up -d

# 7. 启动数据采集
./run-collector.sh

# 8. 运行 Flink 作业
./run-flink-ods-datastream.sh

# 9. 查看数据
mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT COUNT(*) FROM crypto_dw.ods_crypto_ticker_rt"
```

---

## 相关文档

- [完整解决方案](./202603190040-Docker网络-Flink连接Doris的BE地址问题完整解决方案.md)
- [问题解决汇总](../问题解决汇总.md)
- [版本配置参考](./版本配置快速参考.md)

---

## 技术支持

如果以上步骤仍无法解决问题,请:

1. 收集日志:
   ```bash
   docker logs doris-fe > doris-fe.log
   docker logs doris-be > doris-be.log
   tail -100 logs/flink-app.log > flink.log
   ```

2. 检查环境:
   ```bash
   docker --version
   java -version
   mvn --version
   ```

3. 查看详细文档或提交 Issue

---

**最后更新**: 2026-03-19 00:40 (东八区)
