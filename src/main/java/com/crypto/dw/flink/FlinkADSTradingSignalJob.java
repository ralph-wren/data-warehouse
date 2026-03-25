package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.constants.FlinkConstants;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.KafkaSourceFactory;
import com.crypto.dw.redis.RedisConnectionManager;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.*;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.api.java.tuple.Tuple3;
import org.apache.flink.cep.CEP;
import org.apache.flink.cep.PatternSelectFunction;
import org.apache.flink.cep.PatternStream;
import org.apache.flink.cep.pattern.Pattern;
import org.apache.flink.cep.pattern.conditions.SimpleCondition;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.KeyedBroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.SlidingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.*;

/**
 * ADS层 - 实时交易信号生成系统
 * 
 * 业务场景：
 * 基于多维度市场数据，实时生成交易信号，帮助交易员捕捉市场机会
 * 
 * 使用的Flink高级特性：
 * 1. CEP (Complex Event Processing) - 复杂事件处理，检测价格模式
 * 2. Broadcast State - 广播流，动态更新交易策略参数
 * 3. Keyed State - 键控状态，维护每个交易对的历史统计
 * 4. Side Output - 侧输出流，分离不同类型的信号
 * 5. Sliding Window - 滑动窗口，计算多时间维度指标
 * 6. Watermark - 水位线，处理乱序数据
 * 7. Rich Function - 富函数，访问Redis等外部系统
 * 
 * 数据流向：
 * Kafka(ticker) -> 解析 -> CEP模式检测 -> 广播流关联 -> 窗口聚合 -> 信号生成 -> Doris
 *                                                    |
 *                                                    +-> 侧输出(高风险信号)
 * 
 * @author Crypto DW Team
 * @date 2026-03-25
 */
public class FlinkADSTradingSignalJob {
    
    private static final Logger LOG = LoggerFactory.getLogger(FlinkADSTradingSignalJob.class);
    
    // 侧输出标签 - 用于分离高风险信号
    private static final OutputTag<String> HIGH_RISK_SIGNAL_TAG = 
        new OutputTag<String>("high-risk-signal"){};
    
    // 侧输出标签 - 用于分离异常数据（预留，暂未使用）
    // private static final OutputTag<String> ANOMALY_TAG = 
    //     new OutputTag<String>("anomaly-data"){};
    
    // 广播状态描述符 - 用于动态更新策略参数
    private static final MapStateDescriptor<String, TradingStrategy> STRATEGY_STATE_DESC =
        new MapStateDescriptor<>(
            "trading-strategy",
            TypeInformation.of(String.class),
            TypeInformation.of(TradingStrategy.class)
        );
    
    public static void main(String[] args) throws Exception {
        // 1. 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 2. 创建执行环境
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment(
            "flink-ads-trading-signal-job",
            8084
        );
        
        // 3. 创建Kafka Source - 订阅ticker数据
        KafkaSourceFactory kafkaFactory = new KafkaSourceFactory(config);
        KafkaSource<String> tickerSource = kafkaFactory.createKafkaSource(
            "crypto_ticker_spot",
            "flink-ads-trading-signal-consumer"
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
        
        // 5. 解析并转换为MarketData对象
        SingleOutputStreamOperator<MarketData> marketDataStream = tickerStream
            .map(new MarketDataParser())
            .name("Parse Market Data");
        
        // 6. 使用CEP检测价格突破模式
        Pattern<MarketData, ?> breakoutPattern = Pattern
            .<MarketData>begin("start")
            .where(new SimpleCondition<MarketData>() {
                @Override
                public boolean filter(MarketData data) {
                    // 价格在相对稳定区间
                    return data.priceChangePercent.abs().compareTo(new BigDecimal("0.5")) < 0;
                }
            })
            .times(3).consecutive()  // 连续3次稳定
            .followedBy("breakout")
            .where(new SimpleCondition<MarketData>() {
                @Override
                public boolean filter(MarketData data) {
                    // 突然大幅波动
                    return data.priceChangePercent.abs().compareTo(new BigDecimal("1.5")) > 0;
                }
            })
            .within(Time.minutes(5));  // 5分钟内发生
        
        PatternStream<MarketData> patternStream = CEP.pattern(
            marketDataStream.keyBy(data -> data.symbol),
            breakoutPattern
        );
        
        // 7. 提取突破信号
        DataStream<BreakoutSignal> breakoutSignals = patternStream
            .select(new PatternSelectFunction<MarketData, BreakoutSignal>() {
                @Override
                public BreakoutSignal select(Map<String, List<MarketData>> pattern) {
                    List<MarketData> stableData = pattern.get("start");
                    MarketData breakoutData = pattern.get("breakout").get(0);
                    
                    // 计算稳定期平均价格
                    BigDecimal avgPrice = stableData.stream()
                        .map(d -> d.lastPrice)
                        .reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(new BigDecimal(stableData.size()), 8, RoundingMode.HALF_UP);
                    
                    return new BreakoutSignal(
                        breakoutData.symbol,
                        breakoutData.exchange,
                        avgPrice,
                        breakoutData.lastPrice,
                        breakoutData.priceChangePercent,
                        breakoutData.volume24h,
                        breakoutData.timestamp
                    );
                }
            })
            .name("Extract Breakout Signals");
        
        // 8. 创建广播流 - 从Redis读取交易策略配置
        DataStream<TradingStrategy> strategyStream = env
            .addSource(new TradingStrategySource(config))
            .name("Trading Strategy Source");
        
        BroadcastStream<TradingStrategy> strategyBroadcast = strategyStream
            .broadcast(STRATEGY_STATE_DESC);
        
        // 9. 关联突破信号和交易策略
        SingleOutputStreamOperator<EnrichedSignal> enrichedSignals = breakoutSignals
            .keyBy(signal -> signal.symbol)
            .connect(strategyBroadcast)
            .process(new SignalEnrichmentFunction())
            .name("Enrich Signals with Strategy");
        
        // 10. 滑动窗口聚合 - 计算多时间维度指标
        SingleOutputStreamOperator<TradingSignal> tradingSignals = enrichedSignals
            .keyBy(signal -> signal.symbol)
            .window(SlidingEventTimeWindows.of(
                Time.minutes(15),  // 窗口大小15分钟
                Time.minutes(5)    // 滑动步长5分钟
            ))
            .process(new TradingSignalGenerator())
            .name("Generate Trading Signals");
        
        // 11. 获取侧输出流 - 高风险信号
        DataStream<String> highRiskSignals = tradingSignals
            .getSideOutput(HIGH_RISK_SIGNAL_TAG);
        
        // 12. 写入Doris - 主信号流
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        tradingSignals
            .map(signal -> signal.toJson())
            .sinkTo(dorisSinkFactory.createDorisSink(
                "crypto_ads",
                "ads_trading_signals",
                "ads_trading_signal"
            ))
            .name("Sink to Doris - Trading Signals");
        
        // 13. 写入Doris - 高风险信号流
        highRiskSignals
            .sinkTo(dorisSinkFactory.createDorisSink(
                "crypto_ads",
                "ads_high_risk_signals",
                "ads_high_risk_signal"
            ))
            .name("Sink to Doris - High Risk Signals");
        
        // 14. 执行作业
        env.execute("Flink ADS Trading Signal Job");
    }
    
    /**
     * 市场数据实体
     */
    public static class MarketData {
        public String symbol;
        public String exchange;
        public BigDecimal lastPrice;
        public BigDecimal priceChangePercent;
        public BigDecimal volume24h;
        public BigDecimal high24h;
        public BigDecimal low24h;
        public Long timestamp;
        
        public MarketData() {}
        
        public MarketData(String symbol, String exchange, BigDecimal lastPrice,
                         BigDecimal priceChangePercent, BigDecimal volume24h,
                         BigDecimal high24h, BigDecimal low24h, Long timestamp) {
            this.symbol = symbol;
            this.exchange = exchange;
            this.lastPrice = lastPrice;
            this.priceChangePercent = priceChangePercent;
            this.volume24h = volume24h;
            this.high24h = high24h;
            this.low24h = low24h;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 突破信号实体
     */
    public static class BreakoutSignal {
        public String symbol;
        public String exchange;
        public BigDecimal stablePrice;      // 稳定期平均价格
        public BigDecimal breakoutPrice;    // 突破价格
        public BigDecimal priceChange;      // 价格变化百分比
        public BigDecimal volume;           // 成交量
        public Long timestamp;
        
        public BreakoutSignal() {}
        
        public BreakoutSignal(String symbol, String exchange, BigDecimal stablePrice,
                             BigDecimal breakoutPrice, BigDecimal priceChange,
                             BigDecimal volume, Long timestamp) {
            this.symbol = symbol;
            this.exchange = exchange;
            this.stablePrice = stablePrice;
            this.breakoutPrice = breakoutPrice;
            this.priceChange = priceChange;
            this.volume = volume;
            this.timestamp = timestamp;
        }
    }
    
    /**
     * 交易策略实体
     */
    public static class TradingStrategy {
        public String symbol;
        public BigDecimal riskThreshold;     // 风险阈值
        public BigDecimal volumeThreshold;   // 成交量阈值
        public BigDecimal priceThreshold;    // 价格波动阈值
        public String strategyType;          // 策略类型: AGGRESSIVE, MODERATE, CONSERVATIVE
        public Long updateTime;
        
        public TradingStrategy() {}
        
        public TradingStrategy(String symbol, BigDecimal riskThreshold,
                              BigDecimal volumeThreshold, BigDecimal priceThreshold,
                              String strategyType, Long updateTime) {
            this.symbol = symbol;
            this.riskThreshold = riskThreshold;
            this.volumeThreshold = volumeThreshold;
            this.priceThreshold = priceThreshold;
            this.strategyType = strategyType;
            this.updateTime = updateTime;
        }
    }
    
    /**
     * 富化后的信号实体
     */
    public static class EnrichedSignal {
        public String symbol;
        public String exchange;
        public BigDecimal stablePrice;
        public BigDecimal breakoutPrice;
        public BigDecimal priceChange;
        public BigDecimal volume;
        public TradingStrategy strategy;
        public Long timestamp;
        
        public EnrichedSignal() {}
    }
    
    /**
     * 最终交易信号实体
     */
    public static class TradingSignal {
        public String signalId;
        public String symbol;
        public String exchange;
        public String signalType;           // BUY, SELL, HOLD
        public BigDecimal currentPrice;
        public BigDecimal targetPrice;
        public BigDecimal stopLoss;
        public BigDecimal confidence;       // 信号置信度 0-100
        public BigDecimal riskScore;        // 风险评分 0-100
        public String strategyType;
        public BigDecimal volume24h;
        public BigDecimal volatility;       // 波动率
        public Integer signalCount;         // 窗口内信号数量
        public Long timestamp;
        public String createTime;
        
        public TradingSignal() {}
        
        public String toJson() {
            return String.format(
                "{\"signal_id\":\"%s\",\"symbol\":\"%s\",\"exchange\":\"%s\"," +
                "\"signal_type\":\"%s\",\"current_price\":%s,\"target_price\":%s," +
                "\"stop_loss\":%s,\"confidence\":%s,\"risk_score\":%s," +
                "\"strategy_type\":\"%s\",\"volume_24h\":%s,\"volatility\":%s," +
                "\"signal_count\":%d,\"timestamp\":%d,\"create_time\":\"%s\"}",
                signalId, symbol, exchange, signalType,
                currentPrice, targetPrice, stopLoss, confidence, riskScore,
                strategyType, volume24h, volatility, signalCount, timestamp, createTime
            );
        }
    }

    
    /**
     * 市场数据解析器
     * 将Kafka中的JSON字符串解析为MarketData对象
     */
    public static class MarketDataParser extends RichMapFunction<String, MarketData> {
        private transient ObjectMapper objectMapper;
        
        @Override
        public void open(Configuration parameters) {
            objectMapper = new ObjectMapper();
        }
        
        @Override
        public MarketData map(String value) throws Exception {
            try {
                JsonNode node = objectMapper.readTree(value);
                
                return new MarketData(
                    node.get("inst_id").asText(),
                    node.has("exchange") ? node.get("exchange").asText() : "OKX",
                    new BigDecimal(node.get("last").asText()),
                    new BigDecimal(node.has("price_change_percent") ? 
                        node.get("price_change_percent").asText() : "0"),
                    new BigDecimal(node.has("vol_24h") ? node.get("vol_24h").asText() : "0"),
                    new BigDecimal(node.has("high_24h") ? node.get("high_24h").asText() : "0"),
                    new BigDecimal(node.has("low_24h") ? node.get("low_24h").asText() : "0"),
                    node.get("ts").asLong()
                );
            } catch (Exception e) {
                LOG.error("Failed to parse market data: {}", value, e);
                throw e;
            }
        }
    }
    
    /**
     * 交易策略数据源
     * 从Redis定期读取交易策略配置，支持动态更新
     */
    public static class TradingStrategySource 
            implements org.apache.flink.streaming.api.functions.source.SourceFunction<TradingStrategy> {
        
        private volatile boolean isRunning = true;
        private transient RedisConnectionManager redisManager;
        private final ConfigLoader config;
        
        public TradingStrategySource(ConfigLoader config) {
            this.config = config;
        }
        
        @Override
        public void run(SourceContext<TradingStrategy> ctx) throws Exception {
            // 初始化Redis连接
            redisManager = new RedisConnectionManager(config);
            
            while (isRunning) {
                try (Jedis jedis = redisManager.getConnection()) {
                    // 从Redis读取所有交易策略配置
                    Set<String> keys = jedis.keys("trading:strategy:*");
                    
                    for (String key : keys) {
                        String strategyJson = jedis.get(key);
                        if (strategyJson != null) {
                            ObjectMapper mapper = new ObjectMapper();
                            JsonNode node = mapper.readTree(strategyJson);
                            
                            TradingStrategy strategy = new TradingStrategy(
                                node.get("symbol").asText(),
                                new BigDecimal(node.get("risk_threshold").asText()),
                                new BigDecimal(node.get("volume_threshold").asText()),
                                new BigDecimal(node.get("price_threshold").asText()),
                                node.get("strategy_type").asText(),
                                System.currentTimeMillis()
                            );
                            
                            ctx.collect(strategy);
                        }
                    }
                } catch (Exception e) {
                    LOG.error("Failed to load trading strategies from Redis", e);
                }
                
                // 每30秒更新一次策略
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
     * 信号富化函数
     * 使用广播状态将交易策略关联到突破信号上
     * 这是Flink Broadcast State的典型应用场景
     */
    public static class SignalEnrichmentFunction extends 
            KeyedBroadcastProcessFunction<String, BreakoutSignal, TradingStrategy, EnrichedSignal> {
        
        @Override
        public void processElement(
                BreakoutSignal signal,
                ReadOnlyContext ctx,
                Collector<EnrichedSignal> out) throws Exception {
            
            // 从广播状态中获取该交易对的策略
            ReadOnlyBroadcastState<String, TradingStrategy> strategyState = 
                ctx.getBroadcastState(STRATEGY_STATE_DESC);
            
            TradingStrategy strategy = strategyState.get(signal.symbol);
            
            // 如果没有配置策略，使用默认策略
            if (strategy == null) {
                strategy = new TradingStrategy(
                    signal.symbol,
                    new BigDecimal("50"),      // 默认风险阈值
                    new BigDecimal("1000000"), // 默认成交量阈值
                    new BigDecimal("2.0"),     // 默认价格波动阈值
                    "MODERATE",                // 默认策略类型
                    System.currentTimeMillis()
                );
            }
            
            // 创建富化后的信号
            EnrichedSignal enriched = new EnrichedSignal();
            enriched.symbol = signal.symbol;
            enriched.exchange = signal.exchange;
            enriched.stablePrice = signal.stablePrice;
            enriched.breakoutPrice = signal.breakoutPrice;
            enriched.priceChange = signal.priceChange;
            enriched.volume = signal.volume;
            enriched.strategy = strategy;
            enriched.timestamp = signal.timestamp;
            
            out.collect(enriched);
        }
        
        @Override
        public void processBroadcastElement(
                TradingStrategy strategy,
                Context ctx,
                Collector<EnrichedSignal> out) throws Exception {
            
            // 更新广播状态
            BroadcastState<String, TradingStrategy> strategyState = 
                ctx.getBroadcastState(STRATEGY_STATE_DESC);
            
            strategyState.put(strategy.symbol, strategy);
            
            LOG.info("Updated trading strategy for {}: type={}, risk_threshold={}", 
                strategy.symbol, strategy.strategyType, strategy.riskThreshold);
        }
    }
    
    /**
     * 交易信号生成器
     * 使用窗口函数和键控状态生成最终的交易信号
     * 综合考虑价格、成交量、波动率等多个维度
     */
    public static class TradingSignalGenerator extends 
            ProcessWindowFunction<EnrichedSignal, TradingSignal, String, TimeWindow> {
        
        // 使用ValueState存储历史价格，用于计算波动率
        private transient ValueState<List<BigDecimal>> priceHistoryState;
        
        // 使用MapState存储信号统计，用于计算置信度
        private transient MapState<String, Integer> signalStatsState;
        
        @Override
        public void open(Configuration parameters) {
            // 初始化价格历史状态
            ValueStateDescriptor<List<BigDecimal>> priceHistoryDesc = 
                new ValueStateDescriptor<>(
                    "price-history",
                    TypeInformation.of(new org.apache.flink.api.common.typeinfo.TypeHint<List<BigDecimal>>() {})
                );
            priceHistoryState = getRuntimeContext().getState(priceHistoryDesc);
            
            // 初始化信号统计状态
            MapStateDescriptor<String, Integer> signalStatsDesc = 
                new MapStateDescriptor<>(
                    "signal-stats",
                    TypeInformation.of(String.class),
                    TypeInformation.of(Integer.class)
                );
            signalStatsState = getRuntimeContext().getMapState(signalStatsDesc);
        }
        
        @Override
        public void process(
                String symbol,
                Context context,
                Iterable<EnrichedSignal> signals,
                Collector<TradingSignal> out) throws Exception {
            
            List<EnrichedSignal> signalList = new ArrayList<>();
            signals.forEach(signalList::add);
            
            if (signalList.isEmpty()) {
                return;
            }
            
            // 获取最新信号
            EnrichedSignal latestSignal = signalList.get(signalList.size() - 1);
            TradingStrategy strategy = latestSignal.strategy;
            
            // 计算窗口内的统计指标
            BigDecimal avgPrice = signalList.stream()
                .map(s -> s.breakoutPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(signalList.size()), 8, RoundingMode.HALF_UP);
            
            BigDecimal totalVolume = signalList.stream()
                .map(s -> s.volume)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
            
            // 更新价格历史
            List<BigDecimal> priceHistory = priceHistoryState.value();
            if (priceHistory == null) {
                priceHistory = new ArrayList<>();
            }
            priceHistory.add(latestSignal.breakoutPrice);
            
            // 只保留最近100个价格点
            if (priceHistory.size() > 100) {
                priceHistory = priceHistory.subList(priceHistory.size() - 100, priceHistory.size());
            }
            priceHistoryState.update(priceHistory);
            
            // 计算波动率 (标准差)
            BigDecimal volatility = calculateVolatility(priceHistory);
            
            // 生成交易信号
            String signalType = determineSignalType(
                latestSignal.priceChange,
                volatility,
                totalVolume,
                strategy
            );
            
            // 计算目标价格和止损价格
            Tuple2<BigDecimal, BigDecimal> prices = calculateTargetAndStopLoss(
                latestSignal.breakoutPrice,
                signalType,
                volatility,
                strategy
            );
            
            // 计算信号置信度
            BigDecimal confidence = calculateConfidence(
                signalList.size(),
                volatility,
                totalVolume,
                strategy
            );
            
            // 计算风险评分
            BigDecimal riskScore = calculateRiskScore(
                volatility,
                latestSignal.priceChange,
                totalVolume,
                strategy
            );
            
            // 更新信号统计
            Integer signalCount = signalStatsState.get(signalType);
            if (signalCount == null) {
                signalCount = 0;
            }
            signalStatsState.put(signalType, signalCount + 1);
            
            // 创建交易信号
            TradingSignal tradingSignal = new TradingSignal();
            tradingSignal.signalId = UUID.randomUUID().toString();
            tradingSignal.symbol = symbol;
            tradingSignal.exchange = latestSignal.exchange;
            tradingSignal.signalType = signalType;
            tradingSignal.currentPrice = latestSignal.breakoutPrice;
            tradingSignal.targetPrice = prices.f0;
            tradingSignal.stopLoss = prices.f1;
            tradingSignal.confidence = confidence;
            tradingSignal.riskScore = riskScore;
            tradingSignal.strategyType = strategy.strategyType;
            tradingSignal.volume24h = totalVolume;
            tradingSignal.volatility = volatility;
            tradingSignal.signalCount = signalList.size();
            tradingSignal.timestamp = latestSignal.timestamp;
            tradingSignal.createTime = new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss")
                .format(new Date(latestSignal.timestamp));
            
            // 输出主信号
            out.collect(tradingSignal);
            
            // 如果是高风险信号，输出到侧输出流
            if (riskScore.compareTo(strategy.riskThreshold) > 0) {
                context.output(HIGH_RISK_SIGNAL_TAG, tradingSignal.toJson());
            }
        }
        
        /**
         * 计算波动率（标准差）
         */
        private BigDecimal calculateVolatility(List<BigDecimal> prices) {
            if (prices.size() < 2) {
                return BigDecimal.ZERO;
            }
            
            // 计算平均值
            BigDecimal mean = prices.stream()
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(prices.size()), 8, RoundingMode.HALF_UP);
            
            // 计算方差
            BigDecimal variance = prices.stream()
                .map(p -> p.subtract(mean).pow(2))
                .reduce(BigDecimal.ZERO, BigDecimal::add)
                .divide(new BigDecimal(prices.size()), 8, RoundingMode.HALF_UP);
            
            // 返回标准差（波动率）
            return new BigDecimal(Math.sqrt(variance.doubleValue()))
                .setScale(8, RoundingMode.HALF_UP);
        }
        
        /**
         * 确定信号类型：BUY, SELL, HOLD
         */
        private String determineSignalType(
                BigDecimal priceChange,
                BigDecimal volatility,
                BigDecimal volume,
                TradingStrategy strategy) {
            
            // 价格上涨且成交量充足 -> BUY
            if (priceChange.compareTo(strategy.priceThreshold) > 0 &&
                volume.compareTo(strategy.volumeThreshold) > 0) {
                return "BUY";
            }
            
            // 价格下跌且成交量充足 -> SELL
            if (priceChange.compareTo(strategy.priceThreshold.negate()) < 0 &&
                volume.compareTo(strategy.volumeThreshold) > 0) {
                return "SELL";
            }
            
            // 其他情况 -> HOLD
            return "HOLD";
        }
        
        /**
         * 计算目标价格和止损价格
         */
        private Tuple2<BigDecimal, BigDecimal> calculateTargetAndStopLoss(
                BigDecimal currentPrice,
                String signalType,
                BigDecimal volatility,
                TradingStrategy strategy) {
            
            BigDecimal targetPrice;
            BigDecimal stopLoss;
            
            // 根据策略类型调整风险收益比
            BigDecimal riskRewardRatio;
            switch (strategy.strategyType) {
                case "AGGRESSIVE":
                    riskRewardRatio = new BigDecimal("3.0");  // 3:1
                    break;
                case "CONSERVATIVE":
                    riskRewardRatio = new BigDecimal("1.5");  // 1.5:1
                    break;
                default:  // MODERATE
                    riskRewardRatio = new BigDecimal("2.0");  // 2:1
            }
            
            // 使用波动率的2倍作为止损距离
            BigDecimal stopLossDistance = volatility.multiply(new BigDecimal("2"));
            
            if ("BUY".equals(signalType)) {
                // 买入信号：目标价格向上，止损向下
                stopLoss = currentPrice.subtract(stopLossDistance);
                targetPrice = currentPrice.add(stopLossDistance.multiply(riskRewardRatio));
            } else if ("SELL".equals(signalType)) {
                // 卖出信号：目标价格向下，止损向上
                stopLoss = currentPrice.add(stopLossDistance);
                targetPrice = currentPrice.subtract(stopLossDistance.multiply(riskRewardRatio));
            } else {
                // 持有信号：保持当前价格
                targetPrice = currentPrice;
                stopLoss = currentPrice;
            }
            
            return Tuple2.of(targetPrice, stopLoss);
        }
        
        /**
         * 计算信号置信度 (0-100)
         */
        private BigDecimal calculateConfidence(
                int signalCount,
                BigDecimal volatility,
                BigDecimal volume,
                TradingStrategy strategy) {
            
            // 基础置信度：信号数量越多，置信度越高
            BigDecimal baseConfidence = new BigDecimal(Math.min(signalCount * 10, 50));
            
            // 成交量加成：成交量越大，置信度越高
            BigDecimal volumeBonus = volume.compareTo(strategy.volumeThreshold) > 0 ?
                new BigDecimal("20") : BigDecimal.ZERO;
            
            // 波动率惩罚：波动率越高，置信度越低
            BigDecimal volatilityPenalty = volatility.multiply(new BigDecimal("5"));
            
            BigDecimal confidence = baseConfidence
                .add(volumeBonus)
                .subtract(volatilityPenalty);
            
            // 限制在0-100范围内
            if (confidence.compareTo(BigDecimal.ZERO) < 0) {
                confidence = BigDecimal.ZERO;
            }
            if (confidence.compareTo(new BigDecimal("100")) > 0) {
                confidence = new BigDecimal("100");
            }
            
            return confidence.setScale(2, RoundingMode.HALF_UP);
        }
        
        /**
         * 计算风险评分 (0-100)
         */
        private BigDecimal calculateRiskScore(
                BigDecimal volatility,
                BigDecimal priceChange,
                BigDecimal volume,
                TradingStrategy strategy) {
            
            // 波动率风险：波动率越高，风险越大
            BigDecimal volatilityRisk = volatility.multiply(new BigDecimal("10"));
            
            // 价格变化风险：价格变化越剧烈，风险越大
            BigDecimal priceRisk = priceChange.abs().multiply(new BigDecimal("5"));
            
            // 流动性风险：成交量越小，风险越大
            BigDecimal liquidityRisk = volume.compareTo(strategy.volumeThreshold) < 0 ?
                new BigDecimal("30") : BigDecimal.ZERO;
            
            BigDecimal riskScore = volatilityRisk
                .add(priceRisk)
                .add(liquidityRisk);
            
            // 限制在0-100范围内
            if (riskScore.compareTo(BigDecimal.ZERO) < 0) {
                riskScore = BigDecimal.ZERO;
            }
            if (riskScore.compareTo(new BigDecimal("100")) > 0) {
                riskScore = new BigDecimal("100");
            }
            
            return riskScore.setScale(2, RoundingMode.HALF_UP);
        }
    }
}
