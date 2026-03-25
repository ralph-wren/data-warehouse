#!/bin/bash

# ADS层 - 实时交易信号生成作业启动脚本
# 使用Flink CEP、广播状态、侧输出流等高级特性

# 设置环境变量
export JAVA_HOME=/usr/local/jdk1.8.0_202
export PATH=$JAVA_HOME/bin:$PATH

# 加载环境变量
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

# Flink配置
FLINK_HOME=/opt/flink-1.17.2
JOB_NAME="flink-ads-trading-signal-job"
MAIN_CLASS="com.crypto.dw.flink.FlinkADSTradingSignalJob"
JAR_FILE="target/realtime-crypto-datawarehouse-1.0.0.jar"

# 作业参数
PARALLELISM=4
CHECKPOINT_INTERVAL=60000  # 1分钟
CHECKPOINT_TIMEOUT=300000  # 5分钟

echo "=========================================="
echo "启动 ADS层 - 实时交易信号生成作业"
echo "=========================================="
echo "作业名称: $JOB_NAME"
echo "主类: $MAIN_CLASS"
echo "并行度: $PARALLELISM"
echo "Checkpoint间隔: ${CHECKPOINT_INTERVAL}ms"
echo "=========================================="

# 提交Flink作业
$FLINK_HOME/bin/flink run \
    -t yarn-per-job \
    -d \
    -c $MAIN_CLASS \
    -p $PARALLELISM \
    -Dexecution.checkpointing.interval=${CHECKPOINT_INTERVAL} \
    -Dexecution.checkpointing.timeout=${CHECKPOINT_TIMEOUT} \
    -Dexecution.checkpointing.mode=EXACTLY_ONCE \
    -Dstate.backend=rocksdb \
    -Dstate.backend.incremental=true \
    -Dstate.checkpoints.dir=hdfs:///flink/checkpoints/${JOB_NAME} \
    -Dstate.savepoints.dir=hdfs:///flink/savepoints/${JOB_NAME} \
    -Dtaskmanager.memory.process.size=2048m \
    -Dtaskmanager.numberOfTaskSlots=2 \
    -Djobmanager.memory.process.size=1024m \
    $JAR_FILE

echo "=========================================="
echo "作业提交完成！"
echo "=========================================="
