# StreamPark 配置 - Flink 路径挂载和配置

## 问题描述

在 StreamPark Web UI 中配置 Flink Home 时，需要理解路径映射关系。

## 解决方案

### 1. Docker Compose 配置

修改 `docker-compose-streampark.yml`，挂载主机的 Flink 到容器：

```yaml
volumes:
  # 挂载主机的 Flink 到容器（只读）
  - /e/DataFiles/flink-1.17.0:/opt/flink:ro
  
  # StreamPark 数据持久化
  - ./streampark-data:/streampark/data
```

**关键点**：
- **左边**：主机路径（Windows E 盘：`E:\DataFiles\flink-1.17.0`）
- **右边**：容器内路径（`/opt/flink`）
- `:ro`：只读挂载，防止容器修改主机文件

### 2. 路径映射关系

| 主机路径（Windows） | Git Bash 路径 | 容器内路径 | 说明 |
|-------------------|--------------|-----------|------|
| `E:\DataFiles\flink-1.17.0` | `/e/DataFiles/flink-1.17.0` | `/opt/flink` | Flink 安装目录 |
| `.\streampark-data` | `./streampark-data` | `/streampark/data` | StreamPark 数据 |

**注意**：
- Windows 盘符在 Git Bash 中的格式：`C:` → `/c/`，`E:` → `/e/`
- 反斜杠 `\` 要改成正斜杠 `/`

### 3. 重启 StreamPark

```bash
# 停止服务
docker-compose -f docker-compose-streampark.yml down

# 启动服务
docker-compose -f docker-compose-streampark.yml up -d

# 等待服务启动（约 20 秒）
sleep 20

# 检查容器状态
docker ps | grep streampark
```

### 4. 验证 Flink 挂载

```bash
# 检查容器内的 Flink 目录
docker exec streampark ls -la /opt/flink/

# 检查 Flink 版本
docker exec streampark /opt/flink/bin/flink --version
```

**预期输出**：
```
Version: 1.17.0, Commit ID: 69ecda0
```

### 5. 在 StreamPark Web UI 中配置

#### 步骤 1：登录 StreamPark

访问：http://localhost:10000
- 账号：`admin`
- 密码：`streampark`

#### 步骤 2：配置 Flink Home

1. 点击左侧菜单"设置中心"
2. 选择"Flink Home"
3. 点击"添加"按钮
4. 填写配置：
   - **Flink Version**: `1.17.0`
   - **Flink Home**: `/opt/flink`（容器内路径，不是主机路径！）
   - **描述**: `Flink 1.17.0 for development`
5. 点击"验证"按钮
6. 如果显示绿色勾号，点击"确定"保存

**重要提示**：
- 填写的是**容器内的路径** `/opt/flink`
- 不是主机路径 `E:\DataFiles\flink-1.17.0`
- 不是 Git Bash 路径 `/e/DataFiles/flink-1.17.0`

## 常见错误

### 错误 1：填写了主机路径

❌ 错误：
```
Flink Home: E:\DataFiles\flink-1.17.0
Flink Home: /e/DataFiles/flink-1.17.0
```

✅ 正确：
```
Flink Home: /opt/flink
```

### 错误 2：容器一直重启

**原因**：挂载路径覆盖了 StreamPark 的程序目录

❌ 错误配置：
```yaml
volumes:
  - ./streampark-data:/streampark  # 覆盖了 StreamPark 程序目录
```

✅ 正确配置：
```yaml
volumes:
  - ./streampark-data:/streampark/data  # 只挂载数据目录
```

### 错误 3：Flink 目录不存在

**检查方法**：
```bash
# 检查主机路径
ls -la /e/DataFiles/flink-1.17.0/

# 检查容器内路径
docker exec streampark ls -la /opt/flink/
```

**解决方案**：
- 确保主机路径正确
- 确保 Flink 目录包含 `bin`、`lib`、`conf` 等标准目录
- 重启 StreamPark 容器

### 错误 4：只读文件系统错误

**现象**：
```
java.io.FileNotFoundException: /opt/flink/log/flink--client-streampark.log (Read-only file system)
```

**说明**：
- 这是因为 Flink 目录以只读方式挂载（`:ro`）
- Flink 尝试写入日志文件失败
- **不影响功能**，可以忽略

**如果需要写入权限**（不推荐）：
```yaml
volumes:
  - /e/DataFiles/flink-1.17.0:/opt/flink  # 移除 :ro
```

## 验证配置成功

### 1. 检查 Flink Home 配置

在 StreamPark Web UI 中：
1. 进入"设置中心" -> "Flink Home"
2. 应该看到刚才添加的 Flink 1.17.0
3. 状态显示为"可用"（绿色勾号）

### 2. 创建测试作业

1. 进入"应用管理"
2. 点击"添加应用"
3. 选择 Flink Version：`1.17.0`
4. 如果可以选择，说明配置成功

## 技术要点

### Docker Volume 挂载

```yaml
volumes:
  - <主机路径>:<容器路径>:<选项>
```

**选项**：
- `ro`：只读（read-only）
- `rw`：读写（read-write，默认）

### Windows 路径转换

| Windows | Git Bash | Docker |
|---------|----------|--------|
| `C:\Users\ralph` | `/c/Users/ralph` | `/c/Users/ralph` |
| `E:\DataFiles` | `/e/DataFiles` | `/e/DataFiles` |
| `.\streampark-data` | `./streampark-data` | `./streampark-data` |

### 容器内路径

StreamPark 容器内的标准路径：
- `/streampark`：StreamPark 程序目录
- `/streampark/data`：StreamPark 数据目录（可挂载）
- `/opt/flink`：Flink 安装目录（可挂载）
- `/tmp`：临时目录

## 最佳实践

### 开发环境

使用只读挂载，防止容器修改主机文件：

```yaml
volumes:
  - /e/DataFiles/flink-1.17.0:/opt/flink:ro
```

**优点**：
- 安全：容器无法修改主机文件
- 共享：多个容器可以共享同一个 Flink
- 节省空间：不需要复制 Flink

### 生产环境

将 Flink 打包到镜像中：

```dockerfile
FROM apache/streampark:v2.1.5
COPY flink-1.17.0 /opt/flink
```

**优点**：
- 独立：不依赖主机文件系统
- 稳定：版本固定，不会意外更新
- 可移植：镜像可以在任何地方运行

## 下一步

1. ✅ Flink Home 配置完成
2. 📝 添加 Flink 作业到 StreamPark
3. 🚀 通过 StreamPark 启动和管理作业
4. 📊 查看作业监控和日志

## 参考资料

- [StreamPark 官方文档 - Flink Home](https://streampark.apache.org/zh-CN/docs/user-guide/flinkhome)
- [Docker Volume 文档](https://docs.docker.com/storage/volumes/)
- [StreamPark 使用指南](./StreamPark使用指南.md)

## 总结

1. **关键点**：在 StreamPark Web UI 中配置的是**容器内的路径** `/opt/flink`
2. **挂载方式**：通过 Docker Compose 将主机 Flink 挂载到容器
3. **路径映射**：`E:\DataFiles\flink-1.17.0` → `/opt/flink`
4. **验证方法**：`docker exec streampark /opt/flink/bin/flink --version`
5. **配置成功**：StreamPark Web UI 中可以选择 Flink 1.17.0

现在可以开始在 StreamPark 中添加和管理 Flink 作业了！
