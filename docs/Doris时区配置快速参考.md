# Doris 时区配置快速参考

## 问题症状

- ❌ 时间显示相差 8 小时
- ❌ Superset 查询结果时间不对
- ❌ IDEA 数据库工具时间不对
- ❌ 其他 MySQL 客户端时间不对

## 快速解决方案

### 1. Doris 服务器时区配置 ✅

```bash
# 设置全局时区
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SET GLOBAL time_zone = 'Asia/Shanghai';"

# 验证时区
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW VARIABLES LIKE 'time_zone'; SELECT NOW();"
```

### 2. Docker 容器时区配置 ✅

在 `docker-compose-doris.yml` 中添加:
```yaml
services:
  doris-fe:
    environment:
      - TZ=Asia/Shanghai
  
  doris-be:
    environment:
      - TZ=Asia/Shanghai
```

重启容器:
```bash
docker-compose -f docker-compose-doris.yml restart
```

### 3. JDBC 连接时区配置 ✅

**IDEA / DataGrip / MySQL Workbench**:
```
jdbc:mysql://127.0.0.1:9030/crypto_dw?serverTimezone=Asia/Shanghai&useUnicode=true&characterEncoding=utf8
```

**DBeaver**:
- 右键数据源 → "Edit Connection"
- "Driver properties" → 添加 `serverTimezone` = `Asia/Shanghai`

**Navicat**:
- "高级" 选项 → 添加 `serverTimezone=Asia/Shanghai`

**Superset**:
```
mysql+pymysql://root@doris-fe:9030/crypto_dw?charset=utf8mb4&serverTimezone=Asia/Shanghai
```

## 验证命令

```bash
# 快速验证脚本
bash test/verify-doris-timezone.sh
```

或手动验证:
```sql
-- 1. 检查时区设置
SHOW VARIABLES LIKE 'time_zone';

-- 2. 检查当前时间
SELECT NOW() AS '当前时间';

-- 3. 检查数据时间
SELECT 
    MIN(FROM_UNIXTIME(timestamp / 1000)) AS 最早时间,
    MAX(FROM_UNIXTIME(timestamp / 1000)) AS 最新时间
FROM crypto_dw.dwd_crypto_ticker_detail;
```

## 预期结果

```
Variable_name   Value
time_zone       Asia/Shanghai

当前时间: 2026-03-22 04:00:00 (东八区时间)

最早时间: 2026-03-18 21:27:02 (东八区时间)
最新时间: 2026-03-22 00:30:04 (东八区时间)
```

## 时区配置三层架构

```
┌─────────────────────────────────────────┐
│  1. Doris 服务器时区                     │
│     SET GLOBAL time_zone = 'Asia/Shanghai' │
│     ✅ 控制数据库内部时间处理             │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  2. Docker 容器时区                      │
│     TZ=Asia/Shanghai                    │
│     ✅ 控制容器系统时间                   │
└─────────────────────────────────────────┘
                    ↓
┌─────────────────────────────────────────┐
│  3. JDBC 连接时区                        │
│     serverTimezone=Asia/Shanghai        │
│     ✅ 控制客户端时间显示                 │
└─────────────────────────────────────────┘
```

## 常见错误

### 错误 1: 只配置了服务器时区

**症状**: 命令行查询正确,IDEA 查询错误

**原因**: JDBC 连接没有配置时区

**解决**: 在 JDBC URL 中添加 `serverTimezone=Asia/Shanghai`

### 错误 2: 只配置了 JDBC 时区

**症状**: IDEA 查询正确,Superset 查询错误

**原因**: Doris 服务器时区未配置

**解决**: 执行 `SET GLOBAL time_zone = 'Asia/Shanghai'`

### 错误 3: 重启后时区丢失

**症状**: 重启容器后时区恢复为 UTC

**原因**: Docker Compose 配置未添加 TZ 环境变量

**解决**: 在 docker-compose.yml 中添加 `TZ=Asia/Shanghai`

## 相关文档

- [202603220345-Doris时区-修复时区配置显示中国时间.md](./202603220345-Doris时区-修复时区配置显示中国时间.md)
- [202603220400-IDEA配置-修复数据库连接时区显示问题.md](./202603220400-IDEA配置-修复数据库连接时区显示问题.md)

## 一键配置脚本

```bash
#!/bin/bash
# 一键配置 Doris 时区

echo "配置 Doris 时区为 Asia/Shanghai..."

# 1. 设置 Doris 服务器时区
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SET GLOBAL time_zone = 'Asia/Shanghai';"

# 2. 验证配置
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW VARIABLES LIKE 'time_zone'; SELECT NOW();"

echo ""
echo "✅ 时区配置完成!"
echo ""
echo "注意:"
echo "1. 请在 docker-compose-doris.yml 中添加 TZ=Asia/Shanghai"
echo "2. 请在 JDBC URL 中添加 serverTimezone=Asia/Shanghai"
echo "3. 重启容器: docker-compose -f docker-compose-doris.yml restart"
```

保存为 `configure-doris-timezone.sh`,然后执行:
```bash
bash configure-doris-timezone.sh
```
