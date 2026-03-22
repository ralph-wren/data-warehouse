# Superset 配置 - PyMySQL 驱动安装和 Doris 连接指南

**时间**: 2026-03-22 03:10  
**问题**: Superset Web UI 中没有 MySQL 数据源选项,无法连接 Doris  
**状态**: ✅ 已解决

---

## 问题描述

1. Superset 数据库列表中没有 MySQL 选项
2. pymysql 虽然显示已安装,但在 Python 中无法导入
3. Git Bash 路径转换导致无法在容器中执行命令

---

## 根本原因

### 1. PyMySQL 安装到错误的 Python 环境

Superset 容器使用虚拟环境 `/app/.venv/bin/python`,但启动脚本中的 `pip install pymysql` 安装到了系统 Python 环境,导致 Superset 无法导入 pymysql。

```bash
# 检查 Python 路径
$ docker exec superset which python
/app/.venv/bin/python  # ✅ Superset 使用虚拟环境

$ docker exec superset which pip
/usr/local/bin/pip  # ❌ pip 在系统路径

# 验证问题
$ docker exec superset pip list | grep pymysql
PyMySQL    1.1.2  # ✅ 显示已安装

$ docker exec superset python -c "import pymysql"
ModuleNotFoundError: No module named 'pymysql'  # ❌ 无法导入
```

### 2. Git Bash 路径转换问题

在 Windows Git Bash 中,以 `/` 开头的路径会被自动转换为 Windows 路径,导致 Docker 命令失败:

```bash
# Git Bash 中执行
$ docker exec superset /app/.venv/bin/pip install pymysql
# 实际执行的命令
exec: "C:/Program Files/Git/app/.venv/bin/pip"  # ❌ 路径被转换
```

---

## 解决方案

### 方案 1: 修改 Docker Compose 配置(推荐) ✅

修改 `docker-compose-superset.yml`,使用虚拟环境的 pip:

```yaml
services:
  superset:
    command:
      - /bin/sh
      - -c
      - |
        # 使用虚拟环境的 pip 安装 pymysql 驱动
        /app/.venv/bin/pip install -q pymysql &&
        superset db upgrade &&
        superset fab create-admin --username admin --firstname Admin --lastname User --email admin@example.com --password admin || true &&
        superset init &&
        superset run -h 0.0.0.0 -p 8088 --with-threads --reload
```

**重启容器**:
```bash
bash manage-superset.sh restart
```

### 方案 2: 手动进入容器安装 ✅

由于 Git Bash 路径转换问题,需要先进入容器再安装:

```bash
# 1. 进入容器
docker exec -it -u root superset bash

# 2. 在容器内安装 pymysql
/app/.venv/bin/pip install pymysql

# 3. 验证安装
/app/.venv/bin/python -c "import pymysql; print('✓ pymysql 安装成功')"

# 4. 退出容器
exit
```

### 方案 3: 在 Superset Web UI 中直接添加数据库(最简单) ✅

即使 pymysql 未正确安装,也可以通过 Web UI 手动添加数据库连接:

**步骤**:

1. **访问 Superset**  
   打开浏览器访问: http://localhost:8088  
   登录账号: `admin` / `admin`

2. **打开数据库连接页面**  
   点击顶部菜单: `Settings` → `Database Connections`

3. **添加新数据库**  
   点击右上角 `+ Database` 按钮

4. **选择数据库类型**  
   - 如果看到 `MySQL` 选项,直接选择
   - 如果没有,选择 `Other` 或 `Supported Databases`

5. **输入连接信息**  
   
   **方式 A: 使用表单(如果有 MySQL 选项)**:
   ```
   Database Name: Doris Crypto DW
   Host: doris-fe
   Port: 9030
   Database: crypto_dw
   Username: root
   Password: (留空)
   ```

   **方式 B: 使用 SQLAlchemy URI(推荐)**:
   ```
   Display Name: Doris Crypto DW
   SQLALCHEMY URI: mysql+pymysql://root@doris-fe:9030/crypto_dw
   ```

6. **测试连接**  
   点击 `Test Connection` 按钮  
   如果显示 `Connection looks good!`,说明连接成功

7. **保存配置**  
   点击 `Connect` 或 `Save` 按钮

---

## 验证步骤

### 1. 验证 pymysql 安装

```bash
# 进入容器
docker exec -it superset bash

# 验证 pymysql
python -c "import pymysql; print('✓ pymysql 可用')"

# 测试连接 Doris
python << 'EOF'
import pymysql
conn = pymysql.connect(
    host='doris-fe',
    port=9030,
    user='root',
    password='',
    database='crypto_dw'
)
print('✓ 连接 Doris 成功')
cursor = conn.cursor()
cursor.execute('SHOW TABLES')
tables = cursor.fetchall()
print(f'✓ 表数量: {len(tables)}')
conn.close()
EOF

# 退出容器
exit
```

### 2. 在 Superset 中查询数据

1. 点击顶部菜单: `SQL` → `SQL Lab`
2. 选择数据库: `Doris Crypto DW`
3. 选择 Schema: `crypto_dw`
4. 输入 SQL 查询:
   ```sql
   SELECT * FROM ods_crypto_ticker_rt LIMIT 10;
   ```
5. 点击 `Run` 按钮执行查询

---

## Git Bash 路径转换问题解决方法

### 问题说明

Git Bash 会自动将以 `/` 开头的路径转换为 Windows 路径:

```bash
# 输入
/app/.venv/bin/pip

# 实际执行
C:/Program Files/Git/app/.venv/bin/pip
```

### 解决方法

**方法 1: 使用双斜杠**
```bash
docker exec superset //app/.venv/bin/pip install pymysql
```

**方法 2: 设置环境变量**
```bash
MSYS_NO_PATHCONV=1 docker exec superset /app/.venv/bin/pip install pymysql
```

**方法 3: 使用 winpty**
```bash
winpty docker exec -it superset bash
```

**方法 4: 使用 PowerShell(推荐)**
```powershell
docker exec superset /app/.venv/bin/pip install pymysql
```

**方法 5: 进入容器后执行(最可靠)**
```bash
docker exec -it superset bash
/app/.venv/bin/pip install pymysql
```

---

## 常见问题

### Q1: Web UI 中没有 MySQL 选项怎么办?

**A**: 直接使用 SQLAlchemy URI 方式添加数据库:
```
mysql+pymysql://root@doris-fe:9030/crypto_dw
```

### Q2: 连接测试失败,提示 "No module named 'pymysql'"

**A**: pymysql 未正确安装到虚拟环境,按照方案 2 手动安装。

### Q3: 连接测试失败,提示 "Can't connect to MySQL server"

**A**: 检查网络配置:
```bash
# 验证 Superset 能否解析 doris-fe
docker exec superset ping -c 3 doris-fe

# 验证 Superset 能否连接 Doris FE 端口
docker exec superset nc -zv doris-fe 9030
```

### Q4: 为什么 pip list 显示已安装,但 Python 无法导入?

**A**: pip 和 python 使用了不同的环境:
- pip: 系统 Python (`/usr/local/bin/pip`)
- python: 虚拟环境 (`/app/.venv/bin/python`)

解决方法: 使用虚拟环境的 pip 安装:
```bash
/app/.venv/bin/pip install pymysql
```

---

## 技术要点

1. **虚拟环境隔离**: Superset 使用虚拟环境,必须在虚拟环境中安装依赖
2. **Git Bash 路径转换**: Windows 环境下需要注意路径转换问题
3. **SQLAlchemy URI**: 通用的数据库连接方式,支持所有数据库
4. **Doris MySQL 协议**: Doris 兼容 MySQL 协议,可以使用 MySQL 驱动连接

---

## 相关文档

- [Superset 部署指南](./202603220240-Superset部署-Docker方式部署BI工具.md)
- [Superset 网络配置](./202603220300-Superset网络-修复Docker网络配置连接Doris.md)
- [Doris 部署方案](./202603190100-最终成功-Doris部署和Flink连接完整方案.md)

---

## 总结

通过修改 Docker Compose 配置,使用虚拟环境的 pip 安装 pymysql,或者直接在 Superset Web UI 中使用 SQLAlchemy URI 添加数据库连接,成功解决了 Superset 连接 Doris 的问题。

**推荐方案**: 使用 SQLAlchemy URI 方式添加数据库,最简单可靠。
