# Superset 配置 - 使用本地 MySQL 和 Redis 遇到依赖问题

**时间**: 2026-03-22 02:30  
**问题**: 尝试将 Superset 配置为使用本地 MySQL 和 Redis,但遇到 pymysql 依赖安装问题

## 问题描述

用户希望将 Superset 的元数据库从 Docker PostgreSQL 改为本地 MySQL,并使用本地 Redis 作为缓存。

## 尝试的方案

### 方案 1: 环境变量配置
- 尝试通过环境变量 `DATABASE_*` 配置数据库连接
- 结果: Superset 不识别这些环境变量,仍然使用默认的 SQLite

### 方案 2: 自定义配置文件
- 创建 `superset_config.py` 配置文件
- 设置 `SQLALCHEMY_DATABASE_URI` 为 MySQL 连接字符串
- 结果: 配置文件被加载,但缺少 pymysql 驱动

### 方案 3: 在启动命令中安装依赖
- 在 docker-compose 的 command 中添加 `pip install pymysql`
- 结果: pip 安装到用户目录,虚拟环境无法找到

### 方案 4: 自定义 Dockerfile
- 创建 `Dockerfile.superset-mysql` 安装 pymysql
- 尝试多种安装方式:
  - `pip install` - 安装到系统 Python
  - `/app/.venv/bin/pip install` - 虚拟环境 pip 不存在
  - `python -m pip install` - 虚拟环境 Python 没有 pip 模块
  - `apt-get install python3-pip` - 系统 Python 有 pip,但虚拟环境仍然没有

## 根本原因

Superset 使用 poetry 管理虚拟环境,虚拟环境中没有 pip 模块,无法直接安装额外的 Python 包。

## 建议方案

1. **使用 PostgreSQL (推荐)**
   - Superset 默认支持 PostgreSQL
   - 不需要额外安装驱动
   - 性能和稳定性更好

2. **使用官方 MySQL 镜像**
   - Apache Superset 可能有官方的 MySQL 支持镜像
   - 查看官方文档获取正确的镜像标签

3. **使用 Docker Compose 覆盖**
   - 保持使用 Docker PostgreSQL 和 Redis
   - 简化部署和维护

## 经验教训

1. Superset 的 Docker 镜像使用 poetry 管理依赖,不适合运行时安装额外包
2. 修改 Superset 数据库驱动需要在构建时处理,而不是运行时
3. 对于 BI 工具,使用官方推荐的数据库配置更稳定

## 相关文件

- `docker-compose-superset.yml` - Superset Docker Compose 配置
- `superset_config.py` - Superset 自定义配置文件
- `Dockerfile.superset-mysql` - 尝试的自定义 Dockerfile
- `manage-superset.sh` - Superset 管理脚本
