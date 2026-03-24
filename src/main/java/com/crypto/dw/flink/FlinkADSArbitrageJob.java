package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.KafkaSourceFactory;
import com.crypto.dw.model.TickerData;
import com.crypto.dw.redis.RedisConnectionManager;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.api.common.functions.RichMapFunction;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.common.typeinfo.BasicTypeInfo;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.BroadcastStream;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

/**
 * Flink ADS 跨市场套利机会计算作业 - 复杂流处理示例
 * 
 * 业务场景：
 * 计算加密货币现货和合约（永续合约）之间的价差套利机会
 * 
 * 技术特性：
 * 1. 双流 Join：现货价格流 + 合约价格流
 * 2. Interval Join：时间窗口内的流关联
 * 3. 广播流：从 Redis 读取交易对白名单
 * 4. 维度关联：使用广播状态过滤数据
 * 5. 性能优化：异步 IO、状态管理、背压控制
 * 
 * 数据流图：
 * <pre>
 * Kafka(现货 crypto-ticker-spot) ──┐
 *                                   ├─→ Interval Join ──→ 计算套利空间 ──┐
 * Kafka(合约 crypto-ticker-swap) ──┘                                   │
 *                                                                      ├─→ 广播 Join ──→ 过滤 ──→ Doris
 * Redis(白名单) ──→ 广播流 ────────────────────────────────────────────┘
 * </pre>
 * 
 * 性能考虑：
 * - 使用 Interval Join 而非 Window Join，减少状态存储
 * - 广播流用于维度关联，避免每条数据查询 Redis
 * - 合理设置并行度，平衡吞吐量和延迟
 * - 使用 RocksDB StateBackend，支持大状态
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class FlinkADSArbitrageJob {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkADSArbitrageJob.class);
    
    // 静态 ObjectMapper 实例，线程安全
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    // 套利阈值：价差超过 0.5% 才认为有套利机会
    private static final BigDecimal ARBITRAGE_THRESHOLD = new BigDecimal("0.005");
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ADS Arbitrage Job (复杂流处理)");
        logger.info("双流 Join + 广播流 + Redis 维度关联");
        logger.info("==========================================");
        
        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        
        // 使用工厂类创建 Flink 环境
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        int webPort = config.getInt("flink.web.port.ads-arbitrage", 8086);
        logger.info("Web UI 端口: {}", webPort);
        
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment(
            "flink-ads-arbitrage-job", 
            webPort
        );
        
        // 性能优化：设置合理的并行度
        // 说明：根据 Kafka 分区数和 CPU 核心数设置
        int parallelism = config.getInt("flink.execution.parallelism", 4);
        env.setParallelism(parallelism);
        logger.info("并行度: {}", parallelism);
        
        // 使用工厂类创建 Kafka Source
        // 重要：套利作业需要同时读取现货和合约数据，使用不同的 Consumer Group
        KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);
        
        // ========== 步骤 1: 创建现货价格流 ==========
        logger.info("创建现货价格流...");
        KafkaSource<String> spotKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
            "ads-arbitrage",  // 作业类型
            config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot")  // 现货 Topic
        );
        
        DataStream<SpotPrice> spotStream = env.fromSource(
            spotKafkaSource,
            WatermarkStrategy
                .<String>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner(new SerializableTimestampAssigner<String>() {
                    @Override
                    public long extractTimestamp(String element, long recordTimestamp) {
                        try {
                            TickerData ticker = OBJECT_MAPPER.readValue(element, TickerData.class);
                            return ticker.getTimestamp();
                        } catch (Exception e) {
                            return System.currentTimeMillis();
                        }
                    }
                }),
            "Spot Kafka Source"
        )
        .map(json -> {
            TickerData ticker = OBJECT_MAPPER.readValue(json, TickerData.class);
            SpotPrice spot = new SpotPrice();
            spot.symbol = ticker.getSymbol();
            spot.price = ticker.getLastPrice();
            spot.timestamp = ticker.getTimestamp();
            return spot;
        })
        .filter(spot -> spot.price != null && spot.price.compareTo(BigDecimal.ZERO) > 0)
        .name("Parse Spot Price");
        
        logger.info("✓ 现货价格流创建成功");
        
        // ========== 步骤 2: 创建合约价格流 ==========
        // 说明：从合约 Topic 读取数据，使用相同的 Consumer Group（套利作业统一管理）
        logger.info("创建合约价格流...");
        KafkaSource<String> swapKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
            "ads-arbitrage",  // 作业类型（与现货使用相同的 Group ID）
            config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap")  // 合约 Topic
        );
        
        DataStream<FuturesPrice> futuresStream = env.fromSource(
            swapKafkaSource,
            WatermarkStrategy
                .<String>forBoundedOutOfOrderness(Duration.ofSeconds(5))
                .withTimestampAssigner(new SerializableTimestampAssigner<String>() {
                    @Override
                    public long extractTimestamp(String element, long recordTimestamp) {
                        try {
                            TickerData ticker = OBJECT_MAPPER.readValue(element, TickerData.class);
                            return ticker.getTimestamp();
                        } catch (Exception e) {
                            return System.currentTimeMillis();
                        }
                    }
                }),
            "Futures Kafka Source"
        )
        .map(json -> {
            TickerData ticker = OBJECT_MAPPER.readValue(json, TickerData.class);
            FuturesPrice futures = new FuturesPrice();
            // 从 instId 中提取交易对（去掉 -SWAP 后缀）
            // 例如：BTC-USDT-SWAP → BTC-USDT
            String instId = ticker.getSymbol();
            if (instId.endsWith("-SWAP")) {
                futures.symbol = instId.substring(0, instId.length() - 5);
            } else {
                futures.symbol = instId;
            }
            futures.price = ticker.getLastPrice();  // 合约价格
            futures.timestamp = ticker.getTimestamp();
            return futures;
        })
        .filter(futures -> futures.price != null && futures.price.compareTo(BigDecimal.ZERO) > 0)
        .name("Parse Swap Price");
        
        logger.info("✓ 合约价格流创建成功");
        
        // ========== 步骤 3: Interval Join 关联两个流 ==========
        // 说明：使用 Interval Join 在时间窗口内关联现货和期货价格
        // 优势：相比 Window Join，Interval Join 状态更小，性能更好
        logger.info("配置 Interval Join...");
        
        DataStream<ArbitrageOpportunity> arbitrageStream = spotStream
            .keyBy(spot -> spot.symbol)
            .intervalJoin(futuresStream.keyBy(futures -> futures.symbol))
            .between(Time.seconds(-10), Time.seconds(10))  // 允许 ±10 秒的时间差
            .process(new ArbitrageCalculator())
            .name("Calculate Arbitrage");
        
        logger.info("✓ Interval Join 配置成功（时间窗口: ±10 秒）");
        
        // ========== 步骤 4: 从 Redis 读取交易对白名单（广播流）==========
        // 说明：使用广播流将白名单数据分发到所有并行实例
        // 优势：避免每条数据都查询 Redis，大幅提升性能
        logger.info("创建广播流（从 Redis 读取白名单）...");
        
        // 定义广播状态描述符
        MapStateDescriptor<String, Boolean> whitelistStateDescriptor = new MapStateDescriptor<>(
            "whitelist-state",
            BasicTypeInfo.STRING_TYPE_INFO,
            BasicTypeInfo.BOOLEAN_TYPE_INFO
        );
        
        // 创建广播流：定期从 Redis 读取白名单
        DataStream<Tuple2<String, Boolean>> whitelistSource = env
            .addSource(new RedisWhitelistSource(config))
            .name("Redis Whitelist Source");
        
        BroadcastStream<Tuple2<String, Boolean>> whitelistBroadcast = whitelistSource
            .broadcast(whitelistStateDescriptor);
        
        logger.info("✓ 广播流创建成功");
        
        // ========== 步骤 5: 使用广播状态过滤套利机会 ==========
        // 说明：只保留白名单中的交易对
        logger.info("配置广播 Join（过滤白名单）...");
        
        DataStream<ArbitrageOpportunity> filteredStream = arbitrageStream
            .connect(whitelistBroadcast)
            .process(new WhitelistFilter(whitelistStateDescriptor))
            .name("Whitelist Filter");
        
        logger.info("✓ 广播 Join 配置成功");
        
        // ========== 步骤 6: 转换为 JSON 并写入 Doris ==========
        DataStream<String> jsonStream = filteredStream
            .map(opportunity -> {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                json.put("symbol", opportunity.symbol);
                json.put("spot_price", opportunity.spotPrice.toString());
                json.put("futures_price", opportunity.futuresPrice.toString());
                json.put("spread", opportunity.spread.toString());
                json.put("spread_rate", opportunity.spreadRate.toString());
                json.put("arbitrage_direction", opportunity.arbitrageDirection);
                json.put("profit_estimate", opportunity.profitEstimate.toString());
                json.put("timestamp", opportunity.timestamp);
                return json.toString();
            })
            .name("To JSON");
        
        // 写入 Doris
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        DorisSink<String> dorisSink = dorisSinkFactory.createDorisSink(
            "crypto_dw",
            "ads_arbitrage_opportunities",
            "ads-arbitrage"
        );
        
        jsonStream.sinkTo(dorisSink).name("Doris ADS Sink");
        
        logger.info("✓ Doris Sink 创建成功");
        logger.info("  Database: crypto_dw");
        logger.info("  Table: ads_arbitrage_opportunities");
        
        logger.info("==========================================");
        logger.info("启动 Flink ADS Arbitrage Job...");
        logger.info("==========================================");
        
        // 执行作业
        env.execute("Flink ADS Arbitrage Job");
    }
    
    /**
     * 套利机会计算器 - Interval Join 处理函数
     * 
     * 功能：
     * 1. 接收现货和期货价格
     * 2. 计算价差和价差率
     * 3. 判断套利方向
     * 4. 估算利润
     * 
     * 性能优化：
     * - 使用 Interval Join 而非 Window Join
     * - 减少状态存储
     * - 提高吞吐量
     */
    public static class ArbitrageCalculator 
            extends ProcessJoinFunction<SpotPrice, FuturesPrice, ArbitrageOpportunity> {
        
        @Override
        public void processElement(
                SpotPrice spot,
                FuturesPrice futures,
                Context ctx,
                Collector<ArbitrageOpportunity> out) {
            
            // 计算价差
            BigDecimal spread = futures.price.subtract(spot.price);
            
            // 计算价差率（相对于现货价格）
            BigDecimal spreadRate = spread.divide(spot.price, 6, RoundingMode.HALF_UP);
            
            // 判断是否有套利机会（价差率绝对值超过阈值）
            if (spreadRate.abs().compareTo(ARBITRAGE_THRESHOLD) > 0) {
                ArbitrageOpportunity opportunity = new ArbitrageOpportunity();
                opportunity.symbol = spot.symbol;
                opportunity.spotPrice = spot.price;
                opportunity.futuresPrice = futures.price;
                opportunity.spread = spread;
                opportunity.spreadRate = spreadRate.multiply(new BigDecimal("100"));  // 转换为百分比
                
                // 判断套利方向
                if (spread.compareTo(BigDecimal.ZERO) > 0) {
                    // 合约价格 > 现货价格：做空合约，做多现货
                    opportunity.arbitrageDirection = "做空合约/做多现货";
                } else {
                    // 现货价格 > 合约价格：做多合约，做空现货
                    opportunity.arbitrageDirection = "做多合约/做空现货";
                }
                
                // 估算利润（假设交易 1 个单位，扣除 0.1% 手续费）
                BigDecimal fee = spot.price.multiply(new BigDecimal("0.001"));
                opportunity.profitEstimate = spread.abs().subtract(fee.multiply(new BigDecimal("2")));
                
                opportunity.timestamp = System.currentTimeMillis();
                
                // 输出套利机会
                out.collect(opportunity);
                
                // 记录日志
                logger.info("💰 发现套利机会: {} | 现货: {} | 期货: {} | 价差率: {}% | 方向: {} | 预估利润: {}",
                    opportunity.symbol,
                    opportunity.spotPrice,
                    opportunity.futuresPrice,
                    opportunity.spreadRate,
                    opportunity.arbitrageDirection,
                    opportunity.profitEstimate
                );
            }
        }
    }
    
    /**
     * 白名单过滤器 - 广播处理函数
     * 
     * 功能：
     * 1. 接收广播的白名单数据
     * 2. 更新广播状态
     * 3. 使用广播状态过滤数据流
     * 
     * 性能优化：
     * - 使用广播状态，避免每条数据查询 Redis
     * - 所有并行实例共享白名单数据
     * - 定期更新白名单，保持数据新鲜度
     */
    public static class WhitelistFilter 
            extends BroadcastProcessFunction<ArbitrageOpportunity, Tuple2<String, Boolean>, ArbitrageOpportunity> {
        
        private final MapStateDescriptor<String, Boolean> whitelistStateDescriptor;
        
        public WhitelistFilter(MapStateDescriptor<String, Boolean> whitelistStateDescriptor) {
            this.whitelistStateDescriptor = whitelistStateDescriptor;
        }
        
        @Override
        public void processElement(
                ArbitrageOpportunity opportunity,
                ReadOnlyContext ctx,
                Collector<ArbitrageOpportunity> out) throws Exception {
            
            // 读取广播状态
            ReadOnlyBroadcastState<String, Boolean> whitelistState = 
                ctx.getBroadcastState(whitelistStateDescriptor);
            
            // 检查是否在白名单中
            Boolean isWhitelisted = whitelistState.get(opportunity.symbol);
            
            if (isWhitelisted != null && isWhitelisted) {
                // 在白名单中，输出数据
                out.collect(opportunity);
            } else {
                // 不在白名单中，过滤掉
                logger.debug("过滤非白名单交易对: {}", opportunity.symbol);
            }
        }
        
        @Override
        public void processBroadcastElement(
                Tuple2<String, Boolean> whitelistEntry,
                Context ctx,
                Collector<ArbitrageOpportunity> out) throws Exception {
            
            // 更新广播状态
            BroadcastState<String, Boolean> whitelistState = 
                ctx.getBroadcastState(whitelistStateDescriptor);
            
            whitelistState.put(whitelistEntry.f0, whitelistEntry.f1);
            
            logger.info("更新白名单: {} = {}", whitelistEntry.f0, whitelistEntry.f1);
        }
    }
    
    /**
     * Redis 白名单数据源
     * 
     * 功能：
     * 1. 定期从 Redis 读取交易对白名单
     * 2. 发送到广播流
     * 
     * Redis 数据结构：
     * - Key: "crypto:whitelist"
     * - Type: Set
     * - Value: ["BTC-USDT", "ETH-USDT", "SOL-USDT", ...]
     * 
     * 性能优化：
     * - 使用 RedisConnectionManager 管理连接池
     * - 定期刷新（每 60 秒），减少 Redis 压力
     * - 异常处理，保证稳定性
     */
    public static class RedisWhitelistSource 
            extends RichMapFunction<Long, Tuple2<String, Boolean>> 
            implements org.apache.flink.streaming.api.functions.source.SourceFunction<Tuple2<String, Boolean>> {
        
        private final ConfigLoader config;
        private transient RedisConnectionManager redisManager;
        private volatile boolean isRunning = true;
        
        // 刷新间隔：60 秒
        private static final long REFRESH_INTERVAL_MS = 60000;
        
        // Redis Key
        private static final String WHITELIST_KEY = "crypto:whitelist";
        
        public RedisWhitelistSource(ConfigLoader config) {
            this.config = config;
        }
        
        @Override
        public void open(Configuration parameters) {
            // 初始化 Redis 连接管理器
            // 性能优化：使用连接池，避免频繁创建连接
            redisManager = new RedisConnectionManager(config);
            
            // 测试连接
            if (redisManager.testConnection()) {
                logger.info("✓ Redis 连接测试成功");
            } else {
                logger.warn("⚠ Redis 连接测试失败，将使用默认白名单");
            }
        }
        
        @Override
        public void run(SourceContext<Tuple2<String, Boolean>> ctx) throws Exception {
            while (isRunning) {
                try {
                    // 从 Redis 读取白名单
                    Set<String> whitelist = fetchWhitelistFromRedis();
                    
                    // 发送到广播流
                    for (String symbol : whitelist) {
                        ctx.collect(new Tuple2<>(symbol, true));
                    }
                    
                    logger.info("从 Redis 读取白名单成功，数量: {}", whitelist.size());
                    if (logger.isDebugEnabled()) {
                        logger.debug("白名单内容: {}", whitelist);
                    }
                    
                } catch (Exception e) {
                    logger.error("从 Redis 读取白名单失败: {}", e.getMessage(), e);
                }
                
                // 等待下一次刷新
                Thread.sleep(REFRESH_INTERVAL_MS);
            }
        }
        
        @Override
        public void cancel() {
            isRunning = false;
        }
        
        @Override
        public void close() {
            if (redisManager != null) {
                logger.info("关闭 Redis 连接管理器...");
                logger.info(redisManager.getPoolStatus());
                redisManager.close();
            }
        }
        
        /**
         * 从 Redis 读取白名单
         * 
         * Redis 命令：SMEMBERS crypto:whitelist
         */
        private Set<String> fetchWhitelistFromRedis() {
            try {
                // 使用 RedisConnectionManager 读取 Set
                Set<String> whitelist = redisManager.getSet(WHITELIST_KEY);
                
                // 如果 Redis 中没有数据，返回默认白名单
                if (whitelist == null || whitelist.isEmpty()) {
                    logger.warn("Redis 中没有白名单数据（Key: {}），使用默认白名单", WHITELIST_KEY);
                    whitelist = getDefaultWhitelist();
                }
                
                return whitelist;
            } catch (Exception e) {
                logger.error("从 Redis 读取白名单失败，使用默认白名单: {}", e.getMessage());
                return getDefaultWhitelist();
            }
        }
        
        /**
         * 获取默认白名单
         */
        private Set<String> getDefaultWhitelist() {
            Set<String> defaultWhitelist = new HashSet<>();
            defaultWhitelist.add("BTC-USDT");
            defaultWhitelist.add("ETH-USDT");
            defaultWhitelist.add("SOL-USDT");
            defaultWhitelist.add("BNB-USDT");
            defaultWhitelist.add("XRP-USDT");
            return defaultWhitelist;
        }
        
        @Override
        public Tuple2<String, Boolean> map(Long value) {
            return null;  // 不使用
        }
    }
    
    // ========== 数据模型 ==========
    
    /**
     * 现货价格
     */
    public static class SpotPrice {
        public String symbol;
        public BigDecimal price;
        public long timestamp;
    }
    
    /**
     * 期货价格
     */
    public static class FuturesPrice {
        public String symbol;
        public BigDecimal price;
        public long timestamp;
    }
    
    /**
     * 套利机会
     */
    public static class ArbitrageOpportunity {
        public String symbol;
        public BigDecimal spotPrice;
        public BigDecimal futuresPrice;
        public BigDecimal spread;  // 价差
        public BigDecimal spreadRate;  // 价差率（%）
        public String arbitrageDirection;  // 套利方向
        public BigDecimal profitEstimate;  // 预估利润
        public long timestamp;
    }
}
