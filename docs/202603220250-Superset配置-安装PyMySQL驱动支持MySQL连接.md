# Superset 配置 - 安装 PyMySQL 驱动支持 MySQL 连接

**时间**: 2026-03-22 02:50  
**状态**: ✅ 已完成

## 问题描述

Superset 默认镜像不包含 MySQL 驱动,在连接数据库时无法选择 MySQL 选项。

## 解决方案

在运行的 Superset 容器中安装 PyMySQL 驱动。

### 步骤

1. **安装 PyMySQL**
   ```bash
   docker exec -u root superset pip install pymysql
   ```

2. **重启容器**
   ```bash
   docker restart superset
   ```

3. **验证**
   - 刷新 Superset 页面
   - 点击 "+" → "Data" → "Connect database"
   - 在数据库列表中应该可以看到 MySQL 选项

## 为什么选择 PyMySQL

- **mysqlclient**: 需要系统级依赖(pkg-config, libmysqlclient-dev),安装复杂
- **PyMySQL**: 纯 Python 实现,无需系统依赖,安装简单

## 连接 Doris

Doris 兼容 MySQL 协议,可以使用 MySQL 驱动连接:

1. 选择 "MySQL" 数据库类型
2. 填写连接信息:
   ```
   Host: doris-fe
   Port: 9030
   Database: crypto_dw
   Username: root
   Password: (留空)
   ```

## 注意事项

1. 容器重建后需要重新安装驱动
2. 建议创建自定义镜像预装驱动
3. PyMySQL 性能略低于 mysqlclient,但对于 BI 工具足够

## 持久化方案

如需持久化驱动安装,创建自定义 Dockerfile:

```dockerfile
FROM apache/superset:latest
USER root
RUN pip install pymysql
USER superset
```

然后修改 docker-compose-superset.yml:

```yaml
superset:
  build:
    context: .
    dockerfile: Dockerfile.superset
  # ...其他配置
```

## 相关文件

- `docker-compose-superset.yml` - Superset 配置
- `manage-superset.sh` - 管理脚本
