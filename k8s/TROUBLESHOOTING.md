# Kubernetes 部署问题修复记录

## 问题 1: Pod 一直处于 Pending 状态

**时间**: 2026-04-09

**症状**:
```bash
$ kubectl get pods -n flink
NAME                                    READY   STATUS    RESTARTS   AGE
flink-ticker-collector-jobmanager-xxx   0/1     Pending   0          5m
```

**原因**:
1. PVC 配置使用 `ReadWriteMany`,但 Docker Desktop k8s 的 local-path provisioner 只支持 `ReadWriteOnce`
2. PVC 无法绑定到 PV,导致 Pod 无法调度

**解决方案**:

使用 hostPath 卷代替 PVC:

```yaml
# k8s/flink-job-template.yaml
volumes:
- name: flink-storage
  hostPath:
    path: /tmp/flink-data/${JOB_NAME}
    type: DirectoryOrCreate
```

---

## 问题 2: ImagePullBackOff 错误

**时间**: 2026-04-09

**症状**:
```bash
$ kubectl get pods -n flink
NAME                                    READY   STATUS             RESTARTS   AGE
flink-ticker-collector-jobmanager-xxx   0/1     ImagePullBackOff   0          2m
```

**原因**:
1. 镜像配置使用占位符 `your-registry/flink-jobs:1.0.0`
2. 镜像加速器故障返回 500 错误
3. k8s 无法从 Docker Hub 拉取镜像

**解决方案**:

1. 手动拉取官方镜像:
```bash
docker pull flink:1.17.2-java11
```

2. 修改作业配置使用官方镜像:
```yaml
# k8s/jobs/ticker-collector.yaml
DOCKER_REGISTRY: flink
VERSION: 1.17.2-java11
```

3. 在模板中添加 imagePullPolicy:
```yaml
# k8s/flink-job-template.yaml
image: flink:1.17.2-java11
imagePullPolicy: IfNotPresent  # 优先使用本地镜像
```

---

## 问题 3: ConfigMap 只读文件系统警告

**时间**: 2026-04-09

**症状**:
```
sed: cannot rename /opt/flink/conf/sedIeIpCn: Device or resource busy
/docker-entrypoint.sh: line 73: /opt/flink/conf/flink-conf.yaml: Read-only file system
```

**原因**:
ConfigMap 挂载的文件是只读的,Flink 启动脚本尝试修改配置文件

**影响**:
不影响 Flink 正常运行,可以忽略

**说明**:
Flink 会使用 ConfigMap 中的配置,只是无法修改文件。这是 Kubernetes ConfigMap 的正常行为。

---

## 问题 4: 无法访问 Flink Web UI (NodePort 不工作)

**时间**: 2026-04-09

**症状**:
无法通过 http://localhost:30081 访问 Flink Web UI,连接被拒绝

**原因**:
Docker Desktop 的 Kubernetes 在 Windows/WSL2 环境下,NodePort 无法直接通过 localhost 访问

**解决方案**:

**方案 A: 使用 kubectl port-forward (推荐)**

1. 使用提供的脚本:
```bash
cd k8s
./open-webui.sh ticker-collector
```

2. 或手动执行:
```bash
kubectl port-forward -n flink svc/flink-ticker-collector-jobmanager 8081:8081
```

3. 在浏览器中打开:
```
http://localhost:8081
```

**方案 B: 使用 LoadBalancer (需要 MetalLB 或云环境)**

修改 Service 类型为 LoadBalancer:
```yaml
# k8s/flink-job-template.yaml
spec:
  type: LoadBalancer  # 改为 LoadBalancer
```

**方案 C: 使用 Ingress**

配置 Ingress 控制器并创建 Ingress 资源

**说明**:
- port-forward 是本地开发最简单的方式
- 保持终端窗口打开,按 Ctrl+C 停止转发
- 每次重启后需要重新执行 port-forward

---

## 问题 5: 无法访问 Flink Web UI (旧问题)

**时间**: 2026-04-09

**症状**:
无法通过浏览器访问 Flink Web UI

**原因**:
Service 类型配置错误或端口映射问题

**解决方案**:

1. 确认 Service 类型为 NodePort:
```yaml
# k8s/flink-job-template.yaml
apiVersion: v1
kind: Service
metadata:
  name: flink-${JOB_NAME}-jobmanager
  namespace: flink
spec:
  type: NodePort
  ports:
  - name: webui
    port: 8081
    targetPort: 8081
    nodePort: 30081  # 固定端口
```

2. 查看实际端口:
```bash
kubectl get svc -n flink
```

3. 访问地址:
```
http://localhost:30081
```

---

## 问题 5: Secret 配置错误

**时间**: 2026-04-09

**症状**:
Pod 启动失败,日志显示无法读取环境变量

**原因**:
Secret 未创建或配置错误

**解决方案**:

1. 确认 Secret 已创建:
```bash
kubectl get secret -n flink flink-common-secret
```

2. 检查 Secret 内容:
```bash
kubectl get secret -n flink flink-common-secret -o yaml
```

3. 更新 Secret:
```bash
kubectl apply -f k8s/common/shared-resources.yaml
```

---

## 问题 6: 自定义镜像无法使用

**时间**: 2026-04-09

**症状**:
使用本地构建的 `flink-jobs:1.0.0` 镜像时,k8s 无法找到

**原因**:
Docker Desktop k8s 使用 containerd 运行时,无法直接访问 Docker 镜像

**解决方案**:

**方案 A**: 使用官方镜像 + 外部 JAR 提交
```bash
# 使用官方 Flink 镜像
DOCKER_REGISTRY: flink
VERSION: 1.17.2-java11

# 通过 Flink CLI 提交作业
kubectl exec -it -n flink flink-ticker-collector-jobmanager-xxx -- \
  flink run /path/to/app.jar
```

**方案 B**: 推送到镜像仓库
```bash
# 标记镜像
docker tag flink-jobs:1.0.0 your-registry/flink-jobs:1.0.0

# 推送到仓库
docker push your-registry/flink-jobs:1.0.0

# 更新配置
DOCKER_REGISTRY: your-registry/flink-jobs
VERSION: 1.0.0
```

---

## 问题 7: 资源不足导致 Pod 无法调度

**时间**: 2026-04-09

**症状**:
```
Events:
  Warning  FailedScheduling  Insufficient cpu
```

**原因**:
节点资源不足,无法满足 Pod 的资源请求

**解决方案**:

1. 降低资源请求:
```yaml
# k8s/jobs/ticker-collector.yaml
JM_MEMORY_REQUEST: 512Mi  # 降低内存请求
JM_CPU_REQUEST: 250m       # 降低 CPU 请求
```

2. 查看节点资源:
```bash
kubectl top nodes
kubectl describe nodes
```

3. 增加节点或清理资源

---

## 调试技巧

### 1. 查看 Pod 详细信息
```bash
kubectl describe pod -n flink <pod-name>
```

### 2. 查看 Pod 日志
```bash
# 查看当前日志
kubectl logs -n flink <pod-name>

# 查看之前容器的日志
kubectl logs -n flink <pod-name> --previous

# 实时跟踪日志
kubectl logs -n flink <pod-name> -f
```

### 3. 进入容器调试
```bash
kubectl exec -it -n flink <pod-name> -- /bin/bash
```

### 4. 查看事件
```bash
kubectl get events -n flink --sort-by='.lastTimestamp'
```

### 5. 查看资源使用
```bash
kubectl top pods -n flink
kubectl top nodes
```

---

## 生产环境建议

1. **使用持久化存储**
   - 配置 PVC 使用支持 ReadWriteMany 的存储类
   - 定期备份 checkpoint 和 savepoint

2. **配置资源限制**
   - 设置合理的 requests 和 limits
   - 启用 HPA (Horizontal Pod Autoscaler)

3. **网络配置**
   - 使用 Ingress 暴露服务
   - 配置网络策略限制访问

4. **安全配置**
   - 使用 Secret 管理敏感信息
   - 配置 SecurityContext
   - 启用 RBAC

5. **监控告警**
   - 集成 Prometheus 监控
   - 配置 Grafana 面板
   - 设置告警规则

6. **高可用配置**
   - JobManager 使用 HA 模式
   - 配置多个 TaskManager 副本
   - 使用 ZooKeeper 或 Kubernetes HA
