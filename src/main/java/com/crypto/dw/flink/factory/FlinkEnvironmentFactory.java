package com.crypto.dw.flink.factory;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.config.MetricsConfig;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.streaming.api.CheckpointingMode;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.environment.CheckpointConfig;
import org.apache.flink.table.api.bridge.java.StreamTableEnvironment;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Locale;

/**
 * Flink Environment 工厂类
 * 
 * 封装 Flink 执行环境的创建逻辑,避免重复代码
 * 支持的功能:
 * - DataStream API 环境
 * - Table API 环境
 * - Web UI 配置
 * - Prometheus Metrics 配置
 * - Checkpoint 配置
 * - 并行度配置
 * 
 * 使用示例:
 * <pre>
 * ConfigLoader config = ConfigLoader.getInstance();
 * FlinkEnvironmentFactory factory = new FlinkEnvironmentFactory(config);
 * 
 * // 创建 DataStream 环境
 * StreamExecutionEnvironment env = factory.createStreamEnvironment("flink-ods-job", 8081);
 * 
 * // 创建 Table 环境
 * StreamTableEnvironment tableEnv = factory.createTableEnvironment("flink-dwd-job", 8082);
 * </pre>
 */
public class FlinkEnvironmentFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkEnvironmentFactory.class);
    
    private final ConfigLoader config;
    
    public FlinkEnvironmentFactory(ConfigLoader config) {
        this.config = config;
    }
    
    /**
     * 创建 StreamExecutionEnvironment (DataStream API)
     * 
     * @param jobName 作业名称(用于 Metrics 标识)
     * @param webUIPort Web UI 端口(避免端口冲突)
     * @return 配置好的 StreamExecutionEnvironment
     */
    public StreamExecutionEnvironment createStreamEnvironment(String jobName, int webUIPort) {
        logger.info("==========================================");
        logger.info("创建 Flink Stream Environment");
        logger.info("作业名称: {}", jobName);
        logger.info("==========================================");
        
        // 创建 Flink 配置
        Configuration flinkConfig = createFlinkConfiguration(jobName, webUIPort);
        
        // 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        
        // 统一应用执行配置，避免每个作业各自重复实现
        int parallelism = configureExecutionEnvironment(env);

        // 统一应用 Checkpoint 配置，保证流式作业一致性和恢复行为一致
        long checkpointInterval = configureCheckpointEnvironment(env);
        
        // 打印环境信息
        logEnvironmentInfo(parallelism, checkpointInterval, webUIPort);
        
        return env;
    }
    
    /**
     * 创建 StreamTableEnvironment (Table API / SQL)
     * 
     * @param jobName 作业名称(用于 Metrics 标识)
     * @param webUIPort Web UI 端口(避免端口冲突)
     * @return 配置好的 StreamTableEnvironment
     */
    public StreamTableEnvironment createTableEnvironment(String jobName, int webUIPort) {
        logger.info("==========================================");
        logger.info("创建 Flink Table Environment");
        logger.info("作业名称: {}", jobName);
        logger.info("==========================================");
        
        // 创建 Flink 配置
        Configuration flinkConfig = createFlinkConfiguration(jobName, webUIPort);
        
        // 创建执行环境
        StreamExecutionEnvironment env = StreamExecutionEnvironment.getExecutionEnvironment(flinkConfig);
        
        // Table 作业和 DataStream 作业共用同一套环境参数，避免配置行为不一致
        int parallelism = configureExecutionEnvironment(env);

        // Table 作业同样需要完整的 Checkpoint 配置，不能只开 interval
        long checkpointInterval = configureCheckpointEnvironment(env);
        
        // 创建 Table Environment
        StreamTableEnvironment tableEnv = StreamTableEnvironment.create(env);
        
        // 设置时区为中国时区(东八区) - 重要:确保日期计算正确
        tableEnv.getConfig().setLocalTimeZone(java.time.ZoneId.of("Asia/Shanghai"));
        
        // 打印环境信息
        logEnvironmentInfo(parallelism, checkpointInterval, webUIPort);
        
        return tableEnv;
    }
    
    /**
     * 创建 Flink Configuration
     * 
     * 配置内容:
     * - Web UI (端口、地址)
     * - Prometheus Metrics (Pushgateway)
     * - 通用 Metrics 选项
     * 
     * @param jobName 作业名称
     * @param webUIPort Web UI 端口
     * @return Flink Configuration
     */
    private Configuration createFlinkConfiguration(String jobName, int webUIPort) {
        Configuration flinkConfig = new Configuration();
        
        // 配置 Web UI
        configureWebUI(flinkConfig, webUIPort);
        
        // 配置 Prometheus Metrics
        configureMetrics(flinkConfig, jobName);

        // 配置执行层参数（运行模式、重启策略等）
        configureExecutionOptions(flinkConfig);

        // 配置与 Checkpoint 相关的高级参数（压缩、unaligned、外部化保留等）
        configureCheckpointOptions(flinkConfig);

        // 配置状态后端和状态存储路径
        // 说明：这里把 application-*.yml 中的 flink.state.* 真正注入到 Flink 运行时
        configureStateBackend(flinkConfig);

        // 配置故障恢复策略，避免依赖 Flink 集群默认值
        configureRestartStrategy(flinkConfig);
        
        return flinkConfig;
    }
    
    /**
     * 配置 Web UI
     * 
     * @param flinkConfig Flink 配置
     * @param webUIPort Web UI 端口
     */
    private void configureWebUI(Configuration flinkConfig, int webUIPort) {
        // 启用 Web UI
        flinkConfig.setBoolean("web.submit.enable", 
            config.getBoolean("flink.web.submit.enable", true));
        flinkConfig.setBoolean("web.cancel.enable", 
            config.getBoolean("flink.web.cancel.enable", true));
        
        // 设置端口
        flinkConfig.setInteger("rest.port", webUIPort);
        flinkConfig.setString("rest.address", 
            config.getString("flink.web.address", "0.0.0.0"));
        
        // 端口范围(字符串类型)
        flinkConfig.setString("rest.bind-port", webUIPort + "-" + (webUIPort + 10));
        
        // 启用 Flamegraph
        flinkConfig.setBoolean("rest.flamegraph.enabled", true);
        
        logger.info("Web UI 配置:");
        logger.info("  端口: {}", webUIPort);
        logger.info("  地址: http://localhost:{}", webUIPort);
    }
    
    /**
     * 配置 Prometheus Metrics
     * 
     * @param flinkConfig Flink 配置
     * @param jobName 作业名称
     */
    private void configureMetrics(Configuration flinkConfig, String jobName) {
        // 从配置文件读取 Pushgateway 地址,支持本地和 Docker 环境
        String pushgatewayHost = config.getString("application.metrics.pushgateway.host", "localhost");
        int pushgatewayPort = config.getInt("application.metrics.pushgateway.port", 9091);
        
        // 配置 Pushgateway Reporter
        MetricsConfig.configurePushgatewayReporter(
            flinkConfig,
            pushgatewayHost,
            pushgatewayPort,
            jobName
        );
        
        // 配置通用 Metrics 选项
        MetricsConfig.configureCommonMetrics(flinkConfig);
        
        logger.info("Metrics 配置:");
        logger.info("  Pushgateway: {}:{}", pushgatewayHost, pushgatewayPort);
        logger.info("  作业名称: {}", jobName);
    }

    /**
     * 配置执行层参数
     *
     * 说明：
     * 1. 这里主要放运行模式等“环境级”参数
     * 2. 并行度、buffer timeout、watermark 间隔会在 env 创建后再用 API 设置
     *
     * @param flinkConfig Flink 配置
     */
    private void configureExecutionOptions(Configuration flinkConfig) {
        String runtimeMode = normalizeRuntimeMode(
            config.getString("flink.execution.runtime-mode", "STREAMING")
        );
        flinkConfig.setString("execution.runtime-mode", runtimeMode);
    }

    /**
     * 将 execution 分组的配置真正应用到 StreamExecutionEnvironment
     *
     * @param env Flink 执行环境
     * @return 并行度
     */
    private int configureExecutionEnvironment(StreamExecutionEnvironment env) {
        // 推荐默认值：实时流式作业统一使用 STREAMING 模式
        String runtimeMode = normalizeRuntimeMode(
            config.getString("flink.execution.runtime-mode", "STREAMING")
        );
        env.setRuntimeMode(RuntimeExecutionMode.valueOf(runtimeMode));

        // 作业并行度：控制吞吐能力，是最常见的性能调优参数
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);

        // 最大并行度：影响 keyed state 的分片上限，建议尽早固定，避免后续状态迁移麻烦
        int maxParallelism = config.getInt("flink.execution.max-parallelism", 128);
        env.setMaxParallelism(maxParallelism);

        // Buffer timeout：越小延迟越低，越大吞吐越高；100ms 是比较均衡的默认值
        long bufferTimeoutMs = config.getLong("flink.execution.buffer-timeout-ms", 100L);
        env.setBufferTimeout(bufferTimeoutMs);

        // Watermark 周期：事件时间作业需要定期推进 watermark，200ms 是 Flink 常见默认值
        long autoWatermarkIntervalMs = config.getLong("flink.execution.auto-watermark-interval-ms", 200L);
        env.getConfig().setAutoWatermarkInterval(autoWatermarkIntervalMs);

        // 算子链配置：控制是否将可链接的算子合并在一起
        // false = 禁用算子链，Web UI 会显示详细的算子拆分，方便调试和监控
        // true = 启用算子链，性能优化，减少网络传输和序列化开销
        boolean operatorChaining = config.getBoolean("flink.execution.operator-chaining", true);
        if (!operatorChaining) {
            env.disableOperatorChaining();
            logger.info("算子链已禁用，Web UI 将显示详细的算子拆分");
        }

        logger.info("执行配置:");
        logger.info("  runtime-mode: {}", runtimeMode);
        logger.info("  parallelism: {}", parallelism);
        logger.info("  max-parallelism: {}", maxParallelism);
        logger.info("  buffer-timeout-ms: {}", bufferTimeoutMs);
        logger.info("  auto-watermark-interval-ms: {}", autoWatermarkIntervalMs);
        logger.info("  operator-chaining: {}", operatorChaining);

        return parallelism;
    }

    /**
     * 配置 Checkpoint 相关的高级参数
     *
     * 说明：
     * 1. interval 仍在 env 创建后通过 enableCheckpointing 显式打开
     * 2. 这里提前把高级选项放进 Configuration
     * 3. Duration 类型统一转为 "xxx ms" 字符串，兼容 Flink 配置解析
     *
     * @param flinkConfig Flink 配置
     */
    private void configureCheckpointOptions(Configuration flinkConfig) {
        String checkpointMode = normalizeCheckpointingMode(
            config.getString("flink.checkpoint.mode", "EXACTLY_ONCE")
        );
        int maxConcurrentCheckpoints = config.getInt("flink.checkpoint.max-concurrent", 1);
        boolean unalignedEnabled = shouldEnableUnalignedCheckpoint(
            checkpointMode,
            maxConcurrentCheckpoints,
            config.getBoolean("flink.checkpoint.unaligned.enabled", false)
        );

        flinkConfig.setString("execution.checkpointing.mode", checkpointMode);

        setDurationConfig(
            flinkConfig,
            "execution.checkpointing.interval",
            config.getLong("flink.checkpoint.interval", 60000L)
        );
        setDurationConfig(
            flinkConfig,
            "execution.checkpointing.timeout",
            config.getLong("flink.checkpoint.timeout", 300000L)
        );
        setDurationConfig(
            flinkConfig,
            "execution.checkpointing.min-pause",
            config.getLong("flink.checkpoint.min-pause", 10000L)
        );

        flinkConfig.setInteger(
            "execution.checkpointing.max-concurrent-checkpoints",
            maxConcurrentCheckpoints
        );
        flinkConfig.setInteger(
            "execution.checkpointing.tolerable-failed-checkpoints",
            config.getInt("flink.checkpoint.tolerable-failed-checkpoints", 3)
        );

        // 外部化 Checkpoint：控制取消作业后是否保留恢复点
        String retention = normalizeExternalizedRetention(
            config.getString("flink.checkpoint.externalized-retention", "DELETE_ON_CANCELLATION")
        );
        flinkConfig.setString("execution.checkpointing.externalized-checkpoint-retention", retention);

        // 快照压缩：通常建议开启，能减少远程状态存储占用
        flinkConfig.setBoolean(
            "execution.checkpointing.snapshot-compression",
            config.getBoolean("flink.checkpoint.snapshot-compression", true)
        );

        // 部分任务结束后仍允许做 checkpoint，Flink 1.17 默认为 true，这里显式写出方便理解
        flinkConfig.setBoolean(
            "execution.checkpointing.checkpoints-after-tasks-finish.enabled",
            config.getBoolean("flink.checkpoint.checkpoints-after-tasks-finish", true)
        );

        flinkConfig.setBoolean("execution.checkpointing.unaligned.enabled", unalignedEnabled);
        flinkConfig.setInteger(
            "execution.checkpointing.unaligned.max-subtasks-per-channel-state-file",
            config.getInt("flink.checkpoint.unaligned.max-subtasks-per-channel-state-file", 5)
        );
        setDurationConfig(
            flinkConfig,
            "execution.checkpointing.aligned-checkpoint-timeout",
            config.getLong("flink.checkpoint.unaligned.aligned-timeout-ms", 0L)
        );
    }

    /**
     * 将 checkpoint 分组配置应用到执行环境
     *
     * @param env Flink 执行环境
     * @return checkpoint 间隔
     */
    private long configureCheckpointEnvironment(StreamExecutionEnvironment env) {
        long checkpointInterval = config.getLong("flink.checkpoint.interval", 60000L);
        String checkpointMode = normalizeCheckpointingMode(
            config.getString("flink.checkpoint.mode", "EXACTLY_ONCE")
        );

        if (checkpointInterval > 0) {
            env.enableCheckpointing(
                checkpointInterval,
                CheckpointingMode.valueOf(checkpointMode)
            );
        } else {
            logger.warn("Checkpoint interval <= 0，当前作业不会开启 Checkpoint");
            return checkpointInterval;
        }

        CheckpointConfig checkpointConfig = env.getCheckpointConfig();

        long checkpointTimeout = config.getLong("flink.checkpoint.timeout", 300000L);
        checkpointConfig.setCheckpointTimeout(checkpointTimeout);

        long minPause = config.getLong("flink.checkpoint.min-pause", 10000L);
        checkpointConfig.setMinPauseBetweenCheckpoints(minPause);

        int maxConcurrent = config.getInt("flink.checkpoint.max-concurrent", 1);
        checkpointConfig.setMaxConcurrentCheckpoints(maxConcurrent);

        int tolerableFailedCheckpoints = config.getInt("flink.checkpoint.tolerable-failed-checkpoints", 3);
        checkpointConfig.setTolerableCheckpointFailureNumber(tolerableFailedCheckpoints);

        String retention = normalizeExternalizedRetention(
            config.getString("flink.checkpoint.externalized-retention", "DELETE_ON_CANCELLATION")
        );
        applyExternalizedCheckpointRetention(checkpointConfig, retention);

        boolean unalignedEnabled = shouldEnableUnalignedCheckpoint(
            checkpointMode,
            maxConcurrent,
            config.getBoolean("flink.checkpoint.unaligned.enabled", false)
        );
        if (unalignedEnabled) {
            checkpointConfig.enableUnalignedCheckpoints();
        }

        logger.info("Checkpoint 配置:");
        logger.info("  interval: {} ms", checkpointInterval);
        logger.info("  mode: {}", checkpointMode);
        logger.info("  timeout: {} ms", checkpointTimeout);
        logger.info("  min-pause: {} ms", minPause);
        logger.info("  max-concurrent: {}", maxConcurrent);
        logger.info("  tolerable-failed-checkpoints: {}", tolerableFailedCheckpoints);
        logger.info("  externalized-retention: {}", retention);
        logger.info("  unaligned.enabled: {}", unalignedEnabled);

        return checkpointInterval;
    }

    /**
     * 配置状态后端
     *
     * 说明：
     * 1. 从项目配置文件读取 flink.state.* 配置
     * 2. 转换为 Flink 1.17 官方配置项
     * 3. 避免配置文件中声明了状态后端，但运行时没有真正生效
     *
     * @param flinkConfig Flink 配置
     */
    private void configureStateBackend(Configuration flinkConfig) {
        // 从 YAML 读取状态后端类型，例如 rocksdb / hashmap
        String configuredBackend = config.getString("flink.state.backend", "");
        String normalizedBackend = normalizeStateBackend(configuredBackend);
        if (!normalizedBackend.isEmpty()) {
            flinkConfig.setString("state.backend.type", normalizedBackend);
        }

        // 从 YAML 读取 checkpoint 存储目录
        String checkpointDir = config.getString("flink.state.checkpoint-dir", "").trim();
        if (!checkpointDir.isEmpty()) {
            flinkConfig.setString("state.checkpoints.dir", checkpointDir);
        }

        // 从 YAML 读取 savepoint 存储目录
        String savepointDir = config.getString("flink.state.savepoint-dir", "").trim();
        if (!savepointDir.isEmpty()) {
            flinkConfig.setString("state.savepoints.dir", savepointDir);
        }

        // 可选：RocksDB 增量 checkpoint
        if (config.get("flink.state.incremental") != null) {
            flinkConfig.setBoolean(
                "state.backend.incremental",
                config.getBoolean("flink.state.incremental", false)
            );
        }

        // 可选：本地恢复
        if (config.get("flink.state.local-recovery") != null) {
            flinkConfig.setBoolean(
                "state.backend.local-recovery",
                config.getBoolean("flink.state.local-recovery", false)
            );
        }

        logger.info("状态后端配置:");
        logger.info("  backend: {}", normalizedBackend.isEmpty() ? "未显式配置（使用集群配置或 Flink 默认值）" : normalizedBackend);
        logger.info("  checkpoint-dir: {}", checkpointDir.isEmpty() ? "未配置" : checkpointDir);
        logger.info("  savepoint-dir: {}", savepointDir.isEmpty() ? "未配置" : savepointDir);
        if (config.get("flink.state.incremental") != null) {
            logger.info("  incremental: {}", config.getBoolean("flink.state.incremental", false));
        }
        if (config.get("flink.state.local-recovery") != null) {
            logger.info("  local-recovery: {}", config.getBoolean("flink.state.local-recovery", false));
        }
    }

    /**
     * 标准化状态后端名称
     *
     * Flink 1.17 推荐使用 hashmap / rocksdb。
     * 为了兼容旧写法，如果配置的是 filesystem，则映射到 hashmap。
     *
     * @param backend 原始配置
     * @return 标准化后的状态后端名称
     */
    private String normalizeStateBackend(String backend) {
        if (backend == null) {
            return "";
        }

        String normalizedBackend = backend.trim().toLowerCase(Locale.ROOT);
        if (normalizedBackend.isEmpty()) {
            return "";
        }

        if ("filesystem".equals(normalizedBackend)) {
            logger.warn("检测到旧状态后端配置 filesystem，已自动映射为 Flink 1.17 推荐的 hashmap");
            return "hashmap";
        }

        return normalizedBackend;
    }

    /**
     * 配置重启策略
     *
     * 推荐说明：
     * 1. dev 环境更适合 fixed-delay，方便快速发现问题
     * 2. docker / prod 长跑作业更适合 failure-rate，避免短时抖动直接拉挂
     *
     * @param flinkConfig Flink 配置
     */
    private void configureRestartStrategy(Configuration flinkConfig) {
        String restartStrategy = normalizeRestartStrategy(
            config.getString("flink.restart.strategy", "fixed-delay")
        );
        flinkConfig.setString("restart-strategy.type", restartStrategy);

        if ("fixed-delay".equals(restartStrategy)) {
            int attempts = config.getInt("flink.restart.fixed-delay.attempts", 3);
            long delayMs = config.getLong("flink.restart.fixed-delay.delay-ms", 10000L);

            flinkConfig.setInteger("restart-strategy.fixed-delay.attempts", attempts);
            setDurationConfig(flinkConfig, "restart-strategy.fixed-delay.delay", delayMs);

            logger.info("重启策略配置:");
            logger.info("  strategy: fixed-delay");
            logger.info("  attempts: {}", attempts);
            logger.info("  delay-ms: {}", delayMs);
            return;
        }

        if ("failure-rate".equals(restartStrategy)) {
            int maxFailuresPerInterval = config.getInt("flink.restart.failure-rate.max-failures-per-interval", 3);
            long failureRateIntervalMs = config.getLong("flink.restart.failure-rate.failure-rate-interval-ms", 600000L);
            long delayMs = config.getLong("flink.restart.failure-rate.delay-ms", 30000L);

            flinkConfig.setInteger(
                "restart-strategy.failure-rate.max-failures-per-interval",
                maxFailuresPerInterval
            );
            setDurationConfig(
                flinkConfig,
                "restart-strategy.failure-rate.failure-rate-interval",
                failureRateIntervalMs
            );
            setDurationConfig(
                flinkConfig,
                "restart-strategy.failure-rate.delay",
                delayMs
            );

            logger.info("重启策略配置:");
            logger.info("  strategy: failure-rate");
            logger.info("  max-failures-per-interval: {}", maxFailuresPerInterval);
            logger.info("  failure-rate-interval-ms: {}", failureRateIntervalMs);
            logger.info("  delay-ms: {}", delayMs);
            return;
        }

        logger.info("重启策略配置:");
        logger.info("  strategy: {}", restartStrategy);
    }

    /**
     * 标准化运行模式
     */
    private String normalizeRuntimeMode(String runtimeMode) {
        if (runtimeMode == null || runtimeMode.trim().isEmpty()) {
            return "STREAMING";
        }

        String normalizedMode = runtimeMode.trim().toUpperCase(Locale.ROOT);
        if ("STREAMING".equals(normalizedMode)
            || "BATCH".equals(normalizedMode)
            || "AUTOMATIC".equals(normalizedMode)) {
            return normalizedMode;
        }

        logger.warn("未知 runtime-mode 配置: {}，已回退到 STREAMING", runtimeMode);
        return "STREAMING";
    }

    /**
     * 标准化 Checkpoint 模式
     */
    private String normalizeCheckpointingMode(String checkpointMode) {
        if (checkpointMode == null || checkpointMode.trim().isEmpty()) {
            return "EXACTLY_ONCE";
        }

        String normalizedMode = checkpointMode.trim().toUpperCase(Locale.ROOT);
        if ("EXACTLY_ONCE".equals(normalizedMode) || "AT_LEAST_ONCE".equals(normalizedMode)) {
            return normalizedMode;
        }

        logger.warn("未知 checkpoint mode 配置: {}，已回退到 EXACTLY_ONCE", checkpointMode);
        return "EXACTLY_ONCE";
    }

    /**
     * 标准化外部化 Checkpoint 清理策略
     */
    private String normalizeExternalizedRetention(String retention) {
        if (retention == null || retention.trim().isEmpty()) {
            return "DELETE_ON_CANCELLATION";
        }

        String normalizedRetention = retention.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if ("RETAIN_ON_CANCELLATION".equals(normalizedRetention)
            || "DELETE_ON_CANCELLATION".equals(normalizedRetention)
            || "NO_EXTERNALIZED_CHECKPOINTS".equals(normalizedRetention)) {
            return normalizedRetention;
        }

        logger.warn("未知 externalized checkpoint retention 配置: {}，已回退到 DELETE_ON_CANCELLATION", retention);
        return "DELETE_ON_CANCELLATION";
    }

    /**
     * 将外部化 Checkpoint 清理策略应用到 CheckpointConfig
     */
    private void applyExternalizedCheckpointRetention(CheckpointConfig checkpointConfig, String retention) {
        if ("RETAIN_ON_CANCELLATION".equals(retention)) {
            checkpointConfig.enableExternalizedCheckpoints(
                CheckpointConfig.ExternalizedCheckpointCleanup.RETAIN_ON_CANCELLATION
            );
            return;
        }

        if ("DELETE_ON_CANCELLATION".equals(retention)) {
            checkpointConfig.enableExternalizedCheckpoints(
                CheckpointConfig.ExternalizedCheckpointCleanup.DELETE_ON_CANCELLATION
            );
        }
    }

    /**
     * 标准化重启策略名称
     */
    private String normalizeRestartStrategy(String restartStrategy) {
        if (restartStrategy == null || restartStrategy.trim().isEmpty()) {
            return "fixed-delay";
        }

        String normalizedStrategy = restartStrategy.trim().toLowerCase(Locale.ROOT);
        if ("fixeddelay".equals(normalizedStrategy)) {
            return "fixed-delay";
        }
        if ("failurerate".equals(normalizedStrategy)) {
            return "failure-rate";
        }
        if ("none".equals(normalizedStrategy) || "off".equals(normalizedStrategy) || "disable".equals(normalizedStrategy)) {
            return "none";
        }

        if ("fixed-delay".equals(normalizedStrategy) || "failure-rate".equals(normalizedStrategy)) {
            return normalizedStrategy;
        }

        logger.warn("未知 restart strategy 配置: {}，已回退到 fixed-delay", restartStrategy);
        return "fixed-delay";
    }

    /**
     * 判断是否允许启用 unaligned checkpoint
     *
     * Flink 要求：
     * 1. 必须是 EXACTLY_ONCE
     * 2. max concurrent checkpoints 必须为 1
     */
    private boolean shouldEnableUnalignedCheckpoint(
        String checkpointMode,
        int maxConcurrentCheckpoints,
        boolean requestedUnaligned
    ) {
        if (!requestedUnaligned) {
            return false;
        }

        if (!"EXACTLY_ONCE".equals(checkpointMode) || maxConcurrentCheckpoints != 1) {
            logger.warn(
                "unaligned checkpoint 需要 EXACTLY_ONCE 且 max-concurrent=1，当前 mode={}, max-concurrent={}，已自动关闭",
                checkpointMode,
                maxConcurrentCheckpoints
            );
            return false;
        }

        return true;
    }

    /**
     * 设置 Duration 类型配置项
     *
     * Flink 的 Duration 配置支持字符串格式，这里统一使用 ms，便于和项目 YAML 保持一致。
     */
    private void setDurationConfig(Configuration flinkConfig, String key, long millis) {
        flinkConfig.setString(key, millis + " ms");
    }
    
    /**
     * 打印环境信息
     * 
     * @param parallelism 并行度
     * @param checkpointInterval Checkpoint 间隔
     * @param webUIPort Web UI 端口
     */
    private void logEnvironmentInfo(int parallelism, long checkpointInterval, int webUIPort) {
        logger.info("==========================================");
        logger.info("Flink Environment 配置:");
        logger.info("  并行度: {}", parallelism);
        logger.info("  Checkpoint 间隔: {} ms", checkpointInterval);
        logger.info("  Web UI: http://localhost:{}", webUIPort);
        logger.info("  时区: Asia/Shanghai (东八区)");
        logger.info("==========================================");
    }
}
