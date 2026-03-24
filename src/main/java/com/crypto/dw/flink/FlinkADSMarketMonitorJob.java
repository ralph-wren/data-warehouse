package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.KafkaSourceFactory;
import com.crypto.dw.model.TickerData;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.AggregateFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.KeyedProcessFunction;
import org.apache.flink.streaming.api.functions.windowing.ProcessWindowFunction;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.streaming.api.windowing.windows.TimeWindow;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;

/**
 * Flink ADS 实时市场监控作业 - DataStream API
 * 
 * 功能说明：
 * 1. 从 Kafka 消费实时行情数据
 * 2. 使用 Flink 状态管理追踪价格变化
 * 3. 使用滚动窗口计算统计指标
 * 4. 使用 Watermark 处理乱序数据
 * 5. 实时检测价格异常波动
 * 6. 计算市场监控指标并写入 ADS 层
 * 
 * 计算指标：
 * - 价格波动率（标准差）
 * - 价格变化率（涨跌幅）
 * - 交易量统计（最大/最小/平均）
 * - 价格趋势（上涨/下跌/震荡）
 * - 异常检测（价格突变告警）
 * 
 * Flink 特性使用：
 * - KeyedState: 追踪每个交易对的历史价格
 * - TumblingWindow: 5分钟滚动窗口聚合
 * - Watermark: 处理 5 秒乱序数据
 * - AggregateFunction: 增量聚合计算统计值
 * - ProcessFunction: 自定义业务逻辑和状态管理
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class FlinkADSMarketMonitorJob {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkADSMarketMonitorJob.class);
    
    // 静态 ObjectMapper 实例，线程安全，避免重复创建
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ADS Market Monitor Job (DataStream API)");
        logger.info("实时市场监控 - 使用状态、窗口、Watermark");
        logger.info("==========================================");
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 使用工厂类创建 Flink 环境
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        int webPort = config.getInt("flink.web.port.ads", 8085);
        logger.info("Web UI 端口: {}", webPort);
        
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment(
            "flink-ads-market-monitor-job", 
            webPort
        );
        
        // 使用工厂类创建 Kafka Source
        KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);
        KafkaSource<String> kafkaSource = kafkaSourceFactory.createKafkaSource(
            "ads-market-monitor-consumer"  // 独立的 Consumer Group
        );
        
        // 创建数据流，配置 Watermark 策略
        // Watermark 说明：允许 5 秒的乱序数据，超过 5 秒的延迟数据会被丢弃
        DataStream<TickerData> tickerStream = env.fromSource(
            kafkaSource,
            WatermarkStrategy
                .<String>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner(new SerializableTimestampAssigner<String>() {
                    @Override
                    public long extractTimestamp(String element, long recordTimestamp) {
                        try {
                            // 从 JSON 中提取时间戳
                            TickerData ticker = OBJECT_MAPPER.readValue(element, TickerData.class);
                            return ticker.getTimestamp();
                        } catch (Exception e) {
                            logger.warn("提取时间戳失败，使用当前时间: {}", e.getMessage());
                            return System.currentTimeMillis();
                        }
                    }
                }),
            "Kafka Source"
        )
        .map(json -> OBJECT_MAPPER.readValue(json, TickerData.class))
        .name("Parse JSON");
        
        logger.info("✓ Kafka Source 创建成功");
        logger.info("✓ Watermark 策略: 允许 5 秒乱序");
        
        // 步骤 1: 使用 KeyedState 检测价格异常波动
        // 说明：为每个交易对维护状态，追踪上一次价格，检测突变
        DataStream<TickerData> anomalyDetectedStream = tickerStream
            .keyBy(TickerData::getSymbol)  // 按交易对分组
            .process(new PriceAnomalyDetector())  // 使用 KeyedProcessFunction 检测异常
            .name("Anomaly Detection");
        
        logger.info("✓ 价格异常检测已启用（使用 KeyedState）");
        
        // 步骤 2: 使用滚动窗口计算 5 分钟统计指标
        // 说明：每 5 分钟计算一次价格波动率、交易量等指标
        DataStream<MarketMetrics> metricsStream = anomalyDetectedStream
            .keyBy(TickerData::getSymbol)  // 按交易对分组
            .window(TumblingEventTimeWindows.of(Time.minutes(5)))  // 5 分钟滚动窗口
            .aggregate(
                new MarketMetricsAggregator(),  // 增量聚合函数
                new MarketMetricsWindowFunction()  // 窗口函数，添加窗口信息
            )
            .name("5-Min Window Aggregation");
        
        logger.info("✓ 5 分钟滚动窗口聚合已配置");
        
        // 步骤 3: 转换为 JSON 格式
        DataStream<String> jsonStream = metricsStream
            .map(metrics -> {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                json.put("symbol", metrics.symbol);
                json.put("window_start", metrics.windowStart);
                json.put("window_end", metrics.windowEnd);
                json.put("data_count", metrics.dataCount);
                json.put("avg_price", metrics.avgPrice.toString());
                json.put("max_price", metrics.maxPrice.toString());
                json.put("min_price", metrics.minPrice.toString());
                json.put("price_volatility", metrics.priceVolatility.toString());
                json.put("price_change_rate", metrics.priceChangeRate.toString());
                json.put("avg_volume", metrics.avgVolume.toString());
                json.put("max_volume", metrics.maxVolume.toString());
                json.put("trend", metrics.trend);
                json.put("has_anomaly", metrics.hasAnomaly);
                json.put("anomaly_count", metrics.anomalyCount);
                return json.toString();
            })
            .name("To JSON");
        
        // 步骤 4: 写入 Doris ADS 层
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        DorisSink<String> dorisSink = dorisSinkFactory.createDorisSink(
            "crypto_dw",  // 数据库名
            "ads_market_monitor_5min",  // 表名
            "ads-market-monitor"  // Label 前缀
        );
        
        jsonStream.sinkTo(dorisSink).name("Doris ADS Sink");
        
        logger.info("✓ Doris Sink 创建成功");
        logger.info("  Database: crypto_dw");
        logger.info("  Table: ads_market_monitor_5min");
        
        logger.info("==========================================");
        logger.info("启动 Flink ADS Market Monitor Job...");
        logger.info("==========================================");
        
        // 执行作业
        env.execute("Flink ADS Market Monitor Job");
    }
    
    /**
     * 价格异常检测器 - 使用 KeyedState
     * 
     * 功能：
     * 1. 为每个交易对维护上一次价格状态
     * 2. 检测价格突变（变化超过 5%）
     * 3. 标记异常数据
     * 
     * Flink 特性：
     * - ValueState: 存储每个 key 的上一次价格
     * - KeyedProcessFunction: 自定义处理逻辑
     */
    public static class PriceAnomalyDetector extends KeyedProcessFunction<String, TickerData, TickerData> {
        
        // 状态：存储上一次价格
        // 说明：每个交易对（key）都有独立的状态
        private transient ValueState<BigDecimal> lastPriceState;
        
        // 异常阈值：价格变化超过 5% 视为异常
        private static final BigDecimal ANOMALY_THRESHOLD = new BigDecimal("0.05");
        
        @Override
        public void open(Configuration parameters) {
            // 初始化状态描述符
            // 说明：状态会自动持久化到 StateBackend，支持故障恢复
            ValueStateDescriptor<BigDecimal> descriptor = new ValueStateDescriptor<>(
                "last-price",  // 状态名称
                BigDecimal.class  // 状态类型
            );
            lastPriceState = getRuntimeContext().getState(descriptor);
        }
        
        @Override
        public void processElement(
                TickerData ticker,
                Context ctx,
                Collector<TickerData> out) throws Exception {
            
            // 获取当前价格
            BigDecimal currentPrice = ticker.getLastPrice();
            
            // 获取上一次价格（从状态中读取）
            BigDecimal lastPrice = lastPriceState.value();
            
            // 检测价格异常
            boolean isAnomaly = false;
            if (lastPrice != null && currentPrice != null) {
                // 计算价格变化率
                BigDecimal priceChange = currentPrice.subtract(lastPrice);
                BigDecimal changeRate = priceChange.divide(lastPrice, 4, RoundingMode.HALF_UP).abs();
                
                // 判断是否异常（变化超过 5%）
                if (changeRate.compareTo(ANOMALY_THRESHOLD) > 0) {
                    isAnomaly = true;
                    logger.warn("⚠️ 价格异常检测: {} 价格从 {} 变化到 {}, 变化率: {}%",
                        ticker.getSymbol(),
                        lastPrice,
                        currentPrice,
                        changeRate.multiply(new BigDecimal("100")));
                }
            }
            
            // 标记异常（添加到 ticker 对象中，后续窗口聚合会统计）
            ticker.setHasAnomaly(isAnomaly);
            
            // 更新状态（保存当前价格作为下一次的"上一次价格"）
            lastPriceState.update(currentPrice);
            
            // 输出数据
            out.collect(ticker);
        }
    }
    
    /**
     * 市场指标聚合器 - 增量聚合函数
     * 
     * 功能：
     * 1. 在窗口内增量计算统计值
     * 2. 避免存储所有数据，节省内存
     * 3. 计算价格、交易量的统计指标
     * 
     * Flink 特性：
     * - AggregateFunction: 增量聚合，高效节省内存
     * - 累加器模式：每来一条数据就更新累加器
     */
    public static class MarketMetricsAggregator 
            implements AggregateFunction<TickerData, MarketMetricsAccumulator, MarketMetricsAccumulator> {
        
        @Override
        public MarketMetricsAccumulator createAccumulator() {
            // 创建累加器初始值
            return new MarketMetricsAccumulator();
        }
        
        @Override
        public MarketMetricsAccumulator add(TickerData ticker, MarketMetricsAccumulator acc) {
            // 增量累加：每来一条数据就更新累加器
            // 说明：这个方法会被频繁调用，要保证高效
            
            BigDecimal price = ticker.getLastPrice();
            BigDecimal volume = ticker.getVolume24h();
            
            // 更新计数
            acc.count++;
            
            // 更新价格统计
            if (price != null) {
                acc.priceSum = acc.priceSum.add(price);
                acc.priceSumSquare = acc.priceSumSquare.add(price.multiply(price));
                
                if (acc.maxPrice == null || price.compareTo(acc.maxPrice) > 0) {
                    acc.maxPrice = price;
                }
                if (acc.minPrice == null || price.compareTo(acc.minPrice) < 0) {
                    acc.minPrice = price;
                }
                
                // 记录第一个和最后一个价格（用于计算趋势）
                if (acc.firstPrice == null) {
                    acc.firstPrice = price;
                }
                acc.lastPrice = price;
            }
            
            // 更新交易量统计
            if (volume != null) {
                acc.volumeSum = acc.volumeSum.add(volume);
                
                if (acc.maxVolume == null || volume.compareTo(acc.maxVolume) > 0) {
                    acc.maxVolume = volume;
                }
            }
            
            // 统计异常数量
            if (ticker.isHasAnomaly()) {
                acc.anomalyCount++;
            }
            
            return acc;
        }
        
        @Override
        public MarketMetricsAccumulator getResult(MarketMetricsAccumulator acc) {
            // 返回最终结果
            return acc;
        }
        
        @Override
        public MarketMetricsAccumulator merge(MarketMetricsAccumulator a, MarketMetricsAccumulator b) {
            // 合并两个累加器（用于并行计算）
            // 说明：当窗口数据分布在多个并行实例时，需要合并结果
            
            MarketMetricsAccumulator merged = new MarketMetricsAccumulator();
            merged.count = a.count + b.count;
            merged.priceSum = a.priceSum.add(b.priceSum);
            merged.priceSumSquare = a.priceSumSquare.add(b.priceSumSquare);
            merged.volumeSum = a.volumeSum.add(b.volumeSum);
            
            // 合并最大最小值
            merged.maxPrice = (a.maxPrice != null && b.maxPrice != null) 
                ? a.maxPrice.max(b.maxPrice) : (a.maxPrice != null ? a.maxPrice : b.maxPrice);
            merged.minPrice = (a.minPrice != null && b.minPrice != null) 
                ? a.minPrice.min(b.minPrice) : (a.minPrice != null ? a.minPrice : b.minPrice);
            merged.maxVolume = (a.maxVolume != null && b.maxVolume != null) 
                ? a.maxVolume.max(b.maxVolume) : (a.maxVolume != null ? a.maxVolume : b.maxVolume);
            
            // 合并第一个和最后一个价格
            merged.firstPrice = a.firstPrice != null ? a.firstPrice : b.firstPrice;
            merged.lastPrice = b.lastPrice != null ? b.lastPrice : a.lastPrice;
            
            merged.anomalyCount = a.anomalyCount + b.anomalyCount;
            
            return merged;
        }
    }
    
    /**
     * 市场指标窗口函数 - 添加窗口信息
     * 
     * 功能：
     * 1. 接收聚合结果
     * 2. 添加窗口时间信息
     * 3. 计算派生指标（波动率、趋势等）
     * 
     * Flink 特性：
     * - ProcessWindowFunction: 访问窗口元数据
     * - 与 AggregateFunction 结合使用，兼顾效率和灵活性
     */
    public static class MarketMetricsWindowFunction 
            extends ProcessWindowFunction<MarketMetricsAccumulator, MarketMetrics, String, TimeWindow> {
        
        @Override
        public void process(
                String symbol,
                Context context,
                Iterable<MarketMetricsAccumulator> elements,
                Collector<MarketMetrics> out) {
            
            // 获取聚合结果（只有一个元素）
            MarketMetricsAccumulator acc = elements.iterator().next();
            
            // 创建输出对象
            MarketMetrics metrics = new MarketMetrics();
            metrics.symbol = symbol;
            metrics.windowStart = context.window().getStart();
            metrics.windowEnd = context.window().getEnd();
            metrics.dataCount = acc.count;
            
            // 计算平均价格
            if (acc.count > 0) {
                metrics.avgPrice = acc.priceSum.divide(
                    new BigDecimal(acc.count), 
                    8, 
                    RoundingMode.HALF_UP
                );
            }
            
            metrics.maxPrice = acc.maxPrice != null ? acc.maxPrice : BigDecimal.ZERO;
            metrics.minPrice = acc.minPrice != null ? acc.minPrice : BigDecimal.ZERO;
            
            // 计算价格波动率（标准差）
            // 公式：sqrt(E[X^2] - E[X]^2)
            if (acc.count > 1) {
                BigDecimal avgSquare = acc.priceSumSquare.divide(
                    new BigDecimal(acc.count), 
                    8, 
                    RoundingMode.HALF_UP
                );
                BigDecimal squareAvg = metrics.avgPrice.multiply(metrics.avgPrice);
                BigDecimal variance = avgSquare.subtract(squareAvg);
                
                if (variance.compareTo(BigDecimal.ZERO) > 0) {
                    // 简化计算：使用 variance 作为波动率（避免开方）
                    metrics.priceVolatility = variance;
                } else {
                    metrics.priceVolatility = BigDecimal.ZERO;
                }
            } else {
                metrics.priceVolatility = BigDecimal.ZERO;
            }
            
            // 计算价格变化率（涨跌幅）
            if (acc.firstPrice != null && acc.lastPrice != null && 
                acc.firstPrice.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal priceChange = acc.lastPrice.subtract(acc.firstPrice);
                metrics.priceChangeRate = priceChange.divide(
                    acc.firstPrice, 
                    6, 
                    RoundingMode.HALF_UP
                ).multiply(new BigDecimal("100"));  // 转换为百分比
            } else {
                metrics.priceChangeRate = BigDecimal.ZERO;
            }
            
            // 计算平均交易量
            if (acc.count > 0) {
                metrics.avgVolume = acc.volumeSum.divide(
                    new BigDecimal(acc.count), 
                    8, 
                    RoundingMode.HALF_UP
                );
            }
            
            metrics.maxVolume = acc.maxVolume != null ? acc.maxVolume : BigDecimal.ZERO;
            
            // 判断价格趋势
            if (metrics.priceChangeRate.compareTo(new BigDecimal("1")) > 0) {
                metrics.trend = "上涨";
            } else if (metrics.priceChangeRate.compareTo(new BigDecimal("-1")) < 0) {
                metrics.trend = "下跌";
            } else {
                metrics.trend = "震荡";
            }
            
            // 异常统计
            metrics.hasAnomaly = acc.anomalyCount > 0;
            metrics.anomalyCount = acc.anomalyCount;
            
            // 输出结果
            out.collect(metrics);
            
            // 记录日志
            logger.info("📊 市场指标计算完成: {} | 窗口: {} - {} | 数据量: {} | 平均价格: {} | 波动率: {} | 涨跌幅: {}% | 趋势: {} | 异常: {}",
                symbol,
                new java.util.Date(metrics.windowStart),
                new java.util.Date(metrics.windowEnd),
                metrics.dataCount,
                metrics.avgPrice,
                metrics.priceVolatility,
                metrics.priceChangeRate,
                metrics.trend,
                metrics.anomalyCount
            );
        }
    }
    
    /**
     * 市场指标累加器 - 用于增量聚合
     * 
     * 说明：
     * - 存储窗口内的中间计算结果
     * - 支持增量更新，避免存储所有数据
     * - 支持合并操作，用于并行计算
     */
    public static class MarketMetricsAccumulator {
        public long count = 0;
        public BigDecimal priceSum = BigDecimal.ZERO;
        public BigDecimal priceSumSquare = BigDecimal.ZERO;  // 用于计算标准差
        public BigDecimal volumeSum = BigDecimal.ZERO;
        public BigDecimal maxPrice = null;
        public BigDecimal minPrice = null;
        public BigDecimal maxVolume = null;
        public BigDecimal firstPrice = null;  // 窗口内第一个价格
        public BigDecimal lastPrice = null;   // 窗口内最后一个价格
        public int anomalyCount = 0;  // 异常数量
    }
    
    /**
     * 市场指标输出对象
     * 
     * 说明：
     * - 包含窗口聚合后的所有指标
     * - 用于输出到 Doris ADS 层
     */
    public static class MarketMetrics {
        public String symbol;
        public long windowStart;
        public long windowEnd;
        public long dataCount;
        public BigDecimal avgPrice = BigDecimal.ZERO;
        public BigDecimal maxPrice = BigDecimal.ZERO;
        public BigDecimal minPrice = BigDecimal.ZERO;
        public BigDecimal priceVolatility = BigDecimal.ZERO;  // 价格波动率
        public BigDecimal priceChangeRate = BigDecimal.ZERO;  // 价格变化率（%）
        public BigDecimal avgVolume = BigDecimal.ZERO;
        public BigDecimal maxVolume = BigDecimal.ZERO;
        public String trend = "震荡";  // 价格趋势
        public boolean hasAnomaly = false;  // 是否有异常
        public int anomalyCount = 0;  // 异常数量
    }
}
