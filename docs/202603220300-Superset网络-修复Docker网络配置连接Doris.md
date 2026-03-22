# Superset 网络配置 - 修复 Docker 网络连接 Doris

**时间**: 2026-03-22 03:00  
**问题**: Superset 无法连接到 Doris FE,提示找不到 MySQL 数据库选项  
**状态**: ✅ 已解决

---

## 问题描述

1. Superset 数据库列表中没有 MySQL 选项
2. Superset 无法解析 `doris-fe` 主机名
3. 网络配置不正确,使用了不存在的 `data-warehouse-net` 网络

---

## 根本原因

### 1. 网络配置错误
- 原配置使用了 `data-warehouse-net` 网络,但这个网络不存在
- Superset 内部服务(DB、Redis)和应用混用了外部网络
- 没有正确引用 Doris 的网络名称

### 2. 缺少 MySQL 驱动
- Superset 默认镜像不包含 MySQL/PyMySQL 驱动
- 需要在启动时安装 `pymysql` 包

---

## 解决方案

### 1. 修复网络配置

**修改 `docker-compose-superset.yml`**:

```yaml
networks:
  superset-net:
    driver: bridge  # Superset 内部网络
  doris-net:
    external: true
    name: data-warehouse_doris-net  # 引用 Doris 的网络
```

**服务网络分配**:
- `superset-db` 和 `superset-redis`: 只连接 `superset-net`(内部通信)
- `superset` 应用: 连接 `superset-net` 和 `doris-net`(需要访问 Doris)

### 2. 安装 PyMySQL 驱动

在 Superset 启动命令中添加:
```bash
pip install -q pymysql
```

---

## 配置文件对比

### 修改前
```yaml
networks:
  data-warehouse-net:
    external: true  # ❌ 这个网络不存在
  doris-net:
    external: true
    name: data-warehouse_doris-net
```

### 修改后
```yaml
networks:
  superset-net:
    driver: bridge  # ✅ Superset 内部网络
  doris-net:
    external: true
    name: data-warehouse_doris-net  # ✅ 引用 Doris 网络
```

---

## 验证步骤

### 1. 重启 Superset 服务
```bash
bash manage-superset.sh restart
```

### 2. 验证网络连接
```bash
# 测试 DNS 解析
docker exec superset python -c "import socket; print('doris-fe IP:', socket.gethostbyname('doris-fe'))"

# 输出: doris-fe IP: 172.25.0.2
```

### 3. 检查服务状态
```bash
bash manage-superset.sh status
```

---

## 连接 Doris 配置

在 Superset 中添加数据库连接:

1. 访问 http://localhost:8088
2. 登录账号: `admin` / `admin`
3. 点击 "+" → "Data" → "Connect database"
4. 选择 "MySQL" (安装 pymysql 后可见)
5. 填写连接信息:
   - Host: `doris-fe`
   - Port: `9030`
   - Database: `crypto_dw`
   - Username: `root`
   - Password: (留空)

**连接字符串**:
```
mysql+pymysql://root@doris-fe:9030/crypto_dw
```

---

## 网络架构说明

```
┌─────────────────────────────────────────┐
│         Superset 内部网络               │
│         (superset-net)                  │
│                                         │
│  ┌──────────┐  ┌──────────┐           │
│  │ Postgres │  │  Redis   │           │
│  └──────────┘  └──────────┘           │
│         ↑            ↑                  │
│         └────────────┘                  │
│                │                        │
│         ┌──────────┐                   │
│         │ Superset │ ←─────────────────┼─── 宿主机访问
│         │   App    │                   │    localhost:8088
│         └──────────┘                   │
│                │                        │
└────────────────┼────────────────────────┘
                 │
                 │ (连接到 doris-net)
                 ↓
┌─────────────────────────────────────────┐
│         Doris 网络                      │
│         (data-warehouse_doris-net)      │
│                                         │
│  ┌──────────┐  ┌──────────┐           │
│  │ Doris FE │  │ Doris BE │           │
│  │172.25.0.2│  │172.25.0.3│           │
│  └──────────┘  └──────────┘           │
└─────────────────────────────────────────┘
```

---

## 关键要点

1. **网络隔离**: Superset 内部服务使用独立网络,只有应用容器连接外部网络
2. **外部网络引用**: 使用 `external: true` 和正确的网络名称引用 Doris 网络
3. **驱动安装**: 在启动命令中安装必要的数据库驱动
4. **DNS 解析**: 容器通过 Docker 网络自动解析主机名

---

## 相关文档

- [Superset 部署指南](./202603220240-Superset部署-Docker方式部署BI工具.md)
- [Superset PyMySQL 驱动安装](./202603220250-Superset配置-安装PyMySQL驱动支持MySQL连接.md)
- [Doris 网络配置](./202603190050-Doris部署-桥接网络模式最终方案.md)
