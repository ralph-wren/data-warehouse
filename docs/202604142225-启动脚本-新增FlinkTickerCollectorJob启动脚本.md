# 启动 `FlinkTickerCollectorJob` 专用脚本

## 问题背景

项目里已有的 `run-flink-collector.sh` 启动的是另一个类，不适合直接用于启动 `com.crypto.dw.jobs.FlinkTickerCollectorJob`。

## 本次处理

新增专用启动脚本：

- `run-flink-ticker-collector-job.sh`

脚本作用：

1. 专门启动 `com.crypto.dw.jobs.FlinkTickerCollectorJob`
2. 支持外部传入币对参数
3. 如果不传参数，则交给 Java 程序内部决定订阅列表
4. 启动前自动编译项目
5. 默认使用 `APP_ENV=dev`，也支持外部覆盖

## 脚本内容说明

启动主类：

- `com.crypto.dw.jobs.FlinkTickerCollectorJob`

主要执行流程：

1. 打印启动信息
2. 检查/设置 `APP_ENV`
3. 接收命令行传入的币对参数
4. `mvn clean compile -DskipTests`
5. `mvn exec:java` 启动作业

## 使用方式

### 1. 不传币对参数

```bash
bash ./run-flink-ticker-collector-job.sh
```

### 2. 手动指定币对

```bash
bash ./run-flink-ticker-collector-job.sh BTC-USDT ETH-USDT SOL-USDT
```

### 3. 指定环境后启动

```bash
APP_ENV=dev bash ./run-flink-ticker-collector-job.sh BTC-USDT ETH-USDT
```

## 验证结果

已验证：

1. 新脚本能够正确指向目标主类
2. 能够成功触发编译和启动流程
3. 启动后已经进入 `FlinkTickerCollectorJob` 主类日志

## 额外发现

在验证过程中还发现了两个独立问题：

1. `FlinkTickerCollectorJob` 当前在价差计算失败时，订阅币对列表会变成空列表
2. 当前运行环境下还出现了 Flink / JDK 反射访问异常：
   - `Unable to make field private static final long java.util.Arrays$ArrayList.serialVersionUID accessible`

这两个问题与“新增专用启动脚本”不是同一个任务，但后续可以继续单独处理。

## 本次结论

本次已经完成：

- 为 `com.crypto.dw.jobs.FlinkTickerCollectorJob` 新增独立启动脚本
- 可以与旧的 `run-flink-collector.sh` 区分使用，避免误启动到其他类
