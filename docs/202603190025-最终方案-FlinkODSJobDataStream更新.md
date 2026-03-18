# FlinkODSJobDataStream 更新 - 使用官方 Connector

## 更新说明

将 `FlinkODSJobDataStream` 从自定义 HTTP Sink 更新为使用官方 Doris Connector,确保与 Doris 3.1.x 完全兼容。

## 主要改动

### 1. 导入包更新

**更新前**:
```java
import com.crypto.dw.flink.sink.DorisStreamLoadSink;  // 自定义 Sink
```

**更新后**:
```java
import org.apache.doris.flink.cfg.DorisExecutionOptions;
import org.apache.doris.flink.cfg.DorisOptions;
import org.apache.doris.flink.cfg.DorisReadOptions;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.doris.flink.sink.writer.serializer.SimpleStringSerializer;
import java.util.Properties;
```

### 2. Sink 创建方法更新

**更新前** (自定义 HTTP Sink):
```java
private static DorisStreamLoadSink createDorisStreamLoadSink(ConfigLoader config) {
    String feHttpUrl = config.getString("doris.fe.http-url");
    String database = config.getString("doris.database");
    String table = config.getString("doris.tables.ods");
    String username = config.getString("doris.fe.username");
    String password = config.getString("doris.fe.password", "");
    int batchSize = config.getInt("doris.stream-load.batch-size", 1000);
    long batchIntervalMs = config.getLong("doris.stream-load.batch-interval-ms", 5000);
    
    return new DorisStreamLoadSink(
        feHttpUrl, database, table, 
        username, password, 
        batchSize, batchIntervalMs
    );
}
```

**更新后** (官方 Connector):
```java
private static DorisSink<String> createDorisSink(ConfigLoader config) {
    // 提取 FE 地址 (去掉 http:// 前缀)
    String feHttpUrl = config.getString("doris.fe.http-url");
    String feNodes = feHttpUrl.replace("http://", "").replace("https://", "");
    
    // Doris 连接配置
    DorisOptions.Builder dorisBuilder = DorisOptions.builder()
        .setFenodes(feNodes)  // FE 地址,格式: host:port
        .setTableIdentifier(
            config.getString("doris.database") + "." + 
            config.getString("doris.tables.ods")
        )
        .setUsername(config.getString("doris.fe.username"))
        .setPassword(config.getString("doris.fe.password", ""));
    
    // Stream Load 执行配置
    Properties streamLoadProp = new Properties();
    streamLoadProp.setProperty("format", "json");
    streamLoadProp.setProperty("read_json_by_line", "true");
    streamLoadProp.setProperty("strip_outer_array", "false");
    
    DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
        .setStreamLoadProp(streamLoadProp)
        .setMaxRetries(config.getInt("doris.stream-load.max-retries", 3))
        .setBufferSize(config.getInt("doris.stream-load.batch-size", 1000) * 1024)
        .setBufferCount(3)
        .setLabelPrefix("flink-ods")
        .build();
    
    // 构建 DorisSink
    return DorisSink.<String>builder()
        .setDorisReadOptions(DorisReadOptions.builder().build())
        .setDorisExecutionOptions(executionOptions)
        .setDorisOptions(dorisBuilder.build())
        .setSerializer(new SimpleStringSerializer())
        .build();
}
```

### 3. Sink 使用方式更新

**更新前**:
```java
DorisStreamLoadSink dorisSink = createDorisStreamLoadSink(config);
odsStream.addSink(dorisSink).name("Doris ODS Sink");
```

**更新后**:
```java
DorisSink<String> dorisSink = createDorisSink(config);
odsStream.sinkTo(dorisSink).name("Doris ODS Sink");
```

### 4. 配置参数调整

**更新前**:
```yaml
flink:
  execution:
    parallelism: 4
  checkpoint:
    interval: 60000
```

**更新后**:
```yaml
flink:
  execution:
    parallelism: 2  # 降低并行度,减少资源占用
  checkpoint:
    interval: 30000  # 减少 checkpoint 间隔,提高一致性
```

## 优势对比

| 特性 | 自定义 HTTP Sink | 官方 Connector |
|-----|-----------------|---------------|
| 实现复杂度 | 高 | 低 |
| 版本兼容性 | 差 | 好 ✅ |
| 错误处理 | 需要手动实现 | 内置完善 ✅ |
| 重试机制 | 手动实现 | 自动重试 ✅ |
| HTTP 协议处理 | 需要手动处理 | 自动处理 ✅ |
| 连接管理 | 手动管理 | 连接池管理 ✅ |
| 性能优化 | 需要手动优化 | 自动优化 ✅ |
| 维护成本 | 高 | 低 ✅ |
| 社区支持 | 无 | 有 ✅ |
| 生产就绪 | ⚠️ | ✅ |

## 运行方式

### 编译

```bash
mvn clean compile -DskipTests
```

### 运行

```bash
# 方式 1: 使用更新后的脚本 (推荐)
./run-flink-ods-datastream.sh

# 方式 2: 使用 FlinkODSJobDataStream2 (功能相同)
./run-flink-ods-datastream2.sh
```

两个脚本现在功能完全相同,都使用官方 Connector。

## 验证数据写入

### 1. 查看数据量

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT COUNT(*) as total_records 
FROM crypto_dw.ods_crypto_ticker_rt;
"
```

### 2. 查看最新数据

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
SELECT * 
FROM crypto_dw.ods_crypto_ticker_rt 
ORDER BY ingest_time DESC 
LIMIT 10;
"
```

### 3. 查看 Stream Load 历史

```bash
mysql -h 127.0.0.1 -P 9030 -u root -e "
SHOW STREAM LOAD FROM crypto_dw 
WHERE Label LIKE 'flink-ods%' 
ORDER BY CreateTime DESC 
LIMIT 10\G
"
```

## 常见问题

### Q1: 编译错误

**错误**: `cannot find symbol: class DorisSink`

**解决**: 确保 pom.xml 中有 Doris Connector 依赖:
```xml
<dependency>
    <groupId>org.apache.doris</groupId>
    <artifactId>flink-doris-connector-1.17</artifactId>
    <version>1.6.2</version>
</dependency>
```

### Q2: 运行时错误

**错误**: `NoClassDefFoundError: org/apache/flink/api/connector/source/Source`

**解决**: 使用 Maven exec 插件运行,自动处理依赖:
```bash
./run-flink-ods-datastream.sh
```

### Q3: 数据没有写入

**检查步骤**:
1. 确认 Kafka 有数据: `kafka-console-consumer.sh --bootstrap-server localhost:9092 --topic crypto_ticker`
2. 确认 Doris 可连接: `mysql -h 127.0.0.1 -P 9030 -u root`
3. 查看 Flink 日志: `tail -f logs/flink-app.log`

## 性能调优

### 1. 批量大小

```yaml
doris:
  stream-load:
    batch-size: 2000  # 增加批量大小,提高吞吐量
```

### 2. 缓冲区配置

```java
DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
    .setBufferSize(2000 * 1024)  // 增加缓冲区
    .setBufferCount(5)  // 增加缓冲区数量
    .build();
```

### 3. 并行度

```yaml
flink:
  execution:
    parallelism: 4  # 根据 CPU 核心数调整
```

## 总结

通过更新到官方 Connector:

1. ✅ 解决了 Doris 4.0.x 兼容性问题
2. ✅ 简化了代码实现
3. ✅ 提高了稳定性和可靠性
4. ✅ 降低了维护成本
5. ✅ 获得了社区支持

**现在 `FlinkODSJobDataStream` 和 `FlinkODSJobDataStream2` 功能完全相同,都使用官方 Connector,生产可用!**

## 相关文档

- [Doris 版本升级到 3.1.x](./202603190020-Doris版本-升级到3.1.x解决兼容性问题.md)
- [使用官方 Connector 解决 Stream Load Bug](./202603182345-Doris写入-使用官方Connector解决Stream-Load-Bug.md)
- [最终方案 - 生产环境部署指南](./最终方案-生产环境部署指南.md)
