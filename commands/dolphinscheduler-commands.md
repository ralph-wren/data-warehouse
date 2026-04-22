# DolphinScheduler 常用命令

## 启动与停止

```bash
bash manage-dolphinscheduler.sh start
bash manage-dolphinscheduler.sh start-full
bash manage-dolphinscheduler.sh stop
bash manage-dolphinscheduler.sh restart
```

## 查看状态与日志

```bash
bash manage-dolphinscheduler.sh status
bash manage-dolphinscheduler.sh logs
```

## 初始化元数据库

```bash
bash manage-dolphinscheduler.sh init
```

## 推荐模式

```bash
# 本地开发建议使用轻量模式，默认不启动 alert
bash manage-dolphinscheduler.sh start

# 只有需要告警服务时再启动完整版
bash manage-dolphinscheduler.sh start-full
```

## 校验与现有组件联通

```bash
bash manage-dolphinscheduler.sh verify
bash scripts/check-dolphinscheduler-connectivity.sh
```

## Web 入口

```bash
http://localhost:12345/dolphinscheduler/ui
```

## 常见排查

```bash
docker logs dolphinscheduler-api --tail 100
docker logs dolphinscheduler-master --tail 100
docker logs dolphinscheduler-worker --tail 100
docker logs dolphinscheduler-postgresql --tail 100
docker logs dolphinscheduler-zookeeper --tail 100
```

## 检查 Worker 对 Flink / Doris 的访问

```bash
docker exec dolphinscheduler-worker curl -s http://jobmanager:8081/overview
docker exec dolphinscheduler-worker bash -lc 'cat < /dev/null > /dev/tcp/doris-fe/9030'
docker exec dolphinscheduler-worker curl -s http://doris-fe:8030/api/bootstrap
```
