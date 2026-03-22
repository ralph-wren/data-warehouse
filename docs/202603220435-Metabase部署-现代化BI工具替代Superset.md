# Metabase 部署 - 现代化 BI 工具替代 Superset

## 背景

Grafana 的 MySQL 插件不支持最新版本,显示 "This plugin doesn't support your version of Grafana" 错误。为了提供更好的数据可视化体验,我们部署 Metabase 作为替代方案。

## Metabase 简介

Metabase 是一个开源的现代化 BI 工具,具有以下优势:

### 优势
- ✅ 界面非常现代化、简洁美观
- ✅ 无需 SQL 知识,拖拽式操作
- ✅ 部署简单,单容器即可运行
- ✅ 对非技术用户友好
- ✅ 支持 MySQL 协议,可以连接 Doris
- ✅ 自动生成可视化建议
- ✅ 支持仪表板和报表

### 与其他工具对比

| 工具 | 界面美观度 | 易用性 | 技术要求 | 部署难度 | 推荐指数 |
|-----|----------|--------|---------|---------|---------|
| Metabase | ⭐⭐⭐⭐⭐ | ⭐⭐⭐⭐⭐ | 低 | 简单 | ⭐⭐⭐⭐⭐ |
| Superset | ⭐⭐⭐ | ⭐⭐ | 高 | 复杂 | ⭐⭐⭐ |
| Grafana | ⭐⭐⭐⭐⭐ | ⭐⭐⭐ | 中 | 简单 | ⭐⭐⭐⭐ |

## 部署步骤

### 1. 创建 Docker Compose 配置

创建 `docker-compose-metabase.yml`:

```yaml
version: '3.8'

services:
  metabase:
    image: metabase/metabase:latest
    container_name: metabase
    hostname: metabase
    ports:
      - "3001:3000"  # 使用 3001 端口,避免与 Grafana 冲突
    environment:
      # 数据库配置 - 使用 H2 内嵌数据库
      - MB_DB_TYPE=h2
      - MB_DB_FILE=/metabase-data/metabase.db
      # 时区配置
      - TZ=Asia/Shanghai
      - JAVA_TIMEZONE=Asia/Shanghai
      # 日志级别
      - MB_LOG_LEVEL=INFO
    volumes:
      # 持久化 Metabase 数据
      - metabase-data:/metabase-data
    networks:
      - doris-net  # 连接到 Doris 网络
    restart: unless-stopped
    healthcheck:
      test: ["CMD", "curl", "-f", "http://localhost:3000/api/health"]
      interval: 30s
      timeout: 10s
      retries: 3
      start_period: 60s

volumes:
  metabase-data:
    driver: local

networks:
  doris-net:
    external: true
    name: data-warehouse_doris-net
```

### 2. 创建管理脚本

创建 `manage-metabase.sh`:

```bash
#!/bin/bash
# Metabase 管理脚本

COMPOSE_FILE="docker-compose-metabase.yml"

case "$1" in
    start)
        echo "启动 Metabase..."
        docker-compose -f $COMPOSE_FILE up -d
        ;;
    stop)
        echo "停止 Metabase..."
        docker-compose -f $COMPOSE_FILE stop
        ;;
    restart)
        echo "重启 Metabase..."
        docker-compose -f $COMPOSE_FILE restart
        ;;
    logs)
        docker-compose -f $COMPOSE_FILE logs -f metabase
        ;;
    status)
        docker-compose -f $COMPOSE_FILE ps
        ;;
    down)
        echo "停止并删除 Metabase 容器..."
        docker-compose -f $COMPOSE_FILE down
        ;;
    clean)
        echo "警告: 这将删除所有 Metabase 数据!"
        read -p "确认删除? (yes/no): " confirm
        if [ "$confirm" = "yes" ]; then
            docker-compose -f $COMPOSE_FILE down -v
            echo "Metabase 数据已删除"
        fi
        ;;
    *)
        echo "用法: $0 {start|stop|restart|logs|status|down|clean}"
        exit 1
        ;;
esac
```

### 3. 启动 Metabase

```bash
# 添加执行权限
chmod +x manage-metabase.sh

# 启动 Metabase
bash manage-metabase.sh start

# 等待 30-60 秒让 Metabase 完全启动
```

### 4. 验证部署

```bash
# 检查容器状态
docker ps --filter "name=metabase"

# 应该看到:
# NAMES      STATUS                   PORTS
# metabase   Up X minutes (healthy)   0.0.0.0:3001->3000/tcp

# 查看日志
docker logs metabase --tail 50
```

## 初始化配置

### 1. 访问 Metabase

打开浏览器访问: **http://localhost:3001**

### 2. 创建管理员账号

首次访问会看到欢迎页面:

1. 点击 "Let's get started"
2. 填写管理员信息:
   - **First name**: Admin
   - **Last name**: User
   - **Email**: admin@example.com
   - **Password**: (设置一个强密码)
3. 点击 "Next"

### 3. 添加数据库连接

#### 选择数据库类型

1. 在 "Add your data" 页面
2. 选择 **MySQL**

#### 填写连接信息

```
Display name: Doris Crypto DW
Host: doris-fe
Port: 9030
Database name: crypto_dw
Username: root
Password: (留空)
```

#### 高级选项(可选)

点击 "Show advanced options" 可以配置:
- Use a secure connection (SSL): 关闭
- Use an SSH-tunnel for database connections: 关闭
- Additional JDBC connection string options: (留空)

#### 测试连接

1. 点击 "Connect database" 按钮
2. Metabase 会测试连接
3. 如果成功,会显示 "Success!" 提示

### 4. 数据使用偏好

1. 选择 "I'll add my own data later" (我们已经添加了)
2. 或者选择 "Take me to Metabase" 跳过

### 5. 完成设置

点击 "Take me to Metabase" 进入主界面

## 使用指南

### 创建第一个问题(查询)

#### 方法 1: 简单问题(无需 SQL)

1. 点击右上角的 "New" 按钮
2. 选择 "Question"
3. 选择数据库: "Doris Crypto DW"
4. 选择表: "dwd_crypto_ticker_detail"
5. Metabase 会自动显示数据预览
6. 点击 "Visualize" 查看可视化

#### 方法 2: 原生查询(使用 SQL)

1. 点击右上角的 "New" 按钮
2. 选择 "Question"
3. 点击右下角的 "Native query"
4. 输入 SQL 查询:

```sql
-- 各币种最新价格
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

5. 点击右下角的 "Get Answer" 或按 Ctrl+Enter 执行
6. 选择可视化类型(表格、柱状图、折线图等)
7. 点击 "Save" 保存查询

### 创建仪表板

#### 1. 创建新仪表板

1. 点击右上角的 "New" 按钮
2. 选择 "Dashboard"
3. 输入名称: "加密货币实时监控"
4. 点击 "Create"

#### 2. 添加问题到仪表板

1. 点击 "Add a question"
2. 选择之前保存的问题
3. 调整大小和位置
4. 点击 "Save" 保存仪表板

#### 3. 设置自动刷新

1. 点击仪表板右上角的 "..." 菜单
2. 选择 "Auto-refresh"
3. 选择刷新间隔(1分钟、5分钟、10分钟等)

### 常用查询示例

#### 1. 各币种最新价格排行

```sql
SELECT 
  inst_id AS 币种,
  last_price AS 最新价格,
  volume_24h AS 24小时成交量
FROM (
  SELECT 
    inst_id,
    last_price,
    volume_24h,
    ROW_NUMBER() OVER (PARTITION BY inst_id ORDER BY timestamp DESC) AS rn
  FROM dwd_crypto_ticker_detail
) t
WHERE rn = 1
ORDER BY last_price DESC
```

**可视化类型**: 表格

#### 2. 价格趋势(最近1小时)

```sql
SELECT 
  FROM_UNIXTIME(timestamp / 1000) AS 时间,
  inst_id AS 币种,
  last_price AS 价格
FROM dwd_crypto_ticker_detail
WHERE 
  timestamp >= UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 1 HOUR)) * 1000
  AND inst_id IN ('BTC-USDT', 'ETH-USDT', 'BNB-USDT')
ORDER BY timestamp
```

**可视化类型**: 折线图
- X 轴: 时间
- Y 轴: 价格
- 分组: 币种

#### 3. 24小时成交量分布

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
```

**可视化类型**: 饼图

#### 4. 价格波动统计(24小时)

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

**可视化类型**: 柱状图

## 高级功能

### 1. 数据探索

Metabase 提供了强大的数据探索功能:

- **X-ray**: 自动分析表并生成可视化建议
- **Filter**: 添加筛选条件
- **Summarize**: 聚合数据
- **Join**: 关联多个表

### 2. 告警和订阅

1. 打开一个问题或仪表板
2. 点击右上角的 "..." 菜单
3. 选择 "Get alerts"
4. 配置告警条件和通知方式

### 3. 分享和嵌入

1. 打开问题或仪表板
2. 点击右上角的分享图标
3. 选择分享方式:
   - **Public link**: 生成公开链接
   - **Embed**: 嵌入到网页
   - **Export**: 导出为 CSV、JSON 等

### 4. 权限管理

1. 点击右上角的设置图标
2. 选择 "Admin settings"
3. 进入 "People" 管理用户
4. 进入 "Permissions" 管理权限

## 故障排查

### 1. 无法连接到 Doris

**症状**: 添加数据库时提示连接失败

**解决方法**:

```bash
# 1. 检查 Metabase 到 Doris 的网络连接
docker exec metabase ping -c 3 doris-fe

# 2. 检查 Doris 是否运行
docker ps --filter "name=doris"

# 3. 测试 Doris MySQL 端口
docker exec metabase nc -zv doris-fe 9030

# 4. 重启 Metabase
bash manage-metabase.sh restart
```

### 2. 查询超时

**症状**: 执行查询时提示超时

**解决方法**:

1. 减少查询的时间范围
2. 添加索引到 Doris 表
3. 优化 SQL 查询
4. 增加 Metabase 的超时设置:
   - Admin settings → Databases → 编辑数据库
   - 增加 "Additional JDBC connection string options"
   - 添加: `connectTimeout=60000&socketTimeout=60000`

### 3. 时间显示不正确

**症状**: 时间显示为 UTC 而不是东八区

**解决方法**:

1. 确认 Doris 时区设置:
```bash
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW VARIABLES LIKE 'time_zone'"
```

2. 确认 Metabase 时区设置:
   - Admin settings → Localization
   - Report Timezone: Asia/Shanghai

### 4. 容器无法启动

**症状**: Metabase 容器启动失败

**解决方法**:

```bash
# 查看日志
docker logs metabase

# 检查端口占用
netstat -ano | findstr :3001

# 清理并重新启动
bash manage-metabase.sh down
bash manage-metabase.sh start
```

## 性能优化

### 1. 使用外部数据库

默认使用 H2 内嵌数据库,生产环境建议使用 PostgreSQL:

```yaml
environment:
  - MB_DB_TYPE=postgres
  - MB_DB_DBNAME=metabase
  - MB_DB_PORT=5432
  - MB_DB_USER=metabase
  - MB_DB_PASS=metabase_password
  - MB_DB_HOST=postgres
```

### 2. 启用查询缓存

1. Admin settings → Caching
2. 启用 "Cache query results"
3. 设置缓存时间(如 1 小时)

### 3. 优化查询

- 使用窗口函数替代子查询
- 添加适当的索引
- 限制返回的行数
- 使用物化视图

## 相关文件

- `docker-compose-metabase.yml` - Metabase Docker Compose 配置
- `manage-metabase.sh` - Metabase 管理脚本

## 访问信息

- **URL**: http://localhost:3001
- **管理员**: 首次访问时创建
- **数据库**: Doris Crypto DW (doris-fe:9030)

## 下一步

1. 创建更多查询和可视化
2. 构建完整的监控仪表板
3. 配置告警规则
4. 设置定期报表
5. 邀请团队成员使用

## 总结

Metabase 是一个现代化、易用的 BI 工具,完美替代了 Superset 和 Grafana(MySQL 插件不兼容)。它提供了直观的界面、强大的数据探索功能和丰富的可视化选项,非常适合团队协作和数据分析。
