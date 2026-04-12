# Kubernetes 部署指南

本目录包含 Flink 作业在 Kubernetes 上的部署配置和脚本。

## 目录结构

```
k8s/
├── README.md                    # 本文件
├── TROUBLESHOOTING.md          # 问题修复记录
├── deploy-job.sh               # 部署脚本
├── flink-job-template.yaml     # Flink 作业部署模板
├── Dockerfile                  # 自定义镜像构建文件
├── .gitignore                  # Git 忽略配置
├── common/
│   └── shared-resources.yaml   # 共享资源(Namespace, ServiceAccount, Secret, etc.)
└── jobs/
    ├── ticker-collector.yaml   # Ticker 采集作业配置
    └── ads-arbitrage.yaml      # 套利分析作业配置
```

## 快速开始

### 1. 前置条件

- Kubernetes 集群已安装并运行
- kubectl 已配置并可访问集群
- Docker 已安装(用于拉取镜像)

### 2. 创建共享资源

```bash
kubectl apply -f k8s/common/shared-resources.yaml
```

这将创建:
- `flink` namespace
- ServiceAccount 和 RBAC 权限
- Secret (包含 OKX API 密钥、Kafka 地址等)

### 3. 配置 Secret

编辑 `k8s/common/shared-resources.yaml` 中的 Secret,填入真实的配置:

```yaml
stringData:
  OKX_API_KEY: "your-api-key-here"
  OKX_SECRET_KEY: "your-secret-key-here"
  OKX_PASSPHRASE: "your-passphrase-here"
  PROXY_HOST: "localhost"
  PROXY_PORT: "10809"
  KAFKA_BOOTSTRAP_SERVERS: "kafka:9092"
```

### 4. 拉取 Flink 镜像

```bash
docker pull flink:1.17.2-java11
```

### 5. 部署作业

```bash
cd k8s
./deploy-job.sh deploy ticker-collector
```

### 6. 验证部署

```bash
# 查看 Pod 状态
kubectl get pods -n flink -l app=flink-ticker-collector

# 查看服务
kubectl get svc -n flink

# 查看日志
kubectl logs -n flink -l app=flink-ticker-collector,component=jobmanager
```

## 部署脚本使用

`deploy-job.sh` 提供了完整的作业管理功能:

```bash
# 部署作业
./deploy-job.sh deploy <job-name>

# 删除作业
./deploy-job.sh delete <job-name>

# 重启作业
./deploy-job.sh restart <job-name>

# 查看状态
./deploy-job.sh status <job-name>

# 查看日志
./deploy-job.sh logs <job-name>

# 扩缩容 TaskManager
./deploy-job.sh scale <job-name> <replicas>

# 列出所有作业
./deploy-job.sh list

# 测试配置加载
./deploy-job.sh test <job-name>
```

## 作业配置

每个作业的配置文件位于 `jobs/` 目录,包含以下参数:

```yaml
# 作业基本信息
JOB_NAME: ticker-collector
JOB_CLASS: com.crypto.dw.jobs.FlinkTickerCollectorJob

# JobManager 资源配置
JM_MEMORY: 1600m
JM_MEMORY_REQUEST: 1Gi
JM_MEMORY_LIMIT: 2Gi
JM_CPU_REQUEST: 500m
JM_CPU_LIMIT: 1000m

# TaskManager 资源配置
TM_MEMORY: 2048m
TM_MEMORY_REQUEST: 1.5Gi
TM_MEMORY_LIMIT: 2.5Gi
TM_CPU_REQUEST: 500m
TM_CPU_LIMIT: 1000m
TM_REPLICAS: 2
TM_SLOTS: 2

# Flink 配置
PARALLELISM: 1
CHECKPOINT_INTERVAL: 60000

# 镜像配置
DOCKER_REGISTRY: flink
VERSION: 1.17.2-java11
```

## 访问 Flink Web UI

部署成功后,使用以下方式访问 Flink Web UI:

### 方法 1: 使用脚本 (推荐)

```bash
cd k8s
./open-webui.sh ticker-collector
```

然后在浏览器中打开: http://localhost:8081

### 方法 2: 手动 port-forward

```bash
kubectl port-forward -n flink svc/flink-ticker-collector-jobmanager 8081:8081
```

然后在浏览器中打开: http://localhost:8081

### 方法 3: 查看所有服务地址

```bash
./show-webui.sh
```

**注意**: 
- Docker Desktop 的 Kubernetes 在 Windows/WSL2 环境下,NodePort 无法直接通过 localhost 访问
- 必须使用 `kubectl port-forward` 进行端口转发
- 保持终端窗口打开,按 Ctrl+C 停止转发

## 自定义镜像构建

如果需要将应用代码打包到镜像中:

```bash
# 1. 构建应用 JAR
mvn clean package

# 2. 构建 Docker 镜像
docker build -t flink-jobs:1.0.0 -f k8s/Dockerfile .

# 3. 修改作业配置使用自定义镜像
# 编辑 k8s/jobs/ticker-collector.yaml
DOCKER_REGISTRY: flink-jobs
VERSION: 1.0.0
```

## 存储说明

当前配置使用 hostPath 卷存储 checkpoint 和 savepoint:

```yaml
volumes:
- name: flink-storage
  hostPath:
    path: /tmp/flink-data/${JOB_NAME}
    type: DirectoryOrCreate
```

**注意**: hostPath 仅适合单节点开发环境。生产环境建议使用:
- NFS
- Ceph
- 云存储 (AWS EBS, GCP PD, Azure Disk)

## 常见问题

详见 [TROUBLESHOOTING.md](./TROUBLESHOOTING.md)

## 清理资源

```bash
# 删除作业
./deploy-job.sh delete ticker-collector

# 删除所有资源
kubectl delete namespace flink
```
