# Superset BI 工具部署和使用指南

**时间**: 2026-03-22  
**工具**: Apache Superset  
**用途**: 数据可视化和业务分析

---

## 什么是 Superset?

Apache Superset 是一个现代化的数据探索和可视化平台,特点:

- ✅ 开源免费,功能强大
- ✅ 支持多种数据源 (包括 Doris)
- ✅ 丰富的图表类型
- ✅ SQL Lab 交互式查询
- ✅ 支持实时数据刷新
- ✅ 权限管理完善

---

## 快速部署

### 1. 启动 Superset

```bash
# 启动服务
bash manage-superset.sh start

# 查看状态
bash manage-superset.sh status

# 查看日志
bash manage-superset.sh logs
```

### 2. 访问 Superset

- **地址**: http://localhost:8088
- **默认账号**: admin
- **默认密码**: admin

**首次启动需要等待 1-2 分钟初始化**

---

## 连接 Doris 数据源

### 步骤 1: 添加数据库连接

1. 登录 Superset
2. 点击右上角 `+` → `Data` → `Connect Database`
3. 选择 `MySQL`
4. 填写连接信息:

```
Host: doris-fe
Port: 9030
Database: crypto_dw
Username: root
Password: (留空)
```

或使用 SQLAlchemy URI:
```
mysql://root@doris-fe:9030/crypto_dw
```

5. 点击 `Test Connection` 测试连接
6. 点击 `Connect` 保存

### 步骤 2: 添加数据集

1. 点击 `+` → `Data` → `Dataset`
2. 选择数据库: `crypto_dw`
3. 选择表:
   - `ods_crypto_ticker_rt` (ODS 层原始数据)
   - `dwd_crypto_ticker_detail` (DWD 层明细数据)
   - `dws_crypto_ticker_1min` (DWS 层 1 分钟聚合)
   - `ads_crypto_market_overview` (ADS 层市场概览)
4. 点击 `Add` 添加

---

## 创建图表示例

### 示例 1: 实时价格折线图

**数据集**: `ods_crypto_ticker_rt`

**配置**:
- 图表类型: Time-series Line Chart
- X 轴: `timestamp` (时间戳)
- Y 轴: `last_price` (最新价格)
- 分组: `inst_id` (交易对)
- 时间范围: Last 1 hour
- 刷新间隔: 10 seconds

**SQL**:
```sql
SELECT 
    FROM_UNIXTIME(timestamp/1000) as time,
    inst_id,
    last_price
FROM ods_crypto_ticker_rt
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
ORDER BY timestamp DESC
```

### 示例 2: 24 小时涨跌幅排行

**数据集**: `ods_crypto_ticker_rt`

**配置**:
- 图表类型: Bar Chart
- X 轴: `inst_id`
- Y 轴: `(last_price - open_24h) / open_24h * 100` (涨跌幅 %)
- 排序: 降序
- 限制: Top 10

**SQL**:
```sql
SELECT 
    inst_id,
    ROUND((last_price - open_24h) / open_24h * 100, 2) as change_pct
FROM (
    SELECT 
        inst_id,
        last_price,
        open_24h,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) as rn
    FROM ods_crypto_ticker_rt
) t
WHERE rn = 1
ORDER BY change_pct DESC
LIMIT 10
```

### 示例 3: 交易量分布饼图

**数据集**: `ods_crypto_ticker_rt`

**配置**:
- 图表类型: Pie Chart
- 维度: `inst_id`
- 指标: `SUM(volume_24h)`

**SQL**:
```sql
SELECT 
    inst_id,
    SUM(volume_24h) as total_volume
FROM (
    SELECT 
        inst_id,
        volume_24h,
        ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) as rn
    FROM ods_crypto_ticker_rt
) t
WHERE rn = 1
GROUP BY inst_id
ORDER BY total_volume DESC
```

### 示例 4: 1 分钟 K 线图

**数据集**: `dws_crypto_ticker_1min`

**配置**:
- 图表类型: Mixed Chart (Candlestick + Volume)
- X 轴: `window_start`
- 开盘价: `open_price`
- 最高价: `high_price`
- 最低价: `low_price`
- 收盘价: `close_price`
- 成交量: `total_volume`

---

## 创建仪表板

### 步骤 1: 创建 Dashboard

1. 点击 `+` → `Dashboard`
2. 输入名称: `加密货币实时监控`
3. 点击 `Save`

### 步骤 2: 添加图表

1. 点击 `Edit Dashboard`
2. 从右侧拖拽图表到仪表板
3. 调整图表大小和位置
4. 点击 `Save` 保存

### 推荐布局

```
+----------------------------------+----------------------------------+
|  实时价格折线图 (全宽)                                              |
+----------------------------------+----------------------------------+
|  24小时涨跌幅排行 (1/2)          |  交易量分布饼图 (1/2)            |
+----------------------------------+----------------------------------+
|  1分钟K线图 (全宽)                                                  |
+----------------------------------+----------------------------------+
|  市场概览表格 (全宽)                                                |
+----------------------------------+----------------------------------+
```

---

## SQL Lab 使用

### 交互式查询

1. 点击 `SQL` → `SQL Lab`
2. 选择数据库: `crypto_dw`
3. 输入 SQL 查询
4. 点击 `Run` 执行

### 示例查询

**查询最新价格**:
```sql
SELECT 
    inst_id,
    last_price,
    FROM_UNIXTIME(timestamp/1000) as update_time
FROM ods_crypto_ticker_rt
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 5 MINUTE)) * 1000
ORDER BY timestamp DESC
LIMIT 100
```

**查询价格波动**:
```sql
SELECT 
    inst_id,
    MIN(last_price) as min_price,
    MAX(last_price) as max_price,
    AVG(last_price) as avg_price,
    ROUND((MAX(last_price) - MIN(last_price)) / MIN(last_price) * 100, 2) as volatility_pct
FROM ods_crypto_ticker_rt
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
GROUP BY inst_id
ORDER BY volatility_pct DESC
```

---

## 高级功能

### 1. 告警配置

1. 在图表中点击 `...` → `Set up alerts`
2. 配置告警条件:
   - 价格超过阈值
   - 涨跌幅超过百分比
   - 交易量异常
3. 配置通知方式:
   - Email
   - Slack
   - Webhook

### 2. 定时刷新

1. 在 Dashboard 中点击 `...` → `Set auto-refresh interval`
2. 选择刷新间隔:
   - 10 seconds
   - 30 seconds
   - 1 minute
   - 5 minutes

### 3. 权限管理

1. 点击 `Settings` → `List Users`
2. 创建用户并分配角色:
   - Admin: 完全权限
   - Alpha: 可创建和编辑
   - Gamma: 只读权限

---

## 常见问题

### 1. 无法连接 Doris

**问题**: Connection refused

**解决**:
- 确保 Doris 正在运行: `docker ps | grep doris`
- 确保 Superset 和 Doris 在同一网络: `data-warehouse-net`
- 使用容器名称 `doris-fe` 而不是 `localhost`

### 2. 图表不显示数据

**问题**: No data

**解决**:
- 检查时间范围是否正确
- 检查 SQL 查询是否有结果
- 检查数据集是否正确配置

### 3. 中文显示乱码

**问题**: 中文显示为方块

**解决**:
- 在 `superset_config.py` 中添加:
  ```python
  BABEL_DEFAULT_LOCALE = 'zh'
  LANGUAGES = {
      'zh': {'flag': 'cn', 'name': 'Chinese'}
  }
  ```

---

## 管理命令

```bash
# 启动 Superset
bash manage-superset.sh start

# 停止 Superset
bash manage-superset.sh stop

# 重启 Superset
bash manage-superset.sh restart

# 查看日志
bash manage-superset.sh logs

# 查看状态
bash manage-superset.sh status

# 清理数据
bash manage-superset.sh clean
```

---

## 与 Grafana 的对比

| 特性 | Superset | Grafana |
|------|----------|---------|
| 主要用途 | 业务分析 | 系统监控 |
| 图表类型 | 丰富 (50+) | 中等 (20+) |
| SQL 支持 | 强 (SQL Lab) | 弱 |
| 实时刷新 | 支持 | 强 |
| 告警功能 | 基础 | 强 |
| 权限管理 | 强 | 中等 |
| 学习曲线 | 中等 | 简单 |

**建议**:
- **Grafana**: 用于系统监控 (Flink、Kafka、Doris 指标)
- **Superset**: 用于业务分析 (价格、交易量、市场趋势)

---

## 相关文件

- `docker-compose-superset.yml` - Superset Docker Compose 配置
- `manage-superset.sh` - Superset 管理脚本

---

## 参考资源

- [Superset 官方文档](https://superset.apache.org/docs/intro)
- [Superset GitHub](https://github.com/apache/superset)
- [Doris 连接指南](https://doris.apache.org/zh-CN/docs/ecosystem/bi-tools)

---

## 总结

Superset 是一个功能强大的 BI 工具,非常适合:
- ✅ 复杂的数据分析
- ✅ 多维度数据探索
- ✅ 团队协作
- ✅ 业务报表

结合 Grafana 使用,可以实现:
- **Grafana**: 系统监控 + 实时告警
- **Superset**: 业务分析 + 复杂报表

这样可以满足技术和业务两方面的需求!
