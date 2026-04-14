# Mac（Apple Silicon）本地 Doris（Docker）安装与排障总结

本文汇总在 **Apple Silicon Mac** 上使用 **Colima + Docker Compose** 跑 `apache/doris`（`fe-3.0.7-rc01-1204` / `be-3.0.7-rc01-1204`）时的背景、操作步骤、配置文件变更与常见问题。  
关联仓库文件：`docker-compose-doris.yml`、`manage-doris.sh`、`scripts/link-lima-guestagents-for-colima-x86.sh`。

---

## 1. 背景与结论

### 1.1 为何不用 Docker Desktop 直接跑 amd64 BE？

- 当前镜像标签 **仅有 `linux/amd64` 清单**，无 `linux/arm64`。
- 在 **aarch64 宿主机**上由 Docker Desktop 以 **QEMU 用户态模拟**跑 `doris_be` 时，易出现 **`doris_be` 段错误（SIGSEGV，退出码 139）**，表现为 BE 反复重启、`be.out` 仅几行即结束、FE 报 `backend not alive` / `Connection refused`。

### 1.2 推荐路径：Colima **整 VM 为 x86_64**

- 使用 **Colima `arch: x86_64`**，在虚拟机内运行 **原生 amd64 Linux + Docker**，避免「宿主机 arm + 单容器 amd64 模拟」的不稳定。
- 备选：**Linux x86_64 云主机/虚拟机**上直接 `docker compose`（同一 `docker-compose-doris.yml`）。

---

## 2. 已执行 / 应执行的操作清单

### 2.1 Homebrew 组件

```bash
brew install colima docker docker-compose
brew install lima-additional-guestagents
```

`lima-additional-guestagents` 提供 **`lima-guestagent.Linux-x86_64.gz`** 等文件；Lima 默认 `share/lima` **不会自动合并**该目录，需 **符号链接**（见 2.2）。

### 2.2 Guest Agent 链到 Lima（解决 Colima x86_64 启动失败）

错误特征：

```text
guest agent binary could not be found for Linux-x86_64 ... (Hint: try installing `lima-additional-guestagents`)
```

**做法 A（推荐，可重复执行）：**

```bash
bash /Users/ralph/IdeaProjects/data-warehouse/scripts/link-lima-guestagents-for-colima-x86.sh
```

**做法 B（等价手工）：** 将 `$(brew --prefix lima-additional-guestagents)/share/lima/lima-guestagent.*` **ln -sf** 到 `$(brew --prefix lima)/share/lima/`。

> **`brew upgrade lima` 后**若再次出现上述报错，重新执行脚本 A。

### 2.3 创建 / 重建 Colima（x86_64）

```bash
colima delete -f   # 必要时清理旧实例
colima start --arch x86_64 --cpu 4 --memory 8 --disk 60
docker context use colima
docker info | grep -i Architecture   # 期望为 x86_64
```

参数可按本机资源调整；**`arch` 在 VM 创建后不可改**，需改架构时只能 `colima delete` 后重建。

**内存建议（与下文 FE/BE 堆配置联动）：**

- 镜像默认 **FE JDK17 堆为 8G**（`-Xmx8192m -Xms8192m`）时，Colima **仅 8G** 往往会在 FE 启动阶段 **`mmap` / `Not enough space`（errno 12）** 直接失败。
- 仓库内 `docker-compose-doris.yml` 已默认把 **FE 堆降到 2G**（见第 4 节），在此前提下 **8G VM 通常可跑通 FE+BE**；若仍紧张或要跑其他容器，可把 `--memory` 提到 **12～16**。

### 2.4 跨 Docker 上下文迁移镜像（desktop-linux → colima）

与 Docker Desktop 并存时，镜像各自存在**当前 context 的守护进程**里，**不能**从一个 context「push」到另一个。已在 Desktop 拉好的 Doris 镜像，可 **save / load** 到 Colima（无需再 pull）：

```bash
# 在 desktop-linux 上确认标签（示例为当前 compose 所用）
docker --context desktop-linux images | grep -E 'apache/doris.*(fe|be)-3.0.7'

# 合并导出（单文件约 3.x GB，路径自定）
docker --context desktop-linux save \
  apache/doris:fe-3.0.7-rc01-1204 \
  apache/doris:be-3.0.7-rc01-1204 \
  -o /tmp/doris-fe-be-3.0.7-rc01.tar

# 在 colima 导入
docker --context colima load -i /tmp/doris-fe-be-3.0.7-rc01.tar

# 可选：确认后删除 tar 释放磁盘
# rm /tmp/doris-fe-be-3.0.7-rc01.tar
```

### 2.5 拉起 Doris

```bash
cd /Users/ralph/IdeaProjects/data-warehouse
# 若默认 context 不是 colima，可显式指定：
# DOCKER_CONTEXT=colima ./manage-doris.sh start
./manage-doris.sh start
# 或: docker compose -f docker-compose-doris.yml up -d
```

`manage-doris.sh` 已 **`chmod +x`**，可直接 `./manage-doris.sh`；若克隆后无执行位，可 `chmod +x manage-doris.sh`。

自检：

```bash
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW BACKENDS\G"
```

关注 **`Alive: true`**，`BePort` / `HttpPort` 为正。

HTTP 快速探活（FE）：

```bash
curl -sf -o /dev/null -w '%{http_code}\n' http://127.0.0.1:8030/api/bootstrap   # 期望 200
```

FE 日志中应出现 **`-Xmx2048m -Xms2048m`**（与当前 compose 默认一致）。

---

## 3. `~/.colima/default/colima.yaml`（用户主目录，不在 Git 内）

### 3.1 Docker Hub 拉取 EOF（`registry-1.docker.io` / 镜像站 CDN EOF）

现象：`docker compose pull` 或 `docker pull` 报 **`failed to do request` / `EOF`**。

**处理：**

1. 配置 **`docker.registry-mirrors`**（示例，可多填几个备用）：

```yaml
docker:
  registry-mirrors:
    - https://docker.1ms.run
    - https://docker.m.daocloud.io
    - https://docker.xuanyuan.me
```

2. 若本机 **HTTP(S) 代理**写入环境变量，Colima 会改写到 VM（日志里常见把 `127.0.0.1:10809` 换成 `192.168.5.2:10809`）。代理不稳定时 **pull 大层易 EOF**，可先在 VM 侧 **清空代理** 验证（需直连或镜像站可访问）：

```yaml
env:
  HTTP_PROXY: ""
  HTTPS_PROXY: ""
  http_proxy: ""
  https_proxy: ""
  ALL_PROXY: ""
  all_proxy: ""
```

3. 修改后执行：

```bash
colima restart
```

**若必须走代理：** 删除或调整上述 `env` 空值，改为 VM 内可达的代理地址，并保证代理对 Docker Hub / 镜像 CDN 可用（如 Clash **Allow LAN** 等）。

**其他手段：** 换网络（如手机热点）、使用云厂商 **Docker 镜像加速器**（如阿里云个人加速地址）填入 `registry-mirrors` 首位。

---

## 4. 仓库内 `docker-compose-doris.yml` 要点

| 项 | 说明 |
|----|------|
| 固定子网 `172.25.0.0/16`，FE `172.25.0.2`，BE `172.25.0.3` | 与 `FE_SERVERS` / `BE_ADDR` / `PRIORITY_NETWORKS` 一致 |
| `platform: linux/amd64` | 与镜像架构一致；Colima x86_64 下为原生 amd64 |
| 已移除顶层 `version:` | Compose 新版本弃用该字段，消除告警 |
| **FE** `entrypoint` + `command` 包装 | 在 `init_fe.sh` 前 **`sed` 修改** 镜像内 `/opt/apache-doris/fe/conf/fe.conf`：将 **`JAVA_OPTS_FOR_JDK_17`** 中的 **`-Xmx8192m` / `-Xms8192m`** 改为 **`2048m`**（仅当仍存在 `8192` 时执行，可重复起容器）。原因：`start_fe.sh` 从 **`fe.conf` 解析并 `export`** JVM 参数，**环境变量无法覆盖** 文件中的 8G 默认堆。 |
| **FE** `mem_limit` / `memswap_limit` **`3g`** | 与 **2G 堆**对齐并留元数据/系统余量 |
| **BE** `shm_size: "2gb"` | 缓解 `/dev/shm` 过小导致异常 |
| **BE** `ulimits.nofile` | 65535，符合 Doris 对句柄数的一般要求 |
| **BE** `mem_limit` / `memswap_limit` **`4g`** | 当前镜像 **`be.conf`** 中 JDK17 堆已为 **`-Xmx2048m`**；cgroup 略高于堆，覆盖 **`doris_be` C++ 进程** 与 page cache；`memswap_limit == mem_limit` 时该容器不使用 swap 额度 |
| **BE** `JAVA_HOME=/usr/lib/jvm/java` | 避免仅依赖镜像内注释掉的 `be.conf` 导致找不到 `libjvm` |
| FE `healthcheck` | BE `depends_on` 等 FE healthy 再起 |

### 4.1 FE 启动失败：`Not enough space` / JVM 无法 commit 8G

典型日志：

```text
OpenJDK 64-Bit Server VM warning: ... os::commit_memory ... failed; error='Not enough space' (errno=12)
There is insufficient memory for the Java Runtime Environment to continue.
Native memory allocation (mmap) failed to map 8589934592 bytes ...
```

含义：镜像默认 **FE 堆 8G**，Colima VM 或整机可用内存不足。处理优先级：

1. **已合并进 compose**：按上表在容器启动前把 FE 堆改为 **2G**（无需再手工改 `fe.conf`）。
2. 仍 OOM 时：**加大 Colima** `--memory`，或减少本机其它占用。
3. 若去掉 compose 里的 `sed` 包装、恢复 8G 堆：建议 Colima **≥12～16G** 再跑 FE+BE。

### 4.2 Compose 多行 `command` 中的 `$` 必须转义

`command` 内 Shell 变量若写成 `$FE_CONF`，Compose 会当作 **项目环境变量插值**，启动时出现 **`The "FE_CONF" variable is not set`**，且容器内路径变为空，**`sed` 误伤**。应写成 **`$$FE_CONF`**，由 Compose 还原为 **`$FE_CONF`** 再交给 Shell。

---

## 5. `manage-doris.sh` 行为

- 使用脚本所在目录定位 `docker-compose-doris.yml`，不必先 `cd`。
- 优先 `docker compose`，否则 `docker-compose`。
- 在 **Darwin + arm64** 下打印 **Colima x86_64** 与 **registry EOF / colima.yaml** 提示。
- 仓库内脚本已具备 **可执行位**；若本地仍 `Permission denied`，执行 `chmod +x manage-doris.sh`。

---

## 6. 与 Docker Desktop 并存时的注意点

- 同一时间只让一个上下文生效：`docker context use colima` 或 `docker context use desktop-linux`（名称以 `docker context ls` 为准）。
- Doris 开发建议 **Colima x86_64** 下完成 pull 与运行。
- 已在 Desktop 下载的镜像需迁到 Colima 时，见 **2.4 节**（`save` / `load`），勿对另一 context 执行 `docker push`。

---

## 7. 文件索引（本仓库）

| 路径 | 作用 |
|------|------|
| `docker-compose-doris.yml` | FE/BE 编排与资源约束 |
| `manage-doris.sh` | start / stop / restart + 提示 |
| `scripts/link-lima-guestagents-for-colima-x86.sh` | Lima guest agent 符号链接（Colima x86_64 前置条件） |

---

## 8. 参考链接

- [Apache Doris 下载与版本](https://doris.apache.org/download)
- [Colima](https://github.com/abiosoft/colima)
- [Lima additional guest agents（Homebrew）](https://formulae.brew.sh/formula/lima-additional-guestagents)
- [Docker Engine：daemon.json `registry-mirrors`](https://docs.docker.com/engine/reference/commandline/dockerd/#daemon-configuration-file)

---

*文档整理日期：2026-04-13；同日补充：desktop-linux → colima 镜像 `save`/`load`、FE 默认 8G 堆 OOM 与 compose 内降为 2G 的 `sed` 包装、`$$` 转义、`manage-doris.sh` 可执行位、FE/BE `mem_limit` 调整。与当时 `apache/doris:fe|be-3.0.7-rc01-1204`、Colima 0.10.x、Lima 2.1.x 实践一致；若上游镜像或 Colima 行为变更，请以官方文档为准。*
