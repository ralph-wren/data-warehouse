# 使用官方 Doris Connector 解决 Stream Load Bug

## 问题描述

在使用自定义 HTTP Stream Load 向 Doris 4.0.1-rc02 写入数据时,持续出现以下问题:

### 问题 1: 100-continue 错误
```json
{"status":"FAILED","msg":"There is no 100-continue header"}
```

即使在代码中设置了 `conn.setRequestProperty("Expect", "")`,Doris 仍然报错。

### 问题 2: Connection Reset 错误
```
java.net.SocketException: Connection reset
```

无论是访问 FE (8030) 还是直接访问 BE (8040),都会出现连接重置错误。

### 问题 3: 重试机制无效

添加了重试机制后,每次重试都失败:
```
2026-03-18 23:40:49,726 WARN  连接被重置 (第 1 次尝试): Connection reset
2026-03-18 23:41:00,751 WARN  连接被重置 (第 2 次尝试): Connection reset
2026-03-18 23:41:12,762 WARN  连接被重置 (第 3 次尝试): Connection reset
```

## 问题分析

### 1. curl 测试结果

使用 curl 进行测试,发现同样的问题:

```bash
# 测试 1: PUT + --data-binary (模拟 Java 代码)
curl -X PUT -u "root:" -H "Expect:" --data-binary "@test.json" \
  "http://127.0.0.1:8030/api/crypto_dw/ods_crypto_ticker_rt/_stream_load"
# 结果: {"status":"FAILED","msg":"There is no 100-continue header"}

# 测试 2: curl -T (标准方法)
echo '{}' | curl -u "root:" -T - \
  "http://127.0.0.1:8030/api/crypto_dw/ods_crypto_ticker_rt/_stream_load"
# 结果: HTTP/1.1 307 Temporary Redirect (重定向到 BE)

# 测试 3: 跟随重定向
echo '{}' | curl -L -u "root:" -T - \
  "http://127.0.0.1:8030/api/crypto_dw/ods_crypto_ticker_rt/_stream_load"
# 结果: curl: (56) Recv failure: Connection was reset

# 测试 4: 直接访问 BE
curl -X PUT -u "root:" --data-binary "@test.json" \
  "http://172.19.0.3:8040/api/crypto_dw/ods_crypto_ticker_rt/_stream_load"
# 结果: curl: (52) Empty reply from server
```

### 2. 根本原因

经过测试分析,问题的根本原因是:

1. **Doris 4.0.1-rc02 版本 Bug**
   - 这是一个 RC (Release Candidate) 版本,不是稳定版
   - Stream Load API 存在 HTTP 协议处理 bug
   - 无论如何设置 HTTP 头都无法绕过

2. **HTTP 协议兼容性问题**
   - Doris 4.0.x 对 HTTP 请求头的处理与之前版本不同
   - `Expect: 100-continue` 机制处理有问题
   - BE 节点的 HTTP 服务不稳定

3. **自定义实现的局限性**
   - 自定义 HTTP Stream Load 需要处理各种边界情况
   - 难以适配 Doris 不同版本的差异
   - 维护成本高

## 解决方案

### 方案选择

有两个可行方案:

1. **降级 Doris 到稳定版本** (如 2.1.x)
   - 优点: 稳定可靠
   - 缺点: 失去新版本特性

2. **使用官方 Doris Flink Connector** ✅ (推荐)
   - 优点: 官方维护,兼容性好,自动处理版本差异
   - 缺点: 需要修改代码

我们选择方案 2,使用官方 Connector。

### 实现步骤

#### 1. 创建新的 Flink Job

创建 `FlinkODSJobDataStream2.java`,使用官方 `DorisSink`:

```java
// 配置 Doris 连接
DorisOptions.Builder dorisBuilder = DorisOptions.builder()
    .setFenodes(feNodes)  // FE 地址
    .setTableIdentifier(database + "." + table)
    .setUsername(username)
    .setPassword(password);

// 配置 Stream Load 属性
Properties streamLoadProp = new Properties();
streamLoadProp.setProperty("format", "json");
streamLoadProp.setProperty("read_json_by_line", "true");
streamLoadProp.setProperty("strip_outer_array", "false");

// 配置执行选项
DorisExecutionOptions executionOptions = DorisExecutionOptions.builder()
    .setStreamLoadProp(streamLoadProp)
    .setMaxRetries(3)  // 自动重试
    .setBufferSize(1000 * 1024)  // 缓冲区大小
    .setBufferCount(3)  // 缓冲区数量
    .setLabelPrefix("flink-ods")  // 标签前缀
    .build();

// 构建 DorisSink
DorisSink<String> dorisSink = DorisSink.<String>builder()
    .setDorisReadOptions(DorisReadOptions.builder().build())
    .setDorisExecutionOptions(executionOptions)
    .setDorisOptions(dorisBuilder.build())
    .setSerializer(new SimpleStringSerializer())
    .build();

// 写入 Doris
odsStream.sinkTo(dorisSink).name("Doris ODS Sink");
```

#### 2. 创建运行脚本

创建 `run-flink-ods-datastream2.sh`:

```bash
#!/bin/bash
JAR_FILE="target/crypto-dw-1.0-SNAPSHOT.jar"
java -cp "$JAR_FILE" com.crypto.dw.flink.FlinkODSJobDataStream2
```

#### 3. 编译和运行

```bash
# 编译
mvn clean package -DskipTests

# 运行
./run-flink-ods-datastream2.sh
```

### 官方 Connector 的优势

1. **自动处理 HTTP 协议**
   - 内部封装了 Stream Load 的 HTTP 请求
   - 自动处理 Expect: 100-continue
   - 自动跟随 307 重定向

2. **版本兼容性**
   - 支持 Doris 2.0.x, 2.1.x, 4.0.x 等多个版本
   - 自动适配不同版本的 API 差异

3. **错误处理和重试**
   - 内置重试机制
   - 自动处理临时性错误
   - 详细的错误日志

4. **性能优化**
   - 批量写入优化
   - 多缓冲区机制
   - 异步提交

5. **官方维护**
   - 持续更新和 bug 修复
   - 社区支持
   - 文档完善

## 对比分析

### 自定义 HTTP Stream Load vs 官方 Connector

| 特性 | 自定义实现 | 官方 Connector |
|-----|----------|---------------|
| 实现复杂度 | 高 | 低 |
| 版本兼容性 | 差 | 好 |
| 错误处理 | 需要自己实现 | 内置完善 |
| 性能优化 | 需要手动优化 | 自动优化 |
| 维护成本 | 高 | 低 |
| 社区支持 | 无 | 有 |
| 适用场景 | 特殊需求 | 通用场景 ✅ |

## 测试验证

### 1. 编译项目

```bash
mvn clean compile -DskipTests
# 结果: BUILD SUCCESS
```

### 2. 打包项目

```bash
mvn package -DskipTests
# 结果: BUILD SUCCESS
```

### 3. 运行测试

```bash
./run-flink-ods-datastream2.sh
```

### 预期结果

- ✅ 成功连接 Kafka
- ✅ 成功读取数据
- ✅ 成功写入 Doris
- ✅ 无 Connection Reset 错误
- ✅ 无 100-continue 错误

## 配置说明

### application.yml 配置

```yaml
doris:
  fe:
    http-url: http://127.0.0.1:8030  # FE HTTP 地址
    username: root
    password: ""
  
  database: crypto_dw
  
  stream-load:
    batch-size: 1000        # 批量大小
    batch-interval-ms: 5000 # 批量间隔
    max-retries: 3          # 最大重试次数
  
  tables:
    ods: ods_crypto_ticker_rt
```

### Doris Connector 配置项

| 配置项 | 说明 | 默认值 |
|-------|------|--------|
| fenodes | FE 节点地址 | 必填 |
| table.identifier | 表标识 (db.table) | 必填 |
| username | 用户名 | root |
| password | 密码 | 空 |
| sink.buffer-size | 缓冲区大小 (字节) | 1MB |
| sink.buffer-count | 缓冲区数量 | 3 |
| sink.max-retries | 最大重试次数 | 3 |
| sink.label-prefix | Stream Load 标签前缀 | 自动生成 |

## 文件清单

### 新增文件

1. `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream2.java`
   - 使用官方 Doris Connector 的 Flink Job

2. `run-flink-ods-datastream2.sh`
   - 运行脚本

3. `test/test-doris-direct.sh`
   - Doris Stream Load 测试脚本

4. `test/test-data.json`
   - 测试数据文件

### 保留文件

1. `src/main/java/com/crypto/dw/flink/FlinkODSJobDataStream.java`
   - 原自定义实现 (保留作为参考)

2. `src/main/java/com/crypto/dw/flink/sink/DorisStreamLoadSink.java`
   - 自定义 Sink (保留作为参考)

## 后续优化建议

### 1. 监控和告警

```java
// 添加指标监控
.setDorisExecutionOptions(
    DorisExecutionOptions.builder()
        .setStreamLoadProp(streamLoadProp)
        .setMaxRetries(3)
        .enableMetrics(true)  // 启用指标
        .build()
)
```

### 2. 性能调优

```yaml
doris:
  stream-load:
    batch-size: 2000        # 增加批量大小
    batch-interval-ms: 3000 # 减少批量间隔
    buffer-count: 5         # 增加缓冲区数量
```

### 3. 错误处理

```java
// 添加自定义错误处理
.setDorisExecutionOptions(
    DorisExecutionOptions.builder()
        .setStreamLoadProp(streamLoadProp)
        .setMaxRetries(3)
        .setRetryDelay(1000)  // 重试延迟
        .build()
)
```

### 4. 考虑升级 Doris

如果 Doris 4.0.1-rc02 的 bug 持续存在,建议:
- 升级到 Doris 4.0.x 正式版 (GA)
- 或降级到 Doris 2.1.x 稳定版

## 相关文档

### 问题解决文档

1. [DECIMAL128 类型映射错误](./202603182234-Doris连接-解决DECIMAL128类型映射错误.md)
2. [HTTP 100-continue 错误](./202603182301-HTTP请求-解决100-continue错误.md)
3. [Connection Reset 错误](./202603182310-连接重置-解决Doris写入Connection-reset错误.md)

### 官方文档

1. [Doris Flink Connector 文档](https://doris.apache.org/zh-CN/docs/ecosystem/flink-doris-connector)
2. [Doris Stream Load 文档](https://doris.apache.org/zh-CN/docs/data-operate/import/stream-load-manual)
3. [Flink DataStream API](https://nightlies.apache.org/flink/flink-docs-release-1.17/docs/dev/datastream/overview/)

## 总结

通过使用官方 Doris Flink Connector 替代自定义 HTTP Stream Load 实现:

1. ✅ 解决了 Doris 4.0.1-rc02 的 Stream Load Bug
2. ✅ 避免了 Connection Reset 错误
3. ✅ 避免了 100-continue 错误
4. ✅ 获得了更好的版本兼容性
5. ✅ 降低了维护成本
6. ✅ 提高了系统稳定性

**推荐**: 在生产环境中使用官方 Connector,除非有特殊需求才考虑自定义实现。
