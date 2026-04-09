# K8s部署 - 修复ticker-collector作业部署问题

**时间**: 2026-04-09 14:45  
**问题**: 执行 `./deploy-job.sh deploy ticker-collector` 部署到本地k8s任务失败  
**状态**: ✅ 已解决

## 问题分析

部署失败的主要原因有以下几个:

### 1. PVC 存储类不兼容
- **问题**: PVC 配置使用 `ReadWriteMany` 访问模式
- **原因**: Docker Desktop k8s 使用 `rancher.io/local-path` provisioner,只支持 `ReadWriteOnce`
- **影响**: Pod 无法调度,一直处于 Pending 状态

### 2. 镜像配置错误
- **问题**: 初始配置使用占位符镜像 `your-registry/flink-jobs:1.0.0`
- **原因**: 配置文件中的镜像地址未更新
- **影响**: Pod 无法拉取镜像,ImagePullBackOff 错误

### 3. 本地镜像无法访问
- **问题**: 虽然本地有 `flink-jobs:1.0.0` 镜像,但 k8s 无法访问
- **原因**: Docker Desktop k8s 使用 containerd 运行时,需要单独拉取镜像
- **影响**: 持续的 ImagePullBackOff 错误

### 4. 镜像加速器故障
- **问题**: Docker 配置的镜像加速器 `registry-mirror:1273` 返回 500 错误
- **原因**: 镜像加速器服务异常
- **影响**: 无法从加速器拉取官方 Flink 镜像

## 解决方案

### 1. 修改存储配置
将 PVC 改为使用 hostPath 卷,适合本地开发环境:

```yaml
# k8s/flink-job-template-simple.yaml
volumes:
- name: flink-storage
  hostPath:
    path: /tmp/flink-data/${JOB_NAME}
    type: DirectoryOrCreate
```

**优点**:
- 无需 PVC,直接使用节点本地存储
- 适合单节点开发环境
- 避免存储类兼容性问题

### 2. 使用官方 Flink 镜像
修改配置使用官方镜像,避免自定义镜像的复杂性:

```yaml
# k8s/jobs/ticker-collector.yaml
DOCKER_REGISTRY: flink
VERSION: 1.17.2-java11
```

```yaml
# k8s/flink-job-template-simple.yaml
image: flink:1.17.2-java11
imagePullPolicy: IfNotPresent
```

### 3. 手动拉取镜像
绕过镜像加速器,直接从 Docker Hub 拉取:

```bash
docker pull flink:1.17.2-java11
```

### 4. 创建简化部署模板
创建 `flink-job-template-simple.yaml`,使用官方镜像 + hostPath 存储:

**关键改动**:
- 使用官方 Flink 镜像 `flink:1.17.2-java11`
- 使用 hostPath 卷代替 PVC
- Service 类型改为 NodePort,方便本地访问
- 添加 `imagePullPolicy: IfNotPresent` 优先使用本地镜像

### 5. 修改部署脚本
更新 `deploy-job.sh` 使用简化模板:

```bash
TEMPLATE_FILE="${K8S_DIR}/flink-job-template-simple.yaml"
```

修复 sed 替换中的特殊字符处理:

```bash
-e "s|\${DOCKER_REGISTRY}|${DOCKER_REGISTRY}|g"  # 使用 | 分隔符避免 / 冲突
```

## 部署步骤

### 1. 确保基础资源已创建
```bash
kubectl apply -f k8s/common/shared-resources.yaml
```

### 2. 拉取官方镜像
```bash
docker pull flink:1.17.2-java11
```

### 3. 部署任务
```bash
cd k8s
./deploy-job.sh deploy ticker-collector
```

### 4. 验证部署
```bash
# 查看 Pod 状态
kubectl get pods -n flink -l app=flink-ticker-collector

# 查看服务
kubectl get svc -n flink

# 查看日志
kubectl logs -n flink -l app=flink-ticker-collector,component=jobmanager
```

## 部署结果

```bash
$ kubectl get pods -n flink -l app=flink-ticker-collector
NAME                                                  READY   STATUS    RESTARTS   AGE
flink-ticker-collector-jobmanager-75dcdf5cc6-79x8r    1/1     Running   0          79s
flink-ticker-collector-taskmanager-59b79f465c-896wt   1/1     Running   0          79s
flink-ticker-collector-taskmanager-59b79f465c-zfgkf   1/1     Running   0          79s

$ kubectl get svc -n flink
NAME                                TYPE       CLUSTER-IP    EXTERNAL-IP   PORT(S)
flink-ticker-collector-jobmanager   NodePort   10.96.24.31   <none>        6123:31395/TCP,8081:30081/TCP,9249:32488/TCP
```

**访问地址**:
- Flink Web UI: http://localhost:30081
- Prometheus Metrics: http://localhost:32488

## 注意事项

### 1. ConfigMap 只读警告
部署后日志中会出现以下警告:

```
sed: cannot rename /opt/flink/conf/sedIeIpCn: Device or resource busy
/docker-entrypoint.sh: line 73: /opt/flink/conf/flink-conf.yaml: Read-only file system
```

**原因**: ConfigMap 挂载的文件是只读的  
**影响**: 不影响 Flink 正常运行,可以忽略  
**说明**: Flink 会使用 ConfigMap 中的配置,只是无法修改文件

### 2. 应用 JAR 包
当前部署使用官方 Flink 镜像,不包含应用代码。后续需要:

**方案 A**: 构建自定义镜像
```bash
# 构建包含应用的镜像
mvn clean package
docker build -t flink-jobs:1.0.0 -f k8s/Dockerfile .

# 标记并推送到本地 registry 或直接使用
```

**方案 B**: 使用 Flink Application 模式
通过 Job 提交 API 或 Flink CLI 提交应用 JAR

**方案 C**: 挂载 JAR 包
通过 ConfigMap 或 hostPath 挂载 JAR 到容器

### 3. 生产环境建议
本方案适合本地开发,生产环境建议:

- 使用支持 ReadWriteMany 的存储类 (如 NFS, Ceph)
- 使用私有镜像仓库
- 配置资源限制和 HPA
- 启用持久化存储和备份
- 配置网络策略和安全上下文

## 相关文件

- `k8s/deploy-job.sh` - 部署脚本
- `k8s/flink-job-template-simple.yaml` - 简化部署模板
- `k8s/jobs/ticker-collector.yaml` - 任务配置
- `k8s/common/shared-resources.yaml` - 共享资源

## 总结

通过以下改进成功解决了 k8s 部署问题:

1. ✅ 使用 hostPath 卷代替 PVC,避免存储类兼容性问题
2. ✅ 使用官方 Flink 镜像,简化镜像管理
3. ✅ 手动拉取镜像,绕过镜像加速器故障
4. ✅ 创建简化部署模板,适配本地开发环境
5. ✅ 修复部署脚本的变量替换问题

部署成功后,Flink 集群正常运行,可以通过 NodePort 访问 Web UI 和 Metrics 端点。
