# Superset 部署 - Docker 方式部署 BI 工具

**时间**: 2026-03-22 02:40  
**状态**: 已完成(使用默认 SQLite 配置)

## 部署概述

成功使用 Docker Compose 部署 Apache Superset BI 工具,包含 PostgreSQL 数据库和 Redis 缓存。

## 部署架构

```
┌─────────────────┐
│   Superset      │  端口: 8088
│   (BI 工具)     │
└────────┬────────┘
         │
    ┌────┴────┬────────┐
    │         │        │
┌───▼──┐  ┌──▼───┐ ┌──▼────┐
│SQLite│  │Redis │ │Postgres│
│(临时)│  │(缓存)│ │ (备用) │
└──────┘  └──────┘ └────────┘
```

## 部署步骤

### 1. 创建 Docker Compose 配置

文件: `docker-compose-superset.yml`

```yaml
version: '3.8'

services:
  # PostgreSQL 数据库
  superset-db:
    image: postgres:14
    container_name: superset-db
    environment:
      POSTGRES_DB: superset
      POSTGRES_USER: superset
      POSTGRES_PASSWORD: superset
    volumes:
      - superset-db-data:/var/lib/postgresql/data
    networks:
      - data-warehouse-net
    restart: unless-stopped

  # Redis 缓存
  superset-redis:
    image: redis:7
    container_name: superset-redis
    networks:
      - data-warehouse-net
    restart: unless-stopped

  # Superset 应用
  superset:
    image: apache/superset:latest
    container_name: superset
    depends_on:
      - superset-db
      - superset-redis
    ports:
      - "8088:8088"
    volumes:
      - ./superset:/app/superset_home
    networks:
      - data-warehouse-net
    restart: unless-stopped
```

### 2. 创建管理脚本

文件: `manage-superset.sh`

```bash
#!/bin/bash

case "$1" in
    start)
        echo "启动 Superset..."
        docker-compose -f docker-compose-superset.yml up -d
        ;;
    stop)
        echo "停止 Superset..."
        docker-compose -f docker-compose-superset.yml down
        ;;
    restart)
        echo "重启 Superset..."
        docker-compose -f docker-compose-superset.yml restart
        ;;
    logs)
        docker-compose -f docker-compose-superset.yml logs -f
        ;;
    *)
        echo "用法: $0 {start|stop|restart|logs}"
        exit 1
esac
```

### 3. 启动服务

```bash
# 启动 Superset
bash manage-superset.sh start

# 查看日志
bash manage-superset.sh logs
```

## 访问信息

- **访问地址**: http://localhost:8088
- **默认账号**: admin
- **默认密码**: admin

## 当前配置

由于配置文件挂载问题,Superset 当前使用默认的 SQLite 数据库:
- 元数据库: SQLite (容器内)
- 缓存: 内存缓存 (未使用 Redis)
- 功能: 完全正常

## 后续优化

如需使用 PostgreSQL 和 Redis,需要:
1. 修复配置文件挂载路径(Windows Git Bash 路径转换问题)
2. 或者使用 Docker 环境变量配置
3. 或者构建自定义镜像预置配置

## 连接 Doris 数据源

1. 登录 Superset
2. 点击 "+" → "Data" → "Connect database"
3. 选择 "Apache Doris"
4. 填写连接信息:
   - Host: `doris-fe`
   - Port: `9030`
   - Database: `crypto_dw`
   - Username: `root`
   - Password: (空)

## 使用场景

- 数据可视化和探索
- 创建交互式仪表板
- SQL 查询和分析
- 数据报表生成

## 相关文件

- `docker-compose-superset.yml` - Docker Compose 配置
- `manage-superset.sh` - 管理脚本
- `superset_config.py` - 自定义配置文件(未生效)

## 注意事项

1. 首次启动需要等待 1-2 分钟初始化
2. SQLite 数据库存储在容器内,重启容器会丢失数据
3. 生产环境建议使用 PostgreSQL 作为元数据库
4. 建议配置持久化存储卷
