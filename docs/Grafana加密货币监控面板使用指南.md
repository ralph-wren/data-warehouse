# Grafana 加密货币监控面板使用指南

## 快速访问

### 访问地址
- **URL**: http://localhost:3000
- **用户名**: `admin`
- **密码**: `admin`

### 面板路径
`Dashboards` → `加密货币实时数据监控`

或直接访问: http://localhost:3000/d/crypto-dashboard

---

## 面板概览

### 顶部统计卡片 (4个)

```
┌─────────────┬─────────────┬─────────────┬─────────────┐
│  总数据量   │  币种数量   │ BTC最新价格 │ ETH最新价格 │
│  136,934    │     9       │  $70,415    │   $2,148    │
└─────────────┴─────────────┴─────────────┴─────────────┘
```

- **总数据量**: DWD 表中的总记录数
- **币种数量**: 监控的加密货币种类
- **BTC 最新价格**: 比特币实时价格 (USD)
- **ETH 最新价格**: 以太坊实时价格 (USD)

### 中间区域 (2个面板)

#### 左侧: 各币种最新价格排行表
- 显示所有币种的最新价格
- 包含 24 小时成交量
- 显示更新时间
- 支持点击列标题排序
- 支持搜索和筛选

#### 右侧: 主流币种价格趋势图
- 显示 BTC、ETH、BNB 的价格走势
- 时间范围: 最近 1 小时
- 支持缩放和拖拽
- 悬停显示详细数值
- 图例显示最大值、最小值、最新值

### 底部区域 (2个面板)

#### 左侧: 24小时成交量分布
- 饼图展示各币种成交量占比
- 显示百分比和具体数值
- 点击图例可隐藏/显示某个币种

#### 右侧: 数据更新频率
- 柱状图显示每分钟的数据点数
- 时间范围: 最近 1 小时
- 可以看出数据采集的稳定性

---

## 功能说明

### 1. 自动刷新

面板每 10 秒自动刷新一次数据,无需手动刷新。

**调整刷新间隔**:
- 点击右上角的刷新按钮旁边的下拉菜单
- 可选择: 5s, 10s, 30s, 1m, 5m
- 或点击 "Off" 关闭自动刷新

### 2. 时间范围

默认显示最近 1 小时的数据。

**调整时间范围**:
- 点击右上角的时间选择器
- 可选择:
  - 最近 5 分钟
  - 最近 15 分钟
  - 最近 30 分钟
  - 最近 1 小时
  - 最近 3 小时
  - 最近 6 小时
  - 最近 12 小时
  - 最近 24 小时
  - 自定义时间范围

### 3. 图表交互

**折线图 (价格趋势)**:
- 鼠标悬停: 显示详细数值
- 拖拽选择: 放大某个时间段
- 双击: 重置缩放
- 点击图例: 隐藏/显示某条曲线

**表格 (价格排行)**:
- 点击列标题: 排序
- 搜索框: 筛选币种
- 滚动: 查看更多数据

**饼图 (成交量分布)**:
- 鼠标悬停: 显示详细数值和百分比
- 点击图例: 隐藏/显示某个币种

### 4. 导出功能

**导出面板**:
1. 点击面板标题
2. 选择 "More..." → "Export"
3. 可选择导出为:
   - PNG 图片
   - CSV 数据
   - JSON 配置

**导出整个仪表板**:
1. 点击右上角的分享按钮
2. 选择 "Export"
3. 可选择:
   - Export for sharing externally (JSON)
   - Save as file (JSON)

### 5. 分享功能

**生成分享链接**:
1. 点击右上角的分享按钮
2. 选择 "Link" 标签
3. 配置分享选项:
   - Lock time range: 锁定时间范围
   - Theme: 选择主题 (Dark/Light)
4. 点击 "Copy" 复制链接

**嵌入到其他系统**:
1. 点击右上角的分享按钮
2. 选择 "Embed" 标签
3. 复制 iframe 代码
4. 粘贴到网页中

---

## 常用操作

### 查看实时数据

1. 确保自动刷新已开启 (10s)
2. 时间范围设置为 "Last 1 hour"
3. 观察价格趋势图的实时变化

### 分析价格波动

1. 查看 "主流币种价格趋势图"
2. 拖拽选择某个时间段放大
3. 观察价格的涨跌幅度
4. 对比不同币种的走势

### 查找特定币种

1. 在 "各币种最新价格排行表" 中
2. 使用搜索框输入币种名称 (如 "BTC")
3. 查看该币种的详细信息

### 对比成交量

1. 查看 "24小时成交量分布" 饼图
2. 观察各币种的成交量占比
3. 点击图例隐藏某些币种,对比其他币种

### 监控数据质量

1. 查看 "数据更新频率" 柱状图
2. 观察每分钟的数据点数是否稳定
3. 如果某个时间段数据点数异常,可能存在数据采集问题

---

## 故障排查

### 面板显示 "No Data"

**可能原因**:
1. Doris 数据库中没有数据
2. 时间范围内没有数据
3. 数据源连接失败

**解决方法**:
```bash
# 1. 检查 Doris 数据
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "
  SELECT COUNT(*) FROM crypto_dw.dwd_crypto_ticker_detail"

# 2. 检查 Grafana 到 Doris 的连接
docker exec grafana ping -c 3 doris-fe

# 3. 重启 Grafana
docker-compose -f docker-compose-monitoring.yml restart grafana
```

### 时间显示不正确

**可能原因**: 时区配置错误

**解决方法**:
1. 检查 Doris 时区: `SHOW VARIABLES LIKE 'time_zone'`
2. 应该显示: `Asia/Shanghai`
3. 如果不正确,执行: `SET GLOBAL time_zone = 'Asia/Shanghai'`

### 查询速度慢

**可能原因**:
1. 数据量太大
2. 时间范围太长
3. 网络延迟

**优化方法**:
1. 减少时间范围 (从 24h 改为 1h)
2. 关闭不需要的面板
3. 增加刷新间隔 (从 10s 改为 30s)

### 面板无法访问

**可能原因**: Grafana 容器未运行

**解决方法**:
```bash
# 检查容器状态
docker ps --filter "name=grafana"

# 启动 Grafana
docker-compose -f docker-compose-monitoring.yml up -d grafana

# 查看日志
docker logs grafana
```

---

## 高级功能

### 1. 添加告警规则

1. 点击面板标题
2. 选择 "Edit"
3. 切换到 "Alert" 标签
4. 点击 "Create alert rule from this panel"
5. 配置告警条件:
   - 当 BTC 价格 > $80,000 时告警
   - 当数据更新延迟 > 5 分钟时告警
6. 配置通知渠道 (Email, Slack, 钉钉等)

### 2. 创建自定义面板

1. 点击右上角的 "Add panel" 按钮
2. 选择 "Add a new panel"
3. 选择数据源: "Doris"
4. 编写 SQL 查询
5. 选择可视化类型
6. 配置面板选项
7. 点击 "Apply" 保存

### 3. 修改现有面板

1. 点击面板标题
2. 选择 "Edit"
3. 修改查询、可视化类型、配置等
4. 点击 "Apply" 保存

### 4. 导出数据到 CSV

1. 点击面板标题
2. 选择 "Inspect" → "Data"
3. 点击 "Download CSV"
4. 数据将下载到本地

---

## 最佳实践

### 1. 日常监控

- 保持自动刷新开启 (10s)
- 时间范围设置为 "Last 1 hour"
- 关注价格趋势图的异常波动
- 定期检查数据更新频率

### 2. 数据分析

- 调整时间范围到 "Last 24 hours"
- 使用表格排序功能找出涨跌幅最大的币种
- 对比不同币种的成交量分布
- 导出数据到 CSV 进行深度分析

### 3. 性能优化

- 不需要时关闭自动刷新
- 减少显示的时间范围
- 关闭不需要的面板
- 定期清理旧数据

### 4. 团队协作

- 创建不同的仪表板给不同角色
- 使用分享链接分享给团队成员
- 配置告警规则通知相关人员
- 定期导出报告进行汇报

---

## 相关资源

### 文档
- [Grafana 官方文档](https://grafana.com/docs/)
- [Doris 官方文档](https://doris.apache.org/docs/)
- [项目部署文档](./202603220420-Grafana配置-添加Doris数据源和加密货币监控面板.md)

### 测试脚本
```bash
# 测试 Grafana 配置
bash test/test-grafana-crypto-dashboard.sh

# 测试 Doris 连接
docker exec grafana ping -c 3 doris-fe

# 查看 Grafana 日志
docker logs grafana
```

### 配置文件
- `monitoring/grafana/provisioning/datasources/doris-mysql.yml` - 数据源配置
- `monitoring/grafana/dashboards/crypto-data-dashboard.json` - 面板配置
- `docker-compose-monitoring.yml` - Docker Compose 配置

---

## 技术支持

如果遇到问题,可以:

1. 查看日志: `docker logs grafana`
2. 运行测试脚本: `bash test/test-grafana-crypto-dashboard.sh`
3. 查看文档: `docs/202603220420-Grafana配置-添加Doris数据源和加密货币监控面板.md`
4. 重启服务: `docker-compose -f docker-compose-monitoring.yml restart grafana`

---

## 更新日志

- 2026-03-22: 创建加密货币监控面板,包含 8 个可视化图表
- 2026-03-22: 配置 Doris MySQL 数据源
- 2026-03-22: 修复 Grafana 网络配置,连接 Doris 网络
