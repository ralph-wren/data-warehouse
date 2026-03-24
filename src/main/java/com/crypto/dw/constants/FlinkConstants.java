package com.crypto.dw.constants;

/**
 * Flink 常量类
 * 
 * 集中管理 Flink 作业中使用的常量，避免魔法数字和字符串分散在代码中。
 * 
 * 优势：
 * 1. 提高代码可读性：常量名称清晰表达含义
 * 2. 便于维护：修改常量值只需在一处修改
 * 3. 避免错误：减少硬编码导致的拼写错误
 * 4. 统一规范：团队成员使用相同的常量定义
 * 
 * 使用示例：
 * <pre>
 * // 使用常量替代魔法数字
 * long checkpointInterval = FlinkConstants.DEFAULT_CHECKPOINT_INTERVAL;
 * 
 * // 使用常量替代硬编码字符串
 * String dataSource = FlinkConstants.DEFAULT_DATA_SOURCE;
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class FlinkConstants {
    
    // ==================== Web UI 配置 ====================
    
    /**
     * 默认 Web UI 端口
     * 注意：实际使用时应该从配置文件读取，这里只是默认值
     */
    public static final int DEFAULT_WEB_PORT = 8080;
    
    /**
     * ODS 作业 Web UI 端口
     */
    public static final int ODS_WEB_PORT = 8083;
    
    /**
     * DWD 作业 Web UI 端口
     */
    public static final int DWD_WEB_PORT = 8082;
    
    /**
     * DWS 1分钟作业 Web UI 端口
     */
    public static final int DWS_1MIN_WEB_PORT = 8084;
    
    /**
     * DWS 5分钟作业 Web UI 端口
     */
    public static final int DWS_5MIN_WEB_PORT = 8085;
    
    // ==================== Checkpoint 配置 ====================
    
    /**
     * 默认 Checkpoint 间隔（毫秒）
     * 60 秒 = 60000 毫秒
     */
    public static final long DEFAULT_CHECKPOINT_INTERVAL = 60000L;
    
    /**
     * 默认 Checkpoint 超时时间（毫秒）
     * 5 分钟 = 300000 毫秒
     */
    public static final long DEFAULT_CHECKPOINT_TIMEOUT = 300000L;
    
    /**
     * 默认 Checkpoint 最小暂停时间（毫秒）
     * 10 秒 = 10000 毫秒
     */
    public static final long DEFAULT_CHECKPOINT_MIN_PAUSE = 10000L;
    
    /**
     * 默认最大并发 Checkpoint 数量
     * 建议设置为 1，避免状态后端压力过大
     */
    public static final int DEFAULT_MAX_CONCURRENT_CHECKPOINTS = 1;
    
    /**
     * 默认容忍的 Checkpoint 失败次数
     * 允许 3 次失败，避免短时抖动导致作业重启
     */
    public static final int DEFAULT_TOLERABLE_FAILED_CHECKPOINTS = 3;
    
    // ==================== 批量写入配置 ====================
    
    /**
     * 默认批量大小（行数）
     * 每批次写入 1000 行数据
     */
    public static final int DEFAULT_BATCH_SIZE = 1000;
    
    /**
     * 默认批量间隔（毫秒）
     * 5 秒 = 5000 毫秒
     */
    public static final int DEFAULT_BATCH_INTERVAL_MS = 5000;
    
    /**
     * 默认最大重试次数
     * 失败后最多重试 3 次
     */
    public static final int DEFAULT_MAX_RETRIES = 3;
    
    /**
     * 默认缓冲区大小（字节）
     * 10 MB = 10 * 1024 * 1024 字节
     */
    public static final int DEFAULT_BUFFER_SIZE = 10 * 1024 * 1024;
    
    /**
     * 默认缓冲区数量
     * 使用 3 个缓冲区，提高并发写入性能
     */
    public static final int DEFAULT_BUFFER_COUNT = 3;
    
    // ==================== 数据质量配置 ====================
    
    /**
     * 时间戳容忍度（毫秒）
     * 1 小时 = 3600000 毫秒
     * 超过此时间范围的数据被认为是异常数据
     */
    public static final long TIMESTAMP_TOLERANCE_MS = 3600000L;
    
    /**
     * 最小价格
     * 价格必须大于此值才被认为是有效数据
     */
    public static final double MIN_PRICE = 0.00000001;
    
    /**
     * 最大价格
     * 价格必须小于此值才被认为是有效数据
     */
    public static final double MAX_PRICE = 10000000.0;
    
    /**
     * 默认数据源
     * OKX 交易所
     */
    public static final String DEFAULT_DATA_SOURCE = "OKX";
    
    /**
     * 默认最大过滤比例
     * 允许 10% 的数据被过滤
     */
    public static final double DEFAULT_MAX_FILTER_RATIO = 0.1;
    
    // ==================== Consumer Group ID ====================
    
    /**
     * ODS DataStream 作业的 Consumer Group ID
     */
    public static final String ODS_CONSUMER_GROUP = "flink-ods-datastream-consumer";
    
    /**
     * DWD SQL 作业的 Consumer Group ID
     */
    public static final String DWD_CONSUMER_GROUP = "flink-dwd-consumer";
    
    /**
     * DWS 1分钟作业的 Consumer Group ID
     */
    public static final String DWS_1MIN_CONSUMER_GROUP = "flink-dws-1min-consumer";
    
    /**
     * DWS 5分钟作业的 Consumer Group ID
     */
    public static final String DWS_5MIN_CONSUMER_GROUP = "flink-dws-5min-consumer";
    
    // ==================== 时区配置 ====================
    
    /**
     * 中国时区（东八区）
     * 用于时间转换和日期计算
     */
    public static final String CHINA_TIMEZONE = "Asia/Shanghai";
    
    /**
     * UTC 时区
     * 用于国际化场景
     */
    public static final String UTC_TIMEZONE = "UTC";
    
    // ==================== 并行度配置 ====================
    
    /**
     * 默认并行度
     * 根据 CPU 核心数调整，建议 4-8
     */
    public static final int DEFAULT_PARALLELISM = 4;
    
    /**
     * 默认最大并行度
     * 状态分片上限，建议提前固定，避免后续扩缩容问题
     */
    public static final int DEFAULT_MAX_PARALLELISM = 128;
    
    // ==================== Watermark 配置 ====================
    
    /**
     * 默认 Watermark 延迟（秒）
     * 允许 5 秒的乱序数据
     */
    public static final int DEFAULT_WATERMARK_DELAY_SECONDS = 5;
    
    /**
     * 默认 Watermark 间隔（毫秒）
     * 200 毫秒推进一次 Watermark
     */
    public static final long DEFAULT_WATERMARK_INTERVAL_MS = 200L;
    
    // ==================== 私有构造函数 ====================
    
    /**
     * 私有构造函数，防止实例化
     * 常量类不应该被实例化，所有成员都是静态的
     */
    private FlinkConstants() {
        throw new AssertionError("常量类不能被实例化");
    }
}
