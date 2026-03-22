# Superset 网络 - 连接 Doris 网络解决主机名解析

**时间**: 2026-03-22 02:55  
**问题**: Superset 无法解析 `doris-fe` 主机名

## 问题描述

在 Superset 中尝试连接 Doris 数据库时,提示 "The hostname provided can't be resolved",无法解析 `doris-fe` 主机名。

## 原因分析

Superset 容器和 Doris 容器不在同一个 Docker 网络中:
- Superset: `data-warehouse-net` 网络
- Doris: `data-warehouse_doris-net` 网络

Docker 容器只能解析同一网络中的容器主机名。

## 解决方案

将 Superset 容器连接到 Doris 网络:

```bash
docker network connect data-warehouse_doris-net superset
```

## 验证

```bash
# 测试 DNS 解析
docker exec superset python -c "import socket; print(socket.gethostbyname('doris-fe'))"

# 应该返回 Doris FE 的 IP 地址,如: 172.25.0.2
```

## 连接信息

现在可以在 Superset 中连接 Doris:

**选择数据库类型**: MySQL (Doris 兼容 MySQL 协议)

**连接参数**:
- Host: `doris-fe`
- Port: `9030`
- Database: `crypto_dw`
- Username: `root`
- Password: (留空)
- Display Name: `Doris`

## 网络架构

```
data-warehouse-net (172.23.0.0/16)
├── superset (172.23.0.4)
├── superset-db (172.23.0.3)
└── superset-redis (172.23.0.2)

data-warehouse_doris-net (172.25.0.0/16)
├── doris-fe (172.25.0.2)
├── doris-be (172.25.0.3)
└── superset (新增) ← 跨网络连接
```

## 永久解决方案

修改 `docker-compose-superset.yml`,添加 Doris 网络:

```yaml
services:
  superset:
    networks:
      - data-warehouse-net
      - doris-net  # 添加 Doris 网络

networks:
  data-warehouse-net:
    external: true
  doris-net:
    external: true
    name: data-warehouse_doris-net
```

## 注意事项

1. 手动连接的网络在容器重启后会保留
2. 如果重新创建容器,需要重新连接网络
3. 建议在 docker-compose 配置中永久添加网络配置

## 相关命令

```bash
# 查看容器网络
docker inspect superset | grep -A 10 Networks

# 查看网络中的容器
docker network inspect data-warehouse_doris-net

# 断开网络连接
docker network disconnect data-warehouse_doris-net superset
```
