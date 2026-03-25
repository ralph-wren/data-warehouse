package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.KafkaSourceFactory;
import com.crypto.dw.redis.RedisConnectionManager;
import com.crypto.dw.strategy.StrategyRegisterCenter;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.*;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.source.SourceFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * ADS层 - 实时技术指标计算系统
 * - 使用TA4J库进行技术指标计算
 * - 广播状态实现动态指标配置
 * - 键控状态维护每个交易对的K线序列
 * - 支持指标组合和策略回测
 * - 精准一次性语义保证数据准确性
 * 
 * @author Crypto DW Team
 * @date 2026-03-25
 */
public class FlinkADSTechnicalIndicatorsJob {
    
    private static final Logger LOG = LoggerFactory.getLogger(FlinkADSTechnicalIndicatorsJob.class);
    
    // 广播状态描述符 - 用于动态更新指标配置
    private static final MapStateDescriptor<String, IndicatorConfig> INDICATOR_CONFIG_DESC =
        new MapStateDescriptor<>(
            "indicator-config",
            TypeInformation.of(String.class),
            TypeInformation.of(IndicatorConfig.class)
        );
    
    public static void main(String[] args) throws Exception {
        // ============================================================================
        // 步骤 1: 加载配置
        // ============================================================================
        // 从配置文件（application-{env}.yml）加载所有配置项
        // 支持环境变量引用，如 ${DORIS_DATABASE}
        ConfigLoader config = ConfigLoader.getInstance();
        
        // ============================================================================
        // 步骤 2: 创建Flink执行环境
        // ============================================================================
        // 使用工厂类创建StreamExecutionEnvironment，统一管理环境配置
        // - 设置并行度、Checkpoint、状态后端等
        // - 启用Web UI（端口8085）用于监控作业状态
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment(
            "flink-ads-technical-indicators-job",  // 作业名称
            8085                                    // Web UI端口
        );
        
        // ============================================================================
        // 步骤 3: 创建Kafka Source - 订阅实时行情数据
        // ============================================================================
        // 从Kafka读取crypto_ticker_spot主题的实时行情数据
        // 数据格式：{"inst_id":"BTC-USDT","last":"50000","vol_24h":"1000",...}
        KafkaSourceFactory kafkaFactory = new KafkaSourceFactory(config);
        KafkaSource<String> tickerSource = kafkaFactory.createKafkaSource(
            "crypto_ticker_spot",                          // Kafka主题名称
            "flink-ads-technical-indicators-consumer"      // 消费者组ID
        );
        
        // ============================================================================
        // 步骤 4: 读取ticker数据流并设置水印策略
        // ============================================================================
        // 水印策略：允许5秒的乱序数据
        // 时间戳提取：从JSON中提取ts字段作为事件时间
        DataStream<String> tickerStream = env
            .fromSource(
                tickerSource,
                WatermarkStrategy
                    .<String>forBoundedOutOfOrderness(Duration.ofSeconds(5))  // 允许5秒乱序
                    .withTimestampAssigner((event, timestamp) -> {
                        try {
                            // 从JSON中提取时间戳字段
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode node = mapper.readTree(event);
                            return node.get("ts").asLong();  // 毫秒级时间戳
                        } catch (Exception e) {
                            // 解析失败时使用当前系统时间
                            return System.currentTimeMillis();
                        }
                    }),
                "Kafka Ticker Source"  // Source算子名称
            );
        
        // ============================================================================
        // 步骤 5: 解析JSON并转换为TickerData对象
        // ============================================================================
        // 将JSON字符串解析为结构化的TickerData对象
        // 提取关键字段：symbol（交易对）、price（价格）、volume（成交量）等
        SingleOutputStreamOperator<TickerData> tickerDataStream = tickerStream
            .map(new TickerDataParser())  // 使用自定义解析器
            .name("Parse Ticker Data");   // 算子名称
        
        // ============================================================================
        // 步骤 6: 创建广播流 - 从Redis读取策略配置
        // ============================================================================
        // 定期从Redis读取策略配置（每30秒）
        // 配置格式：indicator:config:{configId}
        // 支持动态添加/删除策略，无需重启作业
        DataStream<IndicatorConfig> indicatorConfigStream = env
            .addSource(new IndicatorConfigSource(config))  // 自定义Source
            .name("Indicator Config Source");              // Source名称
        
        // 将配置流转换为广播流，所有Task共享配置
        BroadcastStream<IndicatorConfig> configBroadcast = indicatorConfigStream
            .broadcast(INDICATOR_CONFIG_DESC);  // 使用广播状态描述符
        
        // ============================================================================
        // 步骤 7: 关联ticker数据和策略配置，计算技术指标
        // ============================================================================
        // 核心处理逻辑：
        // 1. 按交易对（symbol）分组，每个交易对独立处理
        // 2. 连接数据流和广播流
        // 3. 使用KeyedBroadcastProcessFunction处理：
        //    - processElement: 处理ticker数据，聚合K线，计算指标
        //    - processBroadcastElement: 更新策略配置
        SingleOutputStreamOperator<IndicatorResult> indicatorResults = tickerDataStream
            .keyBy(data -> data.symbol)           // 按交易对分组
            .connect(configBroadcast)             // 连接广播流
            .process(new TechnicalIndicatorCalculator())  // 核心计算逻辑
            .name("Calculate Technical Indicators");      // 算子名称
        
        // ============================================================================
        // 步骤 8: 写入Doris数据仓库
        // ============================================================================
        // 将计算结果写入Doris的ads_technical_indicators表
        // 使用DorisSinkFactory统一管理Sink配置
        // 支持批量写入、自动重试、精准一次性语义
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        indicatorResults
            .map(result -> result.toJson())  // 转换为JSON字符串
            .sinkTo(dorisSinkFactory.createDorisSink(
                null,                           // database从环境变量/配置文件获取
                "ads_technical_indicators",     // 表名
                "ads_technical_indicator"       // Label前缀（用于去重）
            ))
            .name("Sink to Doris - Technical Indicators");  // Sink名称
        
        // ============================================================================
        // 步骤 9: 执行作业
        // ============================================================================
        // 提交作业到Flink集群执行
        // 作业会持续运行，实时处理数据流
        env.execute("Flink ADS Technical Indicators Job");
    }
    
    /**
     * Ticker数据实体
     * 
     * 用于存储从Kafka读取的实时行情数据
     * 
     * 数据来源：crypto_ticker_spot主题
     * 数据格式：JSON字符串，包含交易对、价格、成交量等信息
     * 
     * 字段说明：
     * - symbol: 交易对（如 BTC-USDT）
     * - exchange: 交易所（如 OKX）
     * - price: 当前价格（最新成交价）
     * - volume: 24小时成交量
     * - high: 24小时最高价
     * - low: 24小时最低价
     * - timestamp: 数据时间戳（毫秒）
     * 
     * 使用场景：
     * 1. 作为K线聚合的输入数据
     * 2. 提供实时价格信息
     * 3. 用于技术指标计算
     */
    public static class TickerData {
        public String symbol;        // 交易对（如 BTC-USDT）
        public String exchange;      // 交易所（如 OKX）
        public BigDecimal price;     // 当前价格
        public BigDecimal volume;    // 24小时成交量
        public BigDecimal high;      // 24小时最高价
        public BigDecimal low;       // 24小时最低价
        public Long timestamp;       // 数据时间戳（毫秒）
        
        public TickerData() {}
        
        public TickerData(String symbol, String exchange, BigDecimal price,
                         BigDecimal volume, BigDecimal high, BigDecimal low, Long timestamp) {
            this.symbol = symbol;
            this.exchange = exchange;
            this.price = price;
            this.volume = volume;
            this.high = high;
            this.low = low;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 指标配置实体
     * 
     * 用于存储从Redis读取的策略配置信息
     * 
     * 数据来源：Redis键 indicator:config:{configId}
     * 数据格式：JSON字符串
     * 
     * 字段说明：
     * - configId: 配置唯一标识（如 config_001）
     * - symbol: 交易对（"*" 表示匹配所有交易对，"BTC-USDT" 表示特定交易对）
     * - indicatorType: 指标类型（MA, EMA, RSI, MACD, BOLL, KDJ, ATR, CUSTOM, STRATEGY）
     * - indicatorName: 指标名称（用于显示和日志）
     * - params: 指标参数（如 {"period": "14", "threshold": "70"}）
     * - customClass: 自定义指标类名（可选，用于扩展自定义指标）
     * - strategyType: 策略类型（对应StrategyRegisterCenter中的130+种策略）
     * - enabled: 是否启用（true=启用，false=禁用）
     * - updateTime: 配置更新时间（毫秒时间戳）
     * 
     * 使用场景：
     * 1. 通过广播状态分发到所有Task
     * 2. 动态添加/删除策略，无需重启作业
     * 3. 支持通配符匹配多个交易对
     * 
     * 配置示例：
     * {
     *   "config_id": "config_001",
     *   "symbol": "*",
     *   "indicator_type": "STRATEGY",
     *   "indicator_name": "RSI Strategy",
     *   "strategy_type": "STRATEGY_RSI",
     *   "params": {"period": "14"},
     *   "enabled": true
     * }
     */
    public static class IndicatorConfig implements java.io.Serializable {
        public String configId;           // 配置ID（唯一标识）
        public String symbol;             // 交易对（*表示所有）
        public String indicatorType;      // 指标类型：MA, EMA, RSI, MACD, BOLL, KDJ, ATR, CUSTOM, STRATEGY
        public String indicatorName;      // 指标名称（用于显示）
        public Map<String, String> params; // 指标参数（如周期、阈值等）
        public String customClass;        // 自定义指标类名（可选）
        public String strategyType;       // 策略类型（对应StrategyRegisterCenter中的策略）
        public Boolean enabled;           // 是否启用
        public Long updateTime;           // 更新时间（毫秒时间戳）
        
        public IndicatorConfig() {}
        
        public IndicatorConfig(String configId, String symbol, String indicatorType,
                              String indicatorName, Map<String, String> params,
                              String customClass, Boolean enabled, Long updateTime) {
            this.configId = configId;
            this.symbol = symbol;
            this.indicatorType = indicatorType;
            this.indicatorName = indicatorName;
            this.params = params;
            this.customClass = customClass;
            this.enabled = enabled;
            this.updateTime = updateTime;
        }
    }
    
    /**
     * 指标计算结果实体（包含交易信号）
     * 
     * 用于存储技术指标计算结果和交易信号
     * 
     * 输出目标：Doris表 ads_technical_indicators
     * 输出格式：JSON字符串
     * 
     * 字段说明：
     * - symbol: 交易对（如 BTC-USDT）
     * - exchange: 交易所（如 OKX）
     * - indicatorType: 指标类型（MA, EMA, RSI, MACD等）
     * - indicatorName: 指标名称（用于显示）
     * - indicatorValue: 指标值（主要指标值）
     * - extraValues: 额外的值（如MACD的DIF、DEA、MACD三个值）
     * - signalType: 交易信号（BUY=买入, SELL=卖出, HOLD=持有）
     * - signalReason: 信号原因（说明为什么产生这个信号）
     * - signalStrength: 信号强度（0-100，数值越大信号越强）
     * - currentPrice: 当前价格（用于后续分析）
     * - timestamp: 时间戳（毫秒）
     * - createTime: 创建时间（格式化字符串）
     * 
     * 使用场景：
     * 1. 存储技术指标计算结果
     * 2. 生成交易信号供策略使用
     * 3. 用于回测和分析
     * 
     * 信号类型说明：
     * - BUY: 策略的shouldEnter()返回true，建议买入
     * - SELL: 策略的shouldExit()返回true，建议卖出
     * - HOLD: 都不满足，建议持有
     */
    public static class IndicatorResult {
        public String symbol;                       // 交易对
        public String exchange;                     // 交易所
        public String indicatorType;                // 指标类型
        public String indicatorName;                // 指标名称
        public BigDecimal indicatorValue;           // 指标值
        public Map<String, BigDecimal> extraValues; // 额外的值（如MACD的DIF、DEA、MACD）
        
        // 交易信号相关字段
        public String signalType;                   // 交易信号：BUY, SELL, HOLD
        public String signalReason;                 // 信号原因（基于哪个指标规则）
        public BigDecimal signalStrength;           // 信号强度 0-100
        public BigDecimal currentPrice;             // 当前价格
        
        public Long timestamp;                      // 时间戳（毫秒）
        public String createTime;                   // 创建时间（格式化字符串）
        
        public IndicatorResult() {}
        
        /**
         * 转换为JSON字符串
         * 
         * 用于写入Doris数据仓库
         * 
         * JSON格式示例：
         * {
         *   "symbol": "BTC-USDT",
         *   "exchange": "OKX",
         *   "indicator_type": "STRATEGY",
         *   "indicator_name": "RSI Strategy",
         *   "indicator_value": 50000.00,
         *   "extra_values": {"rsi": 65.5},
         *   "signal_type": "BUY",
         *   "signal_reason": "RSI Strategy entry signal",
         *   "signal_strength": 80,
         *   "current_price": 50000.00,
         *   "timestamp": 1711353600000,
         *   "create_time": "2026-03-25 14:00:00"
         * }
         * 
         * @return JSON字符串
         */
        public String toJson() {
            StringBuilder json = new StringBuilder();
            json.append("{");
            json.append("\"symbol\":\"").append(symbol).append("\",");
            json.append("\"exchange\":\"").append(exchange).append("\",");
            json.append("\"indicator_type\":\"").append(indicatorType).append("\",");
            json.append("\"indicator_name\":\"").append(indicatorName).append("\",");
            json.append("\"indicator_value\":").append(indicatorValue).append(",");
            
            // 额外的值
            if (extraValues != null && !extraValues.isEmpty()) {
                json.append("\"extra_values\":{");
                boolean first = true;
                for (Map.Entry<String, BigDecimal> entry : extraValues.entrySet()) {
                    if (!first) json.append(",");
                    json.append("\"").append(entry.getKey()).append("\":").append(entry.getValue());
                    first = false;
                }
                json.append("},");
            } else {
                json.append("\"extra_values\":null,");
            }
            
            // 交易信号字段
            json.append("\"signal_type\":\"").append(signalType != null ? signalType : "HOLD").append("\",");
            json.append("\"signal_reason\":\"").append(signalReason != null ? signalReason : "").append("\",");
            json.append("\"signal_strength\":").append(signalStrength != null ? signalStrength : 0).append(",");
            json.append("\"current_price\":").append(currentPrice != null ? currentPrice : 0).append(",");
            
            json.append("\"timestamp\":").append(timestamp).append(",");
            json.append("\"create_time\":\"").append(createTime).append("\"");
            json.append("}");
            
            return json.toString();
        }
    }

    
    /**
     * Ticker数据解析器
     * 
     * 功能：将Kafka中的JSON字符串解析为TickerData对象
     * 
     * 输入格式：
     * {
     *   "inst_id": "BTC-USDT",
     *   "last": "50000.00",
     *   "vol_24h": "1000.5",
     *   "high_24h": "51000.00",
     *   "low_24h": "49000.00",
     *   "ts": 1711353600000,
     *   "exchange": "OKX"
     * }
     * 
     * 输出：TickerData对象
     * 
     * 异常处理：
     * - 解析失败时记录错误日志并抛出异常
     * - 缺失字段使用默认值（如exchange默认为"OKX"）
     */
    public static class TickerDataParser extends RichMapFunction<String, TickerData> {
        private transient ObjectMapper objectMapper;
        
        /**
         * 初始化方法
         * 在Task启动时调用一次
         */
        @Override
        public void open(Configuration parameters) {
            objectMapper = new ObjectMapper();
        }
        
        /**
         * 解析JSON字符串为TickerData对象
         * 
         * @param value JSON字符串
         * @return TickerData对象
         * @throws Exception 解析失败时抛出异常
         */
        @Override
        public TickerData map(String value) throws Exception {
            try {
                JsonNode node = objectMapper.readTree(value);
                
                return new TickerData(
                    node.get("inst_id").asText(),
                    node.has("exchange") ? node.get("exchange").asText() : "OKX",
                    new BigDecimal(node.get("last").asText()),
                    new BigDecimal(node.has("vol_24h") ? node.get("vol_24h").asText() : "0"),
                    new BigDecimal(node.has("high_24h") ? node.get("high_24h").asText() : "0"),
                    new BigDecimal(node.has("low_24h") ? node.get("low_24h").asText() : "0"),
                    node.get("ts").asLong()
                );
            } catch (Exception e) {
                LOG.error("Failed to parse ticker data: {}", value, e);
                throw e;
            }
        }
    }
    
    /**
     * 指标配置数据源
     * 
     * 功能：从Redis定期读取指标配置，支持动态更新
     * 
     * 数据来源：Redis键 indicator:config:*
     * 更新频率：每30秒
     * 
     * 工作流程：
     * 1. 连接Redis
     * 2. 扫描所有 indicator:config:* 键
     * 3. 读取每个配置的JSON数据
     * 4. 解析为IndicatorConfig对象
     * 5. 发送到广播流
     * 6. 等待30秒后重复
     * 
     * 配置格式示例：
     * Redis Key: indicator:config:config_001
     * Redis Value: {
     *   "config_id": "config_001",
     *   "symbol": "*",
     *   "indicator_type": "STRATEGY",
     *   "indicator_name": "RSI Strategy",
     *   "strategy_type": "STRATEGY_RSI",
     *   "params": {"period": "14"},
     *   "enabled": true
     * }
     * 
     * 优点：
     * - 动态配置：无需重启作业即可添加/删除策略
     * - 广播机制：配置自动分发到所有Task
     * - 容错性：Redis连接失败时记录日志并继续运行
     */
    public static class IndicatorConfigSource implements SourceFunction<IndicatorConfig> {
        
        private volatile boolean isRunning = true;           // 运行标志
        private transient RedisConnectionManager redisManager; // Redis连接管理器
        private final ConfigLoader config;                   // 配置加载器
        
        public IndicatorConfigSource(ConfigLoader config) {
            this.config = config;
        }
        
        /**
         * 主运行方法
         * 
         * 持续运行，定期从Redis读取配置并发送到下游
         * 
         * @param ctx Source上下文，用于发送数据
         * @throws Exception 运行异常
         */
        @Override
        public void run(SourceContext<IndicatorConfig> ctx) throws Exception {
            // 初始化Redis连接管理器
            redisManager = new RedisConnectionManager(config);
            
            while (isRunning) {
                try (Jedis jedis = redisManager.getConnection()) {
                    // 从Redis读取所有指标配置
                    // 使用keys命令扫描所有 indicator:config:* 键
                    Set<String> keys = jedis.keys("indicator:config:*");
                    
                    LOG.info("Found {} indicator configs in Redis", keys.size());
                    
                    // 遍历所有配置键
                    for (String key : keys) {
                        String configJson = jedis.get(key);
                        if (configJson != null) {
                            // 解析JSON配置
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode node = mapper.readTree(configJson);
                            
                            // 解析参数字段（如 {"period": "14", "threshold": "70"}）
                            Map<String, String> params = new HashMap<>();
                            if (node.has("params")) {
                                JsonNode paramsNode = node.get("params");
                                paramsNode.fields().forEachRemaining(entry -> 
                                    params.put(entry.getKey(), entry.getValue().asText())
                                );
                            }
                            
                            // 创建IndicatorConfig对象
                            IndicatorConfig config = new IndicatorConfig(
                                node.get("config_id").asText(),
                                node.get("symbol").asText(),
                                node.get("indicator_type").asText(),
                                node.get("indicator_name").asText(),
                                params,
                                node.has("custom_class") ? node.get("custom_class").asText() : null,
                                node.get("enabled").asBoolean(),
                                System.currentTimeMillis()
                            );
                            
                            // 发送配置到广播流
                            ctx.collect(config);
                            
                            LOG.debug("Loaded config: {} for symbol: {}", 
                                config.indicatorName, config.symbol);
                        }
                    }
                } catch (Exception e) {
                    // Redis连接失败时记录错误日志，但不中断作业
                    LOG.error("Failed to load indicator configs from Redis", e);
                }
                
                // 每30秒更新一次配置
                // 这样可以动态添加/删除策略，无需重启作业
                Thread.sleep(30000);
            }
        }
        
        /**
         * 取消方法
         * 
         * 在作业停止时调用，用于清理资源
         */
        @Override
        public void cancel() {
            isRunning = false;
            if (redisManager != null) {
                redisManager.close();
            }
        }
    }
    
    /**
     * 技术指标计算器
     * 
     * 核心功能：
     * 1. K线聚合：将同一分钟的多条ticker数据聚合为一根K线
     * 2. 策略计算：使用StrategyRegisterCenter中的130+种策略
     * 3. 信号生成：根据策略规则生成BUY/SELL/HOLD信号
     * 4. 动态配置：通过广播状态实时更新策略配置
     * 
     * 使用TA4J库进行技术分析：
     * - BarSeries: K线序列（最多保留500根）
     * - Strategy: 交易策略（包含买入/卖出规则）
     * - Indicator: 技术指标（如MA、RSI、MACD等）
     * 
     * 状态管理：
     * - barSeriesState: 存储每个交易对的K线序列（MapState）
     * - currentKLineState: 存储当前正在聚合的K线（ValueState）
     * - configState: 存储策略配置（BroadcastState）
     * 
     * 优化点：
     * 1. K线聚合：同一分钟的多条ticker数据聚合为一根K线，减少计算量
     * 2. 去重处理：避免重复添加K线，确保数据准确性
     * 3. 增量计算：只在K线完成时计算指标，提高效率
     * 4. 策略复用：使用StrategyRegisterCenter统一管理策略，避免重复代码
     * 
     * 处理流程：
     * 1. 接收ticker数据
     * 2. 按分钟聚合K线
     * 3. K线完成时添加到序列
     * 4. 遍历所有启用的策略配置
     * 5. 创建策略并判断交易信号
     * 6. 生成IndicatorResult并输出
     */
    public static class TechnicalIndicatorCalculator extends 
            KeyedBroadcastProcessFunction<String, TickerData, IndicatorConfig, IndicatorResult> {
        
        // 使用MapState存储每个交易对的K线序列
        // Key: symbol (交易对)
        // Value: BarSeries (K线序列，最多500根)
        private transient MapState<String, BarSeries> barSeriesState;
        
        // 使用ValueState存储当前正在聚合的K线数据
        // Value: KLineAggregator (当前分钟的K线聚合器)
        private transient ValueState<KLineAggregator> currentKLineState;
        
        /**
         * 初始化方法
         * 
         * 在Task启动时调用一次，用于初始化状态
         */
        @Override
        public void open(Configuration parameters) {
            // 初始化K线序列状态
            // MapState用于存储每个交易对的K线序列
            // Key: symbol (交易对名称)
            // Value: BarSeries (K线序列对象)
            MapStateDescriptor<String, BarSeries> barSeriesDesc = 
                new MapStateDescriptor<>(
                    "bar-series",
                    TypeInformation.of(String.class),
                    TypeInformation.of(BarSeries.class)
                );
            barSeriesState = getRuntimeContext().getMapState(barSeriesDesc);
            
            // 初始化当前K线聚合状态
            // ValueState用于存储当前正在聚合的K线
            // 每个交易对有一个独立的聚合器
            ValueStateDescriptor<KLineAggregator> currentKLineDesc = 
                new ValueStateDescriptor<>(
                    "current-kline",
                    TypeInformation.of(KLineAggregator.class)
                );
            currentKLineState = getRuntimeContext().getState(currentKLineDesc);
        }
        
        /**
         * 处理ticker数据元素
         * 
         * 核心处理逻辑：
         * 1. 获取或创建K线序列
         * 2. 计算分钟时间戳（对齐到分钟）
         * 3. 判断是否需要创建新K线
         * 4. 聚合ticker数据到当前K线
         * 5. K线完成时计算所有匹配的策略
         * 6. 生成交易信号并输出
         * 
         * K线聚合规则：
         * - 同一分钟的多条ticker聚合为一根K线
         * - 开盘价：第一条数据
         * - 最高价：所有数据中的最高价
         * - 最低价：所有数据中的最低价
         * - 收盘价：最后一条数据
         * - 成交量：累加
         * 
         * 去重机制：
         * - 通过minuteTimestamp判断是否是新的K线
         * - 避免重复添加同一分钟的K线
         * 
         * @param tickerData ticker数据
         * @param ctx 只读上下文（用于访问广播状态）
         * @param out 输出收集器
         * @throws Exception 处理异常
         */
        @Override
        public void processElement(
                TickerData tickerData,
                ReadOnlyContext ctx,
                Collector<IndicatorResult> out) throws Exception {
            
            String symbol = tickerData.symbol;
            
            // ========================================================================
            // 步骤 1: 获取或创建K线序列
            // ========================================================================
            // 从状态中获取该交易对的K线序列
            // 如果不存在，创建新的序列并设置最大K线数量为500
            BarSeries series = barSeriesState.get(symbol);
            if (series == null) {
                series = new BaseBarSeriesBuilder()
                    .withName(symbol)
                    .build();
                series.setMaximumBarCount(500);  // 保留最近500根K线，自动清理旧数据
            }
            
            // ========================================================================
            // 步骤 2: 计算分钟时间戳（对齐到分钟）
            // ========================================================================
            // 将毫秒时间戳对齐到分钟级别
            // 例如：1711353612345 -> 1711353600000 (2026-03-25 14:00:00)
            long minuteTimestamp = (tickerData.timestamp / 60000) * 60000;
            ZonedDateTime minuteTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(minuteTimestamp),
                ZoneId.of("Asia/Shanghai")  // 使用东八区时间
            );
            
            // ========================================================================
            // 步骤 3: 获取当前正在聚合的K线
            // ========================================================================
            KLineAggregator aggregator = currentKLineState.value();
            
            // ========================================================================
            // 步骤 4: 判断是否需要创建新的K线
            // ========================================================================
            // 如果aggregator为null（第一次）或者分钟时间戳不同（新的一分钟）
            // 则需要创建新的K线聚合器
            boolean needNewBar = false;
            if (aggregator == null || aggregator.minuteTimestamp != minuteTimestamp) {
                // 如果有旧的K线，先将其完成并添加到序列中
                if (aggregator != null) {
                    Bar completedBar = aggregator.toBar(Duration.ofMinutes(1));
                    series.addBar(completedBar);
                    
                    LOG.debug("Completed K-line for {}: time={}, open={}, high={}, low={}, close={}, volume={}", 
                        symbol, aggregator.minuteTime, 
                        aggregator.open, aggregator.high, aggregator.low, aggregator.close, aggregator.volume);
                }
                
                // 创建新的K线聚合器
                aggregator = new KLineAggregator(minuteTimestamp, minuteTime);
                needNewBar = true;
            }
            
            // ========================================================================
            // 步骤 5: 更新K线数据（聚合同一分钟的多条ticker）
            // ========================================================================
            aggregator.update(tickerData);
            currentKLineState.update(aggregator);
            
            // ========================================================================
            // 步骤 6: 如果是新K线且序列中已有数据，则计算指标
            // ========================================================================
            // 只在K线完成时计算指标，避免频繁计算
            // 需要至少有1根K线才能计算指标
            if (needNewBar && series.getBarCount() > 0) {
                // 保存更新后的序列到状态
                barSeriesState.put(symbol, series);
                
                // 获取广播状态中的指标配置
                ReadOnlyBroadcastState<String, IndicatorConfig> configState = 
                    ctx.getBroadcastState(INDICATOR_CONFIG_DESC);
                
                // 遍历所有启用的指标配置
                for (Map.Entry<String, IndicatorConfig> entry : configState.immutableEntries()) {
                    IndicatorConfig config = entry.getValue();
                    
                    // 检查是否启用
                    if (!config.enabled) {
                        continue;
                    }
                    
                    // 检查是否匹配当前交易对
                    // "*" 表示匹配所有交易对
                    if (!"*".equals(config.symbol) && !symbol.equals(config.symbol)) {
                        continue;
                    }
                    
                    try {
                        // 计算指标并生成交易信号
                        IndicatorResult result = calculateIndicator(
                            series,
                            config,
                            tickerData.exchange,
                            minuteTimestamp
                        );
                        
                        if (result != null) {
                            out.collect(result);
                        }
                    } catch (Exception e) {
                        LOG.error("Failed to calculate indicator {} for {}", 
                            config.indicatorName, symbol, e);
                    }
                }
            }
        }
        
        /**
         * 处理广播元素（策略配置更新）
         * 
         * 功能：更新广播状态中的策略配置
         * 
         * 处理逻辑：
         * - 如果enabled=true：添加或更新配置
         * - 如果enabled=false：删除配置
         * 
         * 广播机制：
         * - 配置更新会自动分发到所有Task
         * - 所有Task共享同一份配置
         * - 无需重启作业即可生效
         * 
         * @param config 策略配置
         * @param ctx 上下文（用于访问广播状态）
         * @param out 输出收集器（本方法不输出数据）
         * @throws Exception 处理异常
         */
        @Override
        public void processBroadcastElement(
                IndicatorConfig config,
                Context ctx,
                Collector<IndicatorResult> out) throws Exception {
            
            // 获取广播状态
            BroadcastState<String, IndicatorConfig> configState = 
                ctx.getBroadcastState(INDICATOR_CONFIG_DESC);
            
            if (config.enabled) {
                // 添加或更新配置
                configState.put(config.configId, config);
                LOG.info("Added/Updated indicator config: {} for symbol: {}", 
                    config.indicatorName, config.symbol);
            } else {
                // 删除配置
                configState.remove(config.configId);
                LOG.info("Removed indicator config: {}", config.configId);
            }
        }
        
        /**
         * 使用StrategyRegisterCenter中配置的策略计算交易信号
         * 
         * 核心功能：
         * 1. 从StrategyRegisterCenter创建策略
         * 2. 使用策略的shouldEnter()和shouldExit()方法判断信号
         * 3. 生成BUY/SELL/HOLD信号
         * 4. 填充IndicatorResult对象
         * 
         * 策略类型：
         * - 支持130+种预定义策略（在StrategyRegisterCenter中）
         * - 如STRATEGY_RSI, STRATEGY_MACD, STRATEGY_BOLLINGER_BANDS等
         * 
         * 信号判断逻辑：
         * - shouldEnter() = true  → BUY（买入信号）
         * - shouldExit() = true   → SELL（卖出信号）
         * - 都不满足             → HOLD（持有）
         * 
         * 数据要求：
         * - 至少需要2根K线才能计算指标
         * - 不同策略可能需要更多K线（如MA需要period根）
         * 
         * @param series K线序列（包含历史K线数据）
         * @param config 指标配置（包含策略类型和参数）
         * @param exchange 交易所名称
         * @param timestamp 时间戳（毫秒）
         * @return 指标结果（包含交易信号），如果计算失败返回null
         */
        private IndicatorResult calculateIndicator(
                BarSeries series,
                IndicatorConfig config,
                String exchange,
                Long timestamp) {
            
            // ====================================================================
            // 步骤 1: 检查K线数据是否充足
            // ====================================================================
            // 至少需要2根K线才能计算指标
            if (series.getBarCount() < 2) {
                LOG.debug("Insufficient bar data for {}, count: {}", series.getName(), series.getBarCount());
                return null;
            }
            
            // ====================================================================
            // 步骤 2: 创建结果对象并填充基本信息
            // ====================================================================
            IndicatorResult result = new IndicatorResult();
            result.symbol = series.getName();
            result.exchange = exchange;
            result.indicatorType = config.indicatorType;
            result.indicatorName = config.indicatorName;
            result.timestamp = timestamp;
            result.createTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(timestamp));
            result.extraValues = new HashMap<>();
            
            // 获取当前价格（最新K线的收盘价）
            result.currentPrice = new BigDecimal(
                series.getBar(series.getEndIndex()).getClosePrice().toString()
            );
            
            try {
                // ================================================================
                // 步骤 3: 获取策略类型
                // ================================================================
                // 优先使用strategyType，如果没有则使用indicatorType
                String strategyType = config.strategyType;
                if (strategyType == null || strategyType.isEmpty()) {
                    strategyType = config.indicatorType;
                }
                
                // ================================================================
                // 步骤 4: 使用StrategyRegisterCenter创建策略
                // ================================================================
                // StrategyRegisterCenter包含130+种预定义策略
                // 每个策略包含买入规则（entryRule）和卖出规则（exitRule）
                Strategy strategy = StrategyRegisterCenter.createStrategy(series, strategyType);
                
                if (strategy == null) {
                    LOG.warn("Failed to create strategy for type: {}", strategyType);
                    return null;
                }
                
                // ================================================================
                // 步骤 5: 使用策略判断是否满足触发信号
                // ================================================================
                int endIndex = series.getEndIndex();  // 最新K线的索引
                boolean shouldEnter = strategy.shouldEnter(endIndex);  // 判断是否满足买入条件
                boolean shouldExit = strategy.shouldExit(endIndex);    // 判断是否满足卖出条件
                
                // ================================================================
                // 步骤 6: 生成交易信号
                // ================================================================
                if (shouldEnter) {
                    // 买入信号
                    result.signalType = "BUY";
                    result.signalReason = strategy.getName() + " entry signal";
                    result.signalStrength = new BigDecimal(80);  // 默认信号强度80
                    
                    LOG.info("BUY signal generated for {} using strategy {}", 
                        series.getName(), strategy.getName());
                    
                } else if (shouldExit) {
                    // 卖出信号
                    result.signalType = "SELL";
                    result.signalReason = strategy.getName() + " exit signal";
                    result.signalStrength = new BigDecimal(80);
                    
                    LOG.info("SELL signal generated for {} using strategy {}", 
                        series.getName(), strategy.getName());
                    
                } else {
                    // 持有信号（无操作）
                    result.signalType = "HOLD";
                    result.signalReason = "No signal from " + strategy.getName();
                    result.signalStrength = BigDecimal.ZERO;
                }
                
                // 设置指标值（使用当前价格作为指标值）
                result.indicatorValue = result.currentPrice;
                
            } catch (IllegalArgumentException e) {
                // 不支持的策略类型
                LOG.error("Unsupported strategy type: {} for symbol: {}", 
                    config.strategyType, series.getName(), e);
                return null;
                
            } catch (Exception e) {
                // 其他计算错误
                LOG.error("Error calculating strategy signal for {} with strategy {}", 
                    series.getName(), config.strategyType, e);
                return null;
            }
            
            return result;
        }
    }
    
    /**
     * K线聚合器
     * 
     * 功能：聚合同一分钟内的多条ticker数据为一根K线
     * 
     * 使用场景：
     * - 实时行情数据通常是逐笔推送的（每秒多条）
     * - 技术指标计算需要K线数据（OHLCV格式）
     * - 通过聚合可以减少计算量，提高效率
     * 
     * 聚合规则：
     * - 开盘价（Open）：第一条数据的价格
     * - 最高价（High）：所有数据中的最高价
     * - 最低价（Low）：所有数据中的最低价
     * - 收盘价（Close）：最后一条数据的价格
     * - 成交量（Volume）：所有数据的成交量累加
     * 
     * 时间对齐：
     * - minuteTimestamp：分钟级时间戳（对齐到分钟）
     * - 例如：14:00:00 ~ 14:00:59 的所有数据聚合为一根K线
     * 
     * 状态管理：
     * - 使用ValueState存储当前正在聚合的K线
     * - 每个交易对有独立的聚合器
     * - 分钟切换时自动创建新的聚合器
     * 
     * 示例：
     * 输入（同一分钟的3条ticker）：
     *   14:00:10 - price: 50000, volume: 10
     *   14:00:30 - price: 50100, volume: 15
     *   14:00:50 - price: 49900, volume: 20
     * 
     * 输出（一根K线）：
     *   time: 14:00:00
     *   open: 50000 (第一条)
     *   high: 50100 (最高)
     *   low: 49900 (最低)
     *   close: 49900 (最后一条)
     *   volume: 45 (10+15+20)
     */
    public static class KLineAggregator implements java.io.Serializable {
        public long minuteTimestamp;        // 分钟时间戳（对齐到分钟，如 1711353600000）
        public ZonedDateTime minuteTime;    // 分钟时间（ZonedDateTime格式）
        public BigDecimal open;             // 开盘价（第一条数据）
        public BigDecimal high;             // 最高价（所有数据中的最高）
        public BigDecimal low;              // 最低价（所有数据中的最低）
        public BigDecimal close;            // 收盘价（最后一条数据）
        public BigDecimal volume;           // 成交量（累加）
        public int tickCount;               // ticker数量（用于统计）
        
        public KLineAggregator() {}
        
        /**
         * 构造函数
         * 
         * @param minuteTimestamp 分钟时间戳（对齐到分钟）
         * @param minuteTime 分钟时间（ZonedDateTime格式）
         */
        public KLineAggregator(long minuteTimestamp, ZonedDateTime minuteTime) {
            this.minuteTimestamp = minuteTimestamp;
            this.minuteTime = minuteTime;
            this.tickCount = 0;
        }
        
        /**
         * 更新K线数据
         * 
         * 功能：聚合新的ticker数据到当前K线
         * 
         * 聚合规则：
         * - 第一条数据：初始化所有值（open, high, low, close, volume）
         * - 后续数据：
         *   - 更新最高价（如果当前价格更高）
         *   - 更新最低价（如果当前价格更低）
         *   - 更新收盘价（使用最新价格）
         *   - 累加成交量
         * 
         * 这样可以确保：
         * - 开盘价 = 第一条数据的价格
         * - 最高价 = 所有数据中的最高价
         * - 最低价 = 所有数据中的最低价
         * - 收盘价 = 最后一条数据的价格
         * - 成交量 = 所有数据的成交量之和
         * 
         * @param ticker 新的ticker数据
         */
        public void update(TickerData ticker) {
            if (tickCount == 0) {
                // 第一条数据：初始化所有值
                this.open = ticker.price;
                this.high = ticker.high;
                this.low = ticker.low;
                this.close = ticker.price;
                this.volume = ticker.volume;
            } else {
                // 后续数据：更新最高价、最低价、收盘价、成交量
                if (ticker.high.compareTo(this.high) > 0) {
                    this.high = ticker.high;
                }
                if (ticker.low.compareTo(this.low) < 0) {
                    this.low = ticker.low;
                }
                this.close = ticker.price;
                this.volume = this.volume.add(ticker.volume);
            }
            this.tickCount++;
        }
        
        /**
         * 转换为TA4J的Bar对象
         * 
         * 功能：将聚合的K线数据转换为TA4J库使用的Bar对象
         * 
         * Bar对象包含：
         * - duration: K线周期（1分钟）
         * - beginTime: K线开始时间（Instant）
         * - endTime: K线结束时间（Instant）
         * - openPrice: 开盘价
         * - highPrice: 最高价
         * - lowPrice: 最低价
         * - closePrice: 收盘价
         * - volume: 成交量
         * - amount: 成交额（暂时设为0）
         * - trades: 成交笔数（暂时设为0）
         * 
         * @param duration K线周期
         * @return TA4J的Bar对象
         */
        public Bar toBar(Duration duration) {
            // ta4j 0.21 变更：BaseBar 构造函数需要 beginTime 和 endTime 两个 Instant 参数
            Instant endTime = minuteTime.toInstant();
            Instant beginTime = endTime.minus(duration);  // 开始时间 = 结束时间 - 周期
            
            return new BaseBar(
                duration,
                beginTime,  // 开始时间（ta4j 0.21 新增）
                endTime,    // 结束时间
                DecimalNum.valueOf(open.doubleValue()),
                DecimalNum.valueOf(high.doubleValue()),
                DecimalNum.valueOf(low.doubleValue()),
                DecimalNum.valueOf(close.doubleValue()),
                DecimalNum.valueOf(volume.doubleValue()),
                DecimalNum.valueOf(0),  // amount（成交额，暂时设为0）
                0L  // trades（成交笔数，暂时设为0）
            );
        }
    }
    
    /**
     * 自定义指标接口
     * 
     * 功能：允许用户实现自定义技术指标
     * 
     * 使用场景：
     * - 当TA4J库中没有所需的指标时
     * - 需要实现特殊的交易策略时
     * - 需要组合多个指标时
     * 
     * 实现步骤：
     * 1. 创建类实现CustomIndicator接口
     * 2. 实现calculate方法
     * 3. 在Redis配置中指定customClass
     * 4. 系统会通过反射加载自定义指标
     * 
     * 配置示例：
     * {
     *   "config_id": "custom_001",
     *   "symbol": "BTC-USDT",
     *   "indicator_type": "CUSTOM",
     *   "indicator_name": "My Custom Indicator",
     *   "custom_class": "com.crypto.dw.indicators.MyCustomIndicator",
     *   "params": {"param1": "value1"},
     *   "enabled": true
     * }
     * 
     * 实现示例：
     * public class MyCustomIndicator implements CustomIndicator {
     *     @Override
     *     public void calculate(BarSeries series, Map<String, String> params, IndicatorResult result) {
     *         // 自定义计算逻辑
     *         int period = Integer.parseInt(params.getOrDefault("period", "14"));
     *         // ... 计算指标值
     *         result.indicatorValue = new BigDecimal(calculatedValue);
     *         result.signalType = "BUY"; // 或 "SELL" 或 "HOLD"
     *     }
     * }
     */
    public interface CustomIndicator {
        /**
         * 计算自定义指标
         * 
         * @param series K线序列（包含历史K线数据）
         * @param params 指标参数（从Redis配置中读取）
         * @param result 计算结果（需要填充indicatorValue、signalType等字段）
         */
        void calculate(BarSeries series, Map<String, String> params, IndicatorResult result);
    }
}
