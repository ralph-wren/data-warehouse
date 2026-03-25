# Interval Join 与 Window Join 说明（FlinkADSArbitrageJob）

## 目标

为 `FlinkADSArbitrageJob` 补充 `Interval Join` 概念说明，并与 `Window Join` 做优缺点对比，方便初学者理解。

## 思路与步骤

1. 定位 `FlinkADSArbitrageJob` 的作业说明注释区域，选择在“性能考虑”后补充 Join 对比说明。
2. 用简洁条目描述两种 Join 的语义、优势与局限，突出当前作业使用 `Interval Join` 的原因。
3. 在代码关键步骤处增加“变更说明”注释，说明本次改动目的。

## 有效方法

- **语义对齐**：将说明放在作业类注释区，保证读者先看到业务整体，再理解 Join 选择理由。
- **优势对比**：从延迟、状态大小、触发机制三个维度对比，避免泛泛而谈。
- **面向初学者**：用“事件时间 + 水位线”关键字提醒使用前提，降低理解门槛。

## 变更结果

已在 `FlinkADSArbitrageJob` 说明注释中补充 `Interval Join` 与 `Window Join` 的对比内容，并在配置 Join 的步骤中添加了改动说明注释。

