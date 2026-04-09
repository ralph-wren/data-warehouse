# Flink Application 模式 Dockerfile
# 基于官方 Flink 镜像构建

FROM flink:1.17.2-java11

# 设置工作目录
WORKDIR /opt/flink

# 复制应用 JAR 包到镜像
COPY target/realtime-crypto-datawarehouse-1.0.0.jar /opt/flink/usrlib/app.jar

# 复制配置文件
COPY src/main/resources/config/application-dev.yml /opt/flink/conf/application.yml
COPY src/main/resources/log4j2.properties /opt/flink/conf/

# 设置环境变量
ENV FLINK_PROPERTIES="jobmanager.rpc.address: flink-jobmanager"

# 暴露端口
# 6123: JobManager RPC
# 6124: JobManager Blob Server
# 8081: JobManager Web UI
# 9249: Prometheus Metrics
EXPOSE 6123 6124 8081 9249

# 设置用户
USER flink

# 默认命令（会被 K8s 配置覆盖）
CMD ["help"]
