# Doris 写入 Connection Reset 错误解决方案

## 问题描述

在 Flink 任务向 Doris 写入数据时,出现 `java.net.SocketException: Connection reset` 错误:

```
2026-03-18 23:04:40,727 ERROR DorisStreamLoadSink:199 - ========== 刷新数据到 Doris 失败 ==========
2026-03-18 23:04:40,727 ERROR DorisStreamLoadSink:200 - 记录数: 1000
2026-03-18 23:04:40,727 ERROR DorisStreamLoadSink:201 - 错误信息: Connection reset
java.net.SocketException: Connection reset
    at java.net.SocketInputStream.read(SocketInputStream.java:186)
    at sun.net.www.http.HttpClient.parseHTTPHeader(HttpClient.java:789)
    at sun.net.www.protocol.http.HttpURLConnection.getInputStream0(HttpURLConnection.java:1654)
    at java.net.HttpURLConnection.getResponseCode(HttpURLConnection.java:527)
```

## 问题分析

Connection reset 错误通常由以下原因导致:

1. **超时设置不合理**
   - 原代码连接超时和读取超时都设置为 30 秒
   - 当批量数据较大(1000 条记录)时,30 秒可能不够
   - 配置文件中设置的超时是 600 秒,但代码中没有使用

2. **HTTP 连接复用问题**
   - HttpURLConnection 默认会复用连接
   - 在高并发场景下,连接复用可能导致连接状态不一致
   - Doris 服务端可能主动关闭了空闲连接

3. **网络不稳定**
   - 偶发的网络抖动导致连接被重置
   - 需要重试机制来提高容错性

## 解决方案

### 1. 增加超时时间

```java
// 修复前
conn.setConnectTimeout(30000);  // 30 秒
conn.setReadTimeout(30000);     // 30 秒

// 修复后
conn.setConnectTimeout(60000);  // 60 秒
conn.setReadTimeout(120000);    // 120 秒
```

**原因**: 大批量数据写入需要更长的处理时间,避免超时导致连接被重置。

### 2. 禁用连接复用

```java
// 新增配置
conn.setRequestProperty("Connection", "close");
```

**原因**: 
- 每次请求使用新连接,避免连接状态不一致
- 虽然性能略有下降,但提高了稳定性
- 在 Flink 的批量写入场景下,稳定性优先于性能

### 3. 添加重试机制

```java
// 重试逻辑
for (int retry = 0; retry < maxRetries; retry++) {
    try {
        if (retry > 0) {
            logger.warn("第 {} 次重试写入 Doris...", retry);
            Thread.sleep(1000 * retry);  // 指数退避
        }
        
        doFlush(recordCount);
        return;  // 成功则返回
        
    } catch (java.net.SocketException e) {
        // 连接重置错误,可以重试
        lastException = e;
        logger.warn("连接被重置 (第 {} 次尝试): {}", retry + 1, e.getMessage());
    }
}
```

**特点**:
- 默认重试 3 次
- 只对 SocketException 进行重试
- 使用指数退避策略(第 n 次重试等待 n 秒)
- 其他异常直接抛出,不重试

## 代码修改

### 修改文件
`src/main/java/com/crypto/dw/flink/sink/DorisStreamLoadSink.java`

### 主要改动

1. **添加重试次数字段**
```java
private final int maxRetries;  // 最大重试次数
```

2. **重构 flush 方法**
   - 原 `flush()` 方法改为带重试逻辑的版本
   - 新增 `doFlush()` 方法执行实际的写入操作
   - 分离重试逻辑和业务逻辑

3. **优化 HTTP 连接配置**
   - 增加超时时间
   - 禁用连接复用
   - 保留之前的 `Expect: ""` 修复

## 测试验证

### 测试步骤

1. 重新编译项目
```bash
mvn clean package -DskipTests
```

2. 重启 Flink ODS 任务
```bash
./run-flink-ods-datastream.sh
```

3. 观察日志
```bash
tail -f logs/flink-app.log
```

### 预期结果

- 正常情况: 数据成功写入,无错误日志
- 偶发连接重置: 自动重试,最终成功写入
- 持续失败: 重试 3 次后抛出异常,Flink 会根据 checkpoint 机制恢复

## 相关配置

### application.yml 配置
```yaml
doris:
  stream-load:
    batch-size: 1000          # 批次大小
    batch-interval-ms: 5000   # 批次间隔
    max-retries: 3            # 最大重试次数
    timeout-seconds: 600      # 超时时间
```

### 性能影响

- **连接复用禁用**: 每次请求建立新连接,增加约 10-50ms 延迟
- **重试机制**: 仅在失败时触发,正常情况无影响
- **超时增加**: 不影响正常请求,仅在异常情况下生效

## 后续优化建议

1. **监控连接重置频率**
   - 如果频繁出现,需要检查 Doris 服务端配置
   - 可能需要调整 Doris 的连接池大小

2. **考虑使用连接池**
   - 如果性能成为瓶颈,可以使用 Apache HttpClient
   - 提供更好的连接管理和重试机制

3. **调整批次大小**
   - 如果仍有问题,可以减小 batch-size
   - 例如从 1000 降到 500 或 200

## 参考文档

- [Doris Stream Load 文档](https://doris.apache.org/zh-CN/docs/data-operate/import/stream-load-manual)
- [Java HttpURLConnection 最佳实践](https://docs.oracle.com/javase/8/docs/api/java/net/HttpURLConnection.html)
- 相关问题文档: `202603182301-HTTP请求-解决100-continue错误.md`
