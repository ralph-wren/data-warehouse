# Metabase 快速配置指南

## 当前状态

✅ Metabase 已成功部署并运行
- 访问地址: **http://localhost:3001**
- 容器状态: healthy
- 网络连接: 已连接到 doris-net

## 快速开始(5分钟配置)

### 第一步: 初始化设置(2分钟)

1. **打开浏览器访问**: http://localhost:3001

2. **创建管理员账号**:
   ```
   First name: Admin
   Last name: User
   Email: admin@localhost
   Password: admin123456  (建议使用更强的密码)
   ```

3. **添加 Doris 数据库**:
   ```
   Database type: MySQL
   Display name: Doris 加密货币数据仓库
   Host: doris-fe
   Port: 9030
   Database name: crypto_dw
   Username: root
   Password: (留空)
   ```

4. **点击 "Connect database"** 测试连接

5. **完成设置**: 点击 "Take me to Metabase"

### 第二步: 创建第一个查询(2分钟)

#### 查询 1: 各币种最新价格

1. 点击右上角 **"New"** → **"Question"**
2. 点击右下角 **"Native query"** (使用 SQL)
3. 粘贴以下 SQL:

```sql
-- 各币种最新价格排行
SELECT 
  inst_id AS 币种,
  last_price AS 最新价格,
  volume_24h AS 24小时成交量,
  FROM_UNIXTIME(timestamp / 1000) AS 更新时间
FROM (
  SELECT 
    inst_id,
    last_price,
    volume_24h,
    timestamp,
    ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
  FROM dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY last_price DESC
```

4. 点击 **"Get Answer"** 或按 **Ctrl+Enter**
5. 点击 **"Save"** 保存查询,命名为 "币种最新价格"

### 第三步: 创建监控仪表板(1分钟)

1. 点击右上角 **"New"** → **"Dashboard"**
2. 输入名称: **"加密货币实时监控"**
3. 点击 **"Create"**
4. 点击 **"Add a question"**
5. 选择刚才保存的 **"币种最新价格"**
6. 调整卡片大小
7. 点击 **"Save"** 保存仪表板

## 推荐查询集合

### 1. 价格趋势图(最近1小时)

```sql
SELECT 
  FROM_UNIXTIME(timestamp / 1000) AS 时间,
  inst_id AS 币种,
  last_price AS 价格
FROM dwd_crypto_ticker_detail
WHERE 
  timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
  AND inst_id IN ('BTC-USDT', 'ETH-USDT', 'BNB-USDT', 'SOL-USDT', 'XRP-USDT')
ORDER BY timestamp
```

**可视化设置**:
- 图表类型: 折线图
- X 轴: 时间
- Y 轴: 价格
- 分组: 币种

### 2. 24小时成交量分布

```sql
SELECT 
  inst_id AS 币种,
  volume_24h AS 成交量
FROM (
  SELECT 
    inst_id,
    volume_24h,
    ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
  FROM dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY volume_24h DESC
LIMIT 10
```

**可视化设置**:
- 图表类型: 柱状图或饼图
- X 轴: 币种
- Y 轴: 成交量

### 3. 价格波动率(24小时)

```sql
SELECT 
  inst_id AS 币种,
  MAX(last_price) AS 最高价,
  MIN(last_price) AS 最低价,
  AVG(last_price) AS 平均价,
  ROUND((MAX(last_price) - MIN(last_price)) / MIN(last_price) * 100, 2) AS 波动率
FROM dwd_crypto_ticker_detail
WHERE timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 24 HOUR)) * 1000
GROUP BY inst_id
ORDER BY 波动率 DESC
```

**可视化设置**:
- 图表类型: 柱状图
- X 轴: 币种
- Y 轴: 波动率

### 4. 实时数据统计

```sql
SELECT 
  COUNT(DISTINCT inst_id) AS 监控币种数,
  COUNT(*) AS 总记录数,
  MAX(FROM_UNIXTIME(timestamp / 1000)) AS 最新数据时间,
  MIN(FROM_UNIXTIME(timestamp / 1000)) AS 最早数据时间
FROM dwd_crypto_ticker_detail
```

**可视化设置**:
- 图表类型: 数字卡片(Number)

### 5. 买卖盘深度对比

```sql
SELECT 
  inst_id AS 币种,
  bid_price AS 买一价,
  bid_size AS 买一量,
  ask_price AS 卖一价,
  ask_size AS 卖一量,
  ROUND((ask_price - bid_price) / bid_price * 100, 4) AS 价差百分比
FROM (
  SELECT 
    inst_id,
    bid_price,
    bid_size,
    ask_price,
    ask_size,
    ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
  FROM dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY 价差百分比 DESC
```

**可视化设置**:
- 图表类型: 表格

## 仪表板布局建议

创建一个完整的监控仪表板,包含以下卡片:

```
┌─────────────────────────────────────────────────────────┐
│  加密货币实时监控仪表板                                    │
├─────────────────────────────────────────────────────────┤
│                                                         │
│  ┌──────────┐ ┌──────────┐ ┌──────────┐ ┌──────────┐  │
│  │监控币种数 │ │总记录数   │ │最新时间   │ │数据延迟   │  │
│  │   15     │ │ 125,430  │ │ 刚刚     │ │  < 1秒   │  │
│  └──────────┘ └──────────┘ └──────────┘ └──────────┘  │
│                                                         │
│  ┌─────────────────────────────────────────────────┐   │
│  │  价格趋势图(最近1小时)                            │   │
│  │  [折线图: BTC, ETH, BNB, SOL, XRP]              │   │
│  └─────────────────────────────────────────────────┘   │
│                                                         │
│  ┌──────────────────────┐  ┌──────────────────────┐   │
│  │  各币种最新价格       │  │  24小时成交量分布     │   │
│  │  [表格]              │  │  [柱状图]            │   │
│  └──────────────────────┘  └──────────────────────┘   │
│                                                         │
│  ┌──────────────────────┐  ┌──────────────────────┐   │
│  │  价格波动率(24h)      │  │  买卖盘深度对比       │   │
│  │  [柱状图]            │  │  [表格]              │   │
│  └──────────────────────┘  └──────────────────────┘   │
│                                                         │
└─────────────────────────────────────────────────────────┘
```

## 高级功能

### 1. 设置自动刷新

1. 打开仪表板
2. 点击右上角 **"..."** 菜单
3. 选择 **"Auto-refresh"**
4. 选择刷新间隔: **1 minute** (实时监控)

### 2. 创建告警

1. 打开一个查询
2. 点击右上角 **"..."** 菜单
3. 选择 **"Get alerts"**
4. 配置告警条件:
   - 当价格波动超过 5% 时发送邮件
   - 当成交量异常时通知

### 3. 分享仪表板

1. 打开仪表板
2. 点击右上角的分享图标
3. 选择分享方式:
   - **Public link**: 生成公开链接(无需登录)
   - **Embed**: 嵌入到网页
   - **Export**: 导出为 PDF

### 4. 数据探索(X-ray)

Metabase 的 X-ray 功能可以自动分析表并生成可视化建议:

1. 进入 **"Browse data"**
2. 选择 **"dwd_crypto_ticker_detail"** 表
3. 点击右上角的 **"X-ray"** 图标
4. Metabase 会自动生成多个可视化建议

## 常见问题

### Q1: 无法连接到 Doris?

**解决方法**:
```bash
# 测试网络连接
docker exec metabase ping -c 3 doris-fe

# 测试 MySQL 端口
docker exec metabase nc -zv doris-fe 9030

# 重启 Metabase
bash manage-metabase.sh restart
```

### Q2: 时间显示不正确?

**解决方法**:
1. 进入 **Admin settings** → **Localization**
2. 设置 **Report Timezone**: Asia/Shanghai
3. 确认 Doris 时区: `SHOW VARIABLES LIKE 'time_zone'`

### Q3: 查询速度慢?

**优化方法**:
1. 减少查询的时间范围
2. 使用 `LIMIT` 限制返回行数
3. 在 Doris 中添加索引
4. 启用 Metabase 查询缓存

### Q4: 如何导出数据?

**方法**:
1. 打开查询结果
2. 点击右下角的下载图标
3. 选择格式: CSV, JSON, XLSX

## 管理命令

```bash
# 查看状态
bash manage-metabase.sh status

# 查看日志
bash manage-metabase.sh logs

# 重启服务
bash manage-metabase.sh restart

# 停止服务
bash manage-metabase.sh stop

# 启动服务
bash manage-metabase.sh start
```

## 访问信息

| 项目 | 信息 |
|-----|------|
| URL | http://localhost:3001 |
| 管理员 | 首次访问时创建 |
| 数据库 | Doris Crypto DW |
| 主机 | doris-fe:9030 |
| 数据库名 | crypto_dw |
| 用户名 | root |
| 密码 | (空) |

## 下一步

1. ✅ 访问 http://localhost:3001 完成初始化
2. ✅ 创建管理员账号
3. ✅ 添加 Doris 数据库连接
4. ✅ 创建上述推荐的 5 个查询
5. ✅ 构建完整的监控仪表板
6. ✅ 设置自动刷新(1分钟)
7. ⏭️ 配置告警规则
8. ⏭️ 邀请团队成员

## 总结

Metabase 提供了现代化、直观的界面,非常适合实时监控加密货币数据。通过上述配置,你可以在 5 分钟内搭建一个功能完整的监控仪表板。

**优势**:
- 🎨 界面美观现代
- 🚀 配置简单快速
- 📊 可视化丰富
- 🔄 支持自动刷新
- 📱 响应式设计
- 🔔 支持告警通知

现在就开始使用 Metabase 吧! 🎉
