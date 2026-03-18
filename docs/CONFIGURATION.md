# 配置说明文档

## 配置文件结构

项目使用 YAML 格式的配置文件，支持多环境配置和环境变量引用。

```
config/
├── application.yml          # 基础配置（所有环境共享）
├── application-dev.yml      # 开发环境配置
└── application-prod.yml     # 生产环境配置
```

## 环境变量

### 设置环境

通过 `APP_ENV` 环境变量指定运行环境：

```bash
export APP_ENV=dev    # 开发环境（默认）
export APP_ENV=prod   # 生产环境
```

### 敏感信息

敏感信息（如 API 密钥、密码）应存储在环境变量中，不要直接写入配置文件。

在配置文件中使用 `${ENV_VAR_NAME}` 语法引用环境变量：

```yaml
okx:
  api:
    key: ${OKX_API_KEY}
    secret: ${OKX_SECRET_KEY}
    passphrase: ${OKX_PASSPHRASE}
```

### 必需的环境变量

| 环境变量 | 说明 | 示例 |
|---------|------|------|
| OKX_API_KEY | OKX API Key | abc123... |
| OKX_SECRET_KEY | OKX Secret Key | xyz789... |
| OKX_PASSPHRASE | OKX Passphrase | MyPass123 |
| APP_ENV | 运行环境 | dev / prod |

### 可选的环境变量

| 环境变量 | 说明 | 默认值 |
|---------|------|--------|
| DORIS_USERNAME | Doris 用户名 | root |
| DORIS_PASSWORD | Doris 密码 | (空) |

## 配置项说明

### OKX 配置

```yaml
okx:
  # WebSocket API 配置
  websocket:
    url: wss://ws.okx.com:8443/ws/v5/public
    reconnect:
      max-retries: 10          # 最大重连次数
      initial-delay: 1000      # 初始重连延迟（毫秒）
      max-delay: 60000         # 最大重连延迟（毫秒）
  
  # API 认证信息
  api:
    key: ${OKX_API_KEY}
    secret: ${OKX_SECRET_KEY}
    passphrase: ${OKX_PASSPHRASE}
  
  # 订阅的交易对列表
  symbols:
    - BTC-USDT
    - ETH-USDT
    # ... 更多交易对
```

### Kafka 配置

```yaml
kafka:
  bootstrap-servers: localhost:9092
  
  topic:
    crypto-ticker: crypto_ticker
  
  # Producer 配置
  producer:
    acks: 1                    # 确认级别：0/1/all
    retries: 3                 # 重试次数
    batch-size: 16384          # 批量大小（字节）
    linger-ms: 10              # 批量延迟（毫秒）
    compression-type: lz4      # 压缩类型
  
  # Consumer 配置
  consumer:
    group-id: flink-ods-consumer
    auto-offset-reset: latest  # earliest/latest
    enable-auto-commit: false
    max-poll-records: 500
```

### Doris 配置

```yaml
doris:
  # FE 节点配置
  fe:
    http-url: http://127.0.0.1:8030
    jdbc-url: jdbc:mysql://127.0.0.1:9030
    username: root
    password: ""
  
  database: crypto_dw
  
  # Stream Load 配置
  stream-load:
    batch-size: 1000           # 批量大小
    batch-interval-ms: 5000    # 批量间隔（毫秒）
    max-retries: 3             # 最大重试次数
    timeout-seconds: 600       # 超时时间（秒）
    max-filter-ratio: 0.1      # 最大过滤比例
  
  # 表配置
  tables:
    ods: ods_crypto_ticker_rt
    dwd: dwd_crypto_ticker_detail
    dws-1min: dws_crypto_ticker_1min
    dws-5min: dws_crypto_ticker_5min
    ads: ads_crypto_market_overview
```

### Flink 配置

```yaml
flink:
  # 执行环境配置
  execution:
    parallelism: 4             # 并行度
    max-parallelism: 128       # 最大并行度
  
  # Checkpoint 配置
  checkpoint:
    interval: 60000            # Checkpoint 间隔（毫秒）
    mode: EXACTLY_ONCE         # EXACTLY_ONCE/AT_LEAST_ONCE
    timeout: 600000            # 超时时间（毫秒）
    min-pause: 500             # 最小暂停时间（毫秒）
    max-concurrent: 1          # 最大并发 Checkpoint 数
  
  # 状态后端配置
  state:
    backend: rocksdb           # rocksdb/filesystem
    checkpoint-dir: file:///tmp/flink-checkpoints
    savepoint-dir: file:///tmp/flink-savepoints
```

### 应用配置

```yaml
application:
  # 日志配置
  logging:
    level: INFO                # DEBUG/INFO/WARN/ERROR
    file: logs/crypto-dw.log
    max-file-size: 100MB
    max-history: 30
  
  # 监控配置
  metrics:
    enabled: true
    interval: 60               # 指标收集间隔（秒）
  
  # 数据质量配置
  data-quality:
    timestamp-tolerance: 3600000  # 时间戳容忍度（毫秒）
    price-min: 0.00000001         # 最小价格
    price-max: 10000000           # 最大价格
    dedup-window: 5000            # 去重窗口（毫秒）
```

## 配置加载流程

1. 加载 `application.yml` 基础配置
2. 根据 `APP_ENV` 加载对应的环境配置文件
3. 环境配置覆盖基础配置
4. 解析配置中的环境变量引用 `${VAR_NAME}`
5. 从系统环境变量读取实际值

## 使用配置

### Java 代码中使用

```java
import com.crypto.dw.config.ConfigLoader;

// 获取配置实例
ConfigLoader config = ConfigLoader.getInstance();

// 读取字符串配置
String wsUrl = config.getString("okx.websocket.url");

// 读取整数配置
int parallelism = config.getInt("flink.execution.parallelism");

// 读取长整数配置
long checkpointInterval = config.getLong("flink.checkpoint.interval");

// 读取布尔配置
boolean metricsEnabled = config.getBoolean("application.metrics.enabled");

// 带默认值的读取
String logLevel = config.getString("application.logging.level", "INFO");
int batchSize = config.getInt("kafka.producer.batch-size", 1000);
```

## 配置验证

### 运行配置测试

```bash
# 测试配置加载
bash test-config.sh

# 或直接运行 Java 类
mvn exec:java -Dexec.mainClass="com.crypto.dw.ConfigTest"
```

### 验证环境变量

```bash
# 检查环境变量是否设置
echo $OKX_API_KEY
echo $OKX_SECRET_KEY
echo $OKX_PASSPHRASE
echo $APP_ENV
```

## 最佳实践

1. **不要提交敏感信息**
   - 使用 `.env` 文件存储敏感信息
   - 将 `.env` 添加到 `.gitignore`
   - 提供 `.env.example` 作为模板

2. **环境隔离**
   - 开发环境使用 `application-dev.yml`
   - 生产环境使用 `application-prod.yml`
   - 通过 `APP_ENV` 切换环境

3. **配置分层**
   - 公共配置放在 `application.yml`
   - 环境特定配置放在对应的环境文件
   - 敏感信息使用环境变量

4. **配置验证**
   - 启动前验证所有必需的环境变量
   - 使用配置测试脚本检查配置正确性
   - 记录配置加载日志

5. **文档维护**
   - 及时更新配置文档
   - 说明每个配置项的作用和取值范围
   - 提供配置示例

## 故障排查

### 配置加载失败

```bash
# 检查配置文件是否存在
ls -la config/

# 检查 YAML 语法
# 使用在线 YAML 验证器或 yamllint 工具
```

### 环境变量未生效

```bash
# 确认环境变量已设置
env | grep OKX

# 重新加载环境变量
source .env
export $(cat .env | grep -v '^#' | xargs)
```

### 配置值不正确

```bash
# 运行配置测试查看实际加载的值
bash test-config.sh

# 检查配置合并逻辑
# 环境配置会覆盖基础配置
```
