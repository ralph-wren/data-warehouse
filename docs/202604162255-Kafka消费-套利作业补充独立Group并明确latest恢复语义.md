# Kafka消费 - 套利作业补充独立Group并明确latest恢复语义

## 问题描述

`com.crypto.dw.jobs.FlinkADSArbitrageJob` 已配置 `kafka.consumer.startup-mode: latest`，但运行时仍感觉会从历史 offset 开始消费，导致继续处理过期行情。

## 根本原因

本问题实际包含两层：

1. `latest` 只对 Kafka Source 首次初始化生效  
   如果作业从 Flink 的 `checkpoint/savepoint` 恢复，Kafka Source 会优先使用恢复状态中的 offset，而不会重新按 `latest` 初始化。

2. 套利作业的现货/合约 Source 使用的是两个独立 jobType  
   `FlinkADSArbitrageJob` 实际调用：
   - `ads-arbitrage-spot`
   - `ads-arbitrage-swap`

   因此需要分别配置：
   - `kafka.consumer.group-id.ads-arbitrage-spot`
   - `kafka.consumer.group-id.ads-arbitrage-swap`

   否则代码会回退到默认 group 名称，容易和预期不一致。

## 解决方案

### 1. 明确 group 解析日志

在 `KafkaSourceFactory` 中新增 `resolveGroupId()`：

- 优先读取精确配置键
- 未配置时打印 warning 并回退默认 group
- 启动时清楚打印实际命中的 group id

### 2. 明确 latest 的真实语义

在 Kafka Source 启动日志中明确说明：

- `latest` 仅对首次启动且无恢复状态时生效
- 从 `checkpoint/savepoint` 恢复时，恢复状态中的 offset 优先级更高

### 3. Docker 环境补齐套利作业双 Source 的 group 配置

为 `application-docker.yml` 增加：

- `kafka.consumer.group-id.ads-arbitrage-spot`
- `kafka.consumer.group-id.ads-arbitrage-swap`

## 修改文件

- `src/main/java/com/crypto/dw/factory/KafkaSourceFactory.java`
- `src/main/resources/config/application-docker.yml`

## 结论

这次修改不会改变 Flink 从 checkpoint/savepoint 恢复时的官方行为，但会：

1. 保证套利作业的现货/合约 Source 使用明确、可配置的独立 group
2. 避免误以为代码读的是 `ads-arbitrage`，实际却回退到默认 group
3. 在日志中直接说明为什么 `latest` 看起来“没有生效”

## 使用建议

如果目标是“每次启动只消费最新行情”，除了配置 `startup-mode: latest`，还需要满足：

1. 不是从旧 checkpoint/savepoint 恢复
2. 或者使用新的 consumer group
3. 或者清理旧恢复状态后再全新提交

## 日期

2026-04-16 22:55
