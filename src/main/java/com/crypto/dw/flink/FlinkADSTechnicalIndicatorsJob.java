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
 * 
 * 业务场景：
 * 基于TA4J技术分析库，实时计算各种技术指标，支持动态配置和自定义指标
 * 
 * 核心功能：
 * 1. 内置指标支持：MA、EMA、RSI、MACD、布林带、KDJ、ATR等
 * 2. 自定义指标：支持用户自定义指标计算逻辑
 * 3. 动态配置：通过Redis动态添加/删除指标，无需重启作业
 * 4. 多周期支持：支持1分钟、5分钟、15分钟等多个时间周期
 * 5. 状态管理：使用Flink状态维护K线数据和指标计算结果
 * 
 * 技术特点：
 * - 使用TA4J库进行技术指标计算
 * - 广播状态实现动态指标配置
 * - 键控状态维护每个交易对的K线序列
 * - 支持指标组合和策略回测
 * 
 * 数据流向：
 * Kafka(ticker) -> 解析 -> K线聚合 -> 指标计算 -> Doris
 *                                  ↑
 *                            Redis(指标配置)
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
        // 1. 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 2. 创建执行环境
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment(
            "flink-ads-technical-indicators-job",
            8085
        );
        
        // 3. 创建Kafka Source - 订阅ticker数据
        KafkaSourceFactory kafkaFactory = new KafkaSourceFactory(config);
        KafkaSource<String> tickerSource = kafkaFactory.createKafkaSource(
            "crypto_ticker_spot",
            "flink-ads-technical-indicators-consumer"
        );
        
        // 4. 读取ticker数据流
        DataStream<String> tickerStream = env
            .fromSource(
                tickerSource,
                WatermarkStrategy
                    .<String>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                    .withTimestampAssigner((event, timestamp) -> {
                        try {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode node = mapper.readTree(event);
                            return node.get("ts").asLong();
                        } catch (Exception e) {
                            return System.currentTimeMillis();
                        }
                    }),
                "Kafka Ticker Source"
            );
        
        // 5. 解析并转换为TickerData对象
        SingleOutputStreamOperator<TickerData> tickerDataStream = tickerStream
            .map(new TickerDataParser())
            .name("Parse Ticker Data");
        
        // 6. 创建广播流 - 从Redis读取指标配置
        DataStream<IndicatorConfig> indicatorConfigStream = env
            .addSource(new IndicatorConfigSource(config))
            .name("Indicator Config Source");
        
        BroadcastStream<IndicatorConfig> configBroadcast = indicatorConfigStream
            .broadcast(INDICATOR_CONFIG_DESC);
        
        // 7. 关联ticker数据和指标配置，计算技术指标
        SingleOutputStreamOperator<IndicatorResult> indicatorResults = tickerDataStream
            .keyBy(data -> data.symbol)
            .connect(configBroadcast)
            .process(new TechnicalIndicatorCalculator())
            .name("Calculate Technical Indicators");
        
        // 8. 写入Doris
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        indicatorResults
            .map(result -> result.toJson())
            .sinkTo(dorisSinkFactory.createDorisSink(
                "crypto_ads",
                "ads_technical_indicators",
                "ads_technical_indicator"
            ))
            .name("Sink to Doris - Technical Indicators");
        
        // 9. 执行作业
        env.execute("Flink ADS Technical Indicators Job");
    }
    
    /**
     * Ticker数据实体
     */
    public static class TickerData {
        public String symbol;
        public String exchange;
        public BigDecimal price;
        public BigDecimal volume;
        public BigDecimal high;
        public BigDecimal low;
        public Long timestamp;
        
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
     */
    public static class IndicatorConfig implements java.io.Serializable {
        public String configId;           // 配置ID
        public String symbol;             // 交易对（*表示所有）
        public String indicatorType;      // 指标类型：MA, EMA, RSI, MACD, BOLL, KDJ, ATR, CUSTOM, STRATEGY
        public String indicatorName;      // 指标名称
        public Map<String, String> params; // 指标参数
        public String customClass;        // 自定义指标类名（可选）
        public String strategyType;       // 策略类型（用于STRATEGY类型，对应StrategyRegisterCenter中的策略）
        public Boolean enabled;           // 是否启用
        public Long updateTime;           // 更新时间
        
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
     */
    public static class IndicatorResult {
        public String symbol;
        public String exchange;
        public String indicatorType;
        public String indicatorName;
        public BigDecimal indicatorValue;
        public Map<String, BigDecimal> extraValues;  // 额外的值（如MACD的DIF、DEA、MACD）
        
        // 交易信号相关字段
        public String signalType;           // 交易信号：BUY, SELL, HOLD
        public String signalReason;         // 信号原因（基于哪个指标规则）
        public BigDecimal signalStrength;   // 信号强度 0-100
        public BigDecimal currentPrice;     // 当前价格
        
        public Long timestamp;
        public String createTime;
        
        public IndicatorResult() {}
        
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
     */
    public static class TickerDataParser extends RichMapFunction<String, TickerData> {
        private transient ObjectMapper objectMapper;
        
        @Override
        public void open(Configuration parameters) {
            objectMapper = new ObjectMapper();
        }
        
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
     * 从Redis定期读取指标配置，支持动态更新
     */
    public static class IndicatorConfigSource implements SourceFunction<IndicatorConfig> {
        
        private volatile boolean isRunning = true;
        private transient RedisConnectionManager redisManager;
        private final ConfigLoader config;
        
        public IndicatorConfigSource(ConfigLoader config) {
            this.config = config;
        }
        
        @Override
        public void run(SourceContext<IndicatorConfig> ctx) throws Exception {
            // 初始化Redis连接
            redisManager = new RedisConnectionManager(config);
            
            while (isRunning) {
                try (Jedis jedis = redisManager.getConnection()) {
                    // 从Redis读取所有指标配置
                    Set<String> keys = jedis.keys("indicator:config:*");
                    
                    for (String key : keys) {
                        String configJson = jedis.get(key);
                        if (configJson != null) {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode node = mapper.readTree(configJson);
                            
                            // 解析参数
                            Map<String, String> params = new HashMap<>();
                            if (node.has("params")) {
                                JsonNode paramsNode = node.get("params");
                                paramsNode.fields().forEachRemaining(entry -> 
                                    params.put(entry.getKey(), entry.getValue().asText())
                                );
                            }
                            
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
                            
                            ctx.collect(config);
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Failed to load indicator configs from Redis", e);
                }
                
                // 每30秒更新一次配置
                Thread.sleep(30000);
            }
        }
        
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
     * 使用TA4J库计算各种技术指标
     * 
     * 优化点：
     * 1. K线聚合：同一分钟的多条ticker数据聚合为一根K线
     * 2. 去重处理：避免重复添加K线
     * 3. 增量计算：只在K线更新时计算指标
     */
    public static class TechnicalIndicatorCalculator extends 
            KeyedBroadcastProcessFunction<String, TickerData, IndicatorConfig, IndicatorResult> {
        
        // 使用MapState存储每个交易对的K线序列
        private transient MapState<String, BarSeries> barSeriesState;
        
        // 使用ValueState存储当前正在聚合的K线数据
        private transient ValueState<KLineAggregator> currentKLineState;
        
        @Override
        public void open(Configuration parameters) {
            // 初始化K线序列状态
            MapStateDescriptor<String, BarSeries> barSeriesDesc = 
                new MapStateDescriptor<>(
                    "bar-series",
                    TypeInformation.of(String.class),
                    TypeInformation.of(BarSeries.class)
                );
            barSeriesState = getRuntimeContext().getMapState(barSeriesDesc);
            
            // 初始化当前K线聚合状态
            ValueStateDescriptor<KLineAggregator> currentKLineDesc = 
                new ValueStateDescriptor<>(
                    "current-kline",
                    TypeInformation.of(KLineAggregator.class)
                );
            currentKLineState = getRuntimeContext().getState(currentKLineDesc);
        }
        
        @Override
        public void processElement(
                TickerData tickerData,
                ReadOnlyContext ctx,
                Collector<IndicatorResult> out) throws Exception {
            
            String symbol = tickerData.symbol;
            
            // 获取或创建K线序列
            BarSeries series = barSeriesState.get(symbol);
            if (series == null) {
                series = new BaseBarSeriesBuilder()
                    .withName(symbol)
                    .build();
                series.setMaximumBarCount(500);  // 保留最近500根K线
            }
            
            // 计算当前数据所属的分钟时间戳（对齐到分钟）
            long minuteTimestamp = (tickerData.timestamp / 60000) * 60000;
            ZonedDateTime minuteTime = ZonedDateTime.ofInstant(
                Instant.ofEpochMilli(minuteTimestamp),
                ZoneId.of("Asia/Shanghai")
            );
            
            // 获取当前正在聚合的K线
            KLineAggregator aggregator = currentKLineState.value();
            
            // 判断是否需要创建新的K线
            boolean needNewBar = false;
            if (aggregator == null || aggregator.minuteTimestamp != minuteTimestamp) {
                // 如果有旧的K线，先添加到序列中
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
            
            // 更新K线数据（聚合同一分钟的多条ticker）
            aggregator.update(tickerData);
            currentKLineState.update(aggregator);
            
            // 如果是新K线且序列中已有数据，则计算指标
            if (needNewBar && series.getBarCount() > 0) {
                // 保存更新后的序列
                barSeriesState.put(symbol, series);
                
                // 获取广播状态中的指标配置
                ReadOnlyBroadcastState<String, IndicatorConfig> configState = 
                    ctx.getBroadcastState(INDICATOR_CONFIG_DESC);
                
                // 遍历所有启用的指标配置
                for (Map.Entry<String, IndicatorConfig> entry : configState.immutableEntries()) {
                    IndicatorConfig config = entry.getValue();
                    
                    // 检查是否启用且匹配当前交易对
                    if (!config.enabled) {
                        continue;
                    }
                    if (!"*".equals(config.symbol) && !symbol.equals(config.symbol)) {
                        continue;
                    }
                    
                    try {
                        // 计算指标
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
        
        @Override
        public void processBroadcastElement(
                IndicatorConfig config,
                Context ctx,
                Collector<IndicatorResult> out) throws Exception {
            
            // 更新广播状态
            BroadcastState<String, IndicatorConfig> configState = 
                ctx.getBroadcastState(INDICATOR_CONFIG_DESC);
            
            if (config.enabled) {
                configState.put(config.configId, config);
                LOG.info("Added/Updated indicator config: {} for symbol: {}", 
                    config.indicatorName, config.symbol);
            } else {
                configState.remove(config.configId);
                LOG.info("Removed indicator config: {}", config.configId);
            }
        }
        
        /**
         * 使用StrategyRegisterCenter中配置的策略计算交易信号
         * 
         * @param series K线序列
         * @param config 指标配置（包含策略类型）
         * @param exchange 交易所
         * @param timestamp 时间戳
         * @return 指标结果（包含交易信号）
         */
        private IndicatorResult calculateIndicator(
                BarSeries series,
                IndicatorConfig config,
                String exchange,
                Long timestamp) {
            
            // 检查K线数据是否充足
            if (series.getBarCount() < 2) {
                LOG.debug("Insufficient bar data for {}, count: {}", series.getName(), series.getBarCount());
                return null;
            }
            
            // 创建结果对象
            IndicatorResult result = new IndicatorResult();
            result.symbol = series.getName();
            result.exchange = exchange;
            result.indicatorType = config.indicatorType;
            result.indicatorName = config.indicatorName;
            result.timestamp = timestamp;
            result.createTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(timestamp));
            result.extraValues = new HashMap<>();
            
            // 获取当前价格
            result.currentPrice = new BigDecimal(
                series.getBar(series.getEndIndex()).getClosePrice().toString()
            );
            
            try {
                // 从配置中获取策略类型
                String strategyType = config.strategyType;
                if (strategyType == null || strategyType.isEmpty()) {
                    // 如果没有配置strategyType，使用indicatorType作为策略类型
                    strategyType = config.indicatorType;
                }
                
                // 使用StrategyRegisterCenter创建策略
                Strategy strategy = StrategyRegisterCenter.createStrategy(series, strategyType);
                
                if (strategy == null) {
                    LOG.warn("Failed to create strategy for type: {}", strategyType);
                    return null;
                }
                
                // 使用策略判断是否满足触发信号
                int endIndex = series.getEndIndex();
                boolean shouldEnter = strategy.shouldEnter(endIndex);
                boolean shouldExit = strategy.shouldExit(endIndex);
                
                // 生成交易信号
                if (shouldEnter) {
                    result.signalType = "BUY";
                    result.signalReason = strategy.getName() + " entry signal";
                    result.signalStrength = new BigDecimal(80);  // 默认信号强度
                    
                    LOG.info("BUY signal generated for {} using strategy {}", 
                        series.getName(), strategy.getName());
                    
                } else if (shouldExit) {
                    result.signalType = "SELL";
                    result.signalReason = strategy.getName() + " exit signal";
                    result.signalStrength = new BigDecimal(80);
                    
                    LOG.info("SELL signal generated for {} using strategy {}", 
                        series.getName(), strategy.getName());
                    
                } else {
                    result.signalType = "HOLD";
                    result.signalReason = "No signal from " + strategy.getName();
                    result.signalStrength = BigDecimal.ZERO;
                }
                
                // 设置指标值（使用当前价格作为指标值）
                result.indicatorValue = result.currentPrice;
                
            } catch (IllegalArgumentException e) {
                LOG.error("Unsupported strategy type: {} for symbol: {}", 
                    config.strategyType, series.getName(), e);
                return null;
                
            } catch (Exception e) {
                LOG.error("Error calculating strategy signal for {} with strategy {}", 
                    series.getName(), config.strategyType, e);
                return null;
            }
            
            return result;
        }
    }
    
    /**
     * K线聚合器
     * 用于聚合同一分钟内的多条ticker数据为一根K线
     * 
     * 聚合规则：
     * - 开盘价：第一条数据的价格
     * - 最高价：所有数据中的最高价
     * - 最低价：所有数据中的最低价
     * - 收盘价：最后一条数据的价格
     * - 成交量：所有数据的成交量累加
     */
    public static class KLineAggregator implements java.io.Serializable {
        public long minuteTimestamp;        // 分钟时间戳（对齐到分钟）
        public ZonedDateTime minuteTime;    // 分钟时间
        public BigDecimal open;             // 开盘价
        public BigDecimal high;             // 最高价
        public BigDecimal low;              // 最低价
        public BigDecimal close;            // 收盘价
        public BigDecimal volume;           // 成交量
        public int tickCount;               // ticker数量
        
        public KLineAggregator() {}
        
        public KLineAggregator(long minuteTimestamp, ZonedDateTime minuteTime) {
            this.minuteTimestamp = minuteTimestamp;
            this.minuteTime = minuteTime;
            this.tickCount = 0;
        }
        
        /**
         * 更新K线数据
         * 聚合新的ticker数据
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
         */
        public Bar toBar(Duration duration) {
            return new BaseBar(
                duration,
                minuteTime.toInstant(),  // 转换为Instant
                DecimalNum.valueOf(open.doubleValue()),
                DecimalNum.valueOf(high.doubleValue()),
                DecimalNum.valueOf(low.doubleValue()),
                DecimalNum.valueOf(close.doubleValue()),
                DecimalNum.valueOf(volume.doubleValue()),
                DecimalNum.valueOf(0),  // amount
                0L  // trades
            );
        }
    }
    
    /**
     * 自定义指标接口
     * 用户可以实现此接口来创建自定义指标
     */
    public interface CustomIndicator {
        /**
         * 计算自定义指标
         * 
         * @param series K线序列
         * @param params 指标参数
         * @param result 计算结果（需要填充indicatorValue和extraValues）
         */
        void calculate(BarSeries series, Map<String, String> params, IndicatorResult result);
    }
}
