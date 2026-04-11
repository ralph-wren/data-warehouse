package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.KafkaSourceFactory;
import com.crypto.dw.model.TickerData;
import com.crypto.dw.model.TradeRecord;
import com.crypto.dw.redis.RedisConnectionManager;
import com.crypto.dw.trading.OKXOrderWebSocketSource;
import com.crypto.dw.trading.OKXTradingService;
import com.crypto.dw.trading.OpportunityTracker;
import com.crypto.dw.trading.PositionState;
import com.fasterxml.jackson.databind.JsonNode;
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
import org.apache.flink.streaming.api.functions.co.KeyedCoProcessFunction;
import org.apache.flink.api.common.state.ValueState;
import org.apache.flink.api.common.state.ValueStateDescriptor;
import org.apache.flink.api.common.state.MapState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.streaming.api.functions.co.ProcessJoinFunction;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
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
 * 3. 广播流：从 Redis 读取交易对黑名单
 * 4. 维度关联：使用广播状态过滤数据
 * 5. 性能优化：异步 IO、状态管理、背压控制
 * 
 * 数据流图：
 * <pre>
 * Kafka(现货 crypto-ticker-spot) ──┐
 *                                   ├─→ Interval Join ──→ 计算套利空间 ──┐
 * Kafka(合约 crypto-ticker-swap) ──┘                                   │
 *                                                                      ├─→ 广播 Join ──→ 过滤 ──→ Doris
 * Redis(黑名单) ──→ 广播流 ────────────────────────────────────────────┘
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
        logger.info("双流 Join + 广播流 + Redis 黑名单过滤");
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
            spot.symbol = ticker.getSymbol();  // 使用币种名称,如 COMP (用于 Join)
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
        
        // ========== 步骤 4: 从 Redis 读取交易对黑名单（广播流）==========
        // 说明：使用广播流将黑名单数据分发到所有并行实例
        // 优势：避免每条数据都查询 Redis，大幅提升性能
        logger.info("创建广播流（从 Redis 读取黑名单）...");
        
        // 定义广播状态描述符
        MapStateDescriptor<String, Boolean> blacklistStateDescriptor = new MapStateDescriptor<>(
            "blacklist-state",
            BasicTypeInfo.STRING_TYPE_INFO,
            BasicTypeInfo.BOOLEAN_TYPE_INFO
        );
        
        // 创建广播流：定期从 Redis 读取黑名单
        DataStream<Tuple2<String, Boolean>> blacklistSource = env
            .addSource(new RedisBlacklistSource(config))
            .name("Redis Blacklist Source");
        
        BroadcastStream<Tuple2<String, Boolean>> blacklistBroadcast = blacklistSource
            .broadcast(blacklistStateDescriptor);
        
        logger.info("✓ 广播流创建成功");
        
        // ========== 步骤 5: 使用广播状态过滤套利机会 ==========
        // 说明：过滤掉黑名单中的交易对
        logger.info("配置广播 Join（过滤黑名单）...");
        
        DataStream<ArbitrageOpportunity> filteredStream = arbitrageStream
            .connect(blacklistBroadcast)
            .process(new BlacklistFilter(blacklistStateDescriptor))
            .name("Blacklist Filter");
        
        logger.info("✓ 广播 Join 配置成功");
        
        // ========== 步骤 6: 创建订单流 ==========
        logger.info("创建订单 WebSocket 流...");
        
        DataStream<OrderUpdate> orderStream = env
            .addSource(new OKXOrderWebSocketSource(config))
            .map(new OrderUpdateParser())
            .filter(order -> order != null)
            .name("Parse Order Update");
        
        logger.info("✓ 订单流创建成功");
        
        // ========== 步骤 7: Connect 套利机会流和订单流(三流 Join) ==========
        logger.info("配置三流 Join（套利机会 + 订单流）...");
        
        DataStream<TradeRecord> tradeStream = filteredStream
            .keyBy(opp -> opp.symbol)
            .connect(orderStream.keyBy(order -> order.symbol))
            .process(new TradingDecisionProcessor(config))
            .name("Trading Decision");
        
        logger.info("✓ 三流 Join 配置成功");
        
        // ========== 步骤 8: 创建 Doris Sink Factory ==========
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        
        // ========== 步骤 9: 输出交易明细到 Doris ==========
        DataStream<String> tradeJsonStream = tradeStream
            .map(record -> {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                json.put("symbol", record.symbol);
                json.put("action", record.action);
                json.put("direction", record.direction);
                json.put("amount", record.amount.toString());
                json.put("spot_price", record.spotPrice.toString());
                json.put("swap_price", record.swapPrice.toString());
                json.put("spread_rate", record.spreadRate != null ? record.spreadRate.toString() : "0");
                json.put("profit", record.profit != null ? record.profit.toString() : "0");
                json.put("close_reason", record.closeReason != null ? record.closeReason : "");
                json.put("hold_time_ms", record.holdTimeMs);
                json.put("timestamp", record.timestamp);
                return json.toString();
            })
            .name("Trade To JSON");
        
        // 写入 Doris 交易明细表
        DorisSink<String> tradeSink = dorisSinkFactory.createDorisSink(
            "crypto_dw",
            "dwd_arbitrage_trades",
            "ads-arbitrage-trades"
        );
        
        tradeJsonStream.sinkTo(tradeSink).name("Doris Trade Sink");
        
        logger.info("✓ Doris Trade Sink 创建成功");
        logger.info("  Database: crypto_dw");
        logger.info("  Table: dwd_arbitrage_trades");
        
        // ========== 步骤 10: 转换为 JSON 并写入 Doris(套利机会表) ==========
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
        
        // 写入 Doris (套利机会表)
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
                    // 合约价格 > 现货价格：做空合约，做多现货（策略 A）
                    opportunity.arbitrageDirection = "做多现货/做空合约";
                } else {
                    // 现货价格 > 合约价格：做多合约，做空现货（策略 B）
                    opportunity.arbitrageDirection = "做空现货/做多合约";
                }
                
                // 估算利润（假设交易 1 个单位，扣除 0.1% 手续费）
                BigDecimal fee = spot.price.multiply(new BigDecimal("0.001"));
                opportunity.profitEstimate = spread.abs().subtract(fee.multiply(new BigDecimal("2")));
                
                opportunity.timestamp = System.currentTimeMillis();
                
                // 输出套利机会(不输出日志,由 TradingDecisionProcessor 统一管理)
                out.collect(opportunity);
            }
        }
    }
    
    /**
     * 黑名单过滤器 - 广播处理函数
     * 
     * 功能：
     * 1. 接收广播的黑名单数据
     * 2. 更新广播状态
     * 3. 使用广播状态过滤数据流
     * 
     * 性能优化：
     * - 使用广播状态，避免每条数据查询 Redis
     * - 所有并行实例共享黑名单数据
     * - 定期更新黑名单，保持数据新鲜度
     */
    public static class BlacklistFilter 
            extends BroadcastProcessFunction<ArbitrageOpportunity, Tuple2<String, Boolean>, ArbitrageOpportunity> {
        
        private final MapStateDescriptor<String, Boolean> blacklistStateDescriptor;
        
        public BlacklistFilter(MapStateDescriptor<String, Boolean> blacklistStateDescriptor) {
            this.blacklistStateDescriptor = blacklistStateDescriptor;
        }
        
        @Override
        public void processElement(
                ArbitrageOpportunity opportunity,
                ReadOnlyContext ctx,
                Collector<ArbitrageOpportunity> out) throws Exception {
            
            // 读取广播状态
            ReadOnlyBroadcastState<String, Boolean> blacklistState = 
                ctx.getBroadcastState(blacklistStateDescriptor);
            
            // 检查是否在黑名单中
            Boolean isBlacklisted = blacklistState.get(opportunity.symbol);
            
            if (isBlacklisted != null && isBlacklisted) {
                // 在黑名单中，过滤掉
                logger.debug("过滤黑名单交易对: {}", opportunity.symbol);
            } else {
                // 不在黑名单中，输出数据
                out.collect(opportunity);
            }
        }
        
        @Override
        public void processBroadcastElement(
                Tuple2<String, Boolean> blacklistEntry,
                Context ctx,
                Collector<ArbitrageOpportunity> out) throws Exception {
            
            // 更新广播状态
            BroadcastState<String, Boolean> blacklistState = 
                ctx.getBroadcastState(blacklistStateDescriptor);
            
            blacklistState.put(blacklistEntry.f0, blacklistEntry.f1);
            
            logger.info("更新黑名单: {} = {}", blacklistEntry.f0, blacklistEntry.f1);
        }
    }
    
    /**
     * Redis 黑名单数据源
     * 
     * 功能：
     * 1. 定期从 Redis 读取交易对黑名单
     * 2. 发送到广播流
     * 
     * Redis 数据结构：
     * - Key: "crypto:blacklist"
     * - Type: Set
     * - Value: ["SHIB-USDT", "DOGE-USDT", ...]（需要过滤的交易对）
     * 
     * 性能优化：
     * - 使用 RedisConnectionManager 管理连接池
     * - 定期刷新（每 60 秒），减少 Redis 压力
     * - 异常处理，保证稳定性
     */
    public static class RedisBlacklistSource 
            extends RichMapFunction<Long, Tuple2<String, Boolean>> 
            implements org.apache.flink.streaming.api.functions.source.SourceFunction<Tuple2<String, Boolean>> {
        
        private final ConfigLoader config;
        private transient RedisConnectionManager redisManager;
        private volatile boolean isRunning = true;
        
        // 刷新间隔：60 秒
        private static final long REFRESH_INTERVAL_MS = 60000;
        
        // Redis Key
        private static final String BLACKLIST_KEY = "crypto:blacklist";
        
        public RedisBlacklistSource(ConfigLoader config) {
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
                logger.warn("⚠ Redis 连接测试失败，黑名单将为空");
            }
        }
        
        @Override
        public void run(SourceContext<Tuple2<String, Boolean>> ctx) throws Exception {
            while (isRunning) {
                try {
                    // 从 Redis 读取黑名单
                    Set<String> blacklist = fetchBlacklistFromRedis();
                    
                    // 发送到广播流
                    for (String symbol : blacklist) {
                        ctx.collect(new Tuple2<>(symbol, true));
                    }
                    
                    logger.debug("从 Redis 读取黑名单成功，数量: {}", blacklist.size());
                    if (logger.isDebugEnabled()) {
                        logger.debug("黑名单内容: {}", blacklist);
                    }
                    
                } catch (Exception e) {
                    logger.error("从 Redis 读取黑名单失败: {}", e.getMessage(), e);
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
         * 从 Redis 读取黑名单
         * 
         * Redis 命令：SMEMBERS crypto:blacklist
         */
        private Set<String> fetchBlacklistFromRedis() {
            try {
                // 使用 RedisConnectionManager 读取 Set
                Set<String> blacklist = redisManager.getSet(BLACKLIST_KEY);
                
                // 如果 Redis 中没有数据，返回空黑名单（不过滤任何交易对）
                if (blacklist == null || blacklist.isEmpty()) {
                    logger.debug("Redis 中没有黑名单数据（Key: {}），不过滤任何交易对", BLACKLIST_KEY);
                    return new HashSet<>();
                }
                
                return blacklist;
            } catch (Exception e) {
                logger.error("从 Redis 读取黑名单失败，不过滤任何交易对: {}", e.getMessage());
                return new HashSet<>();
            }
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
    
    /**
     * 订单更新
     */
    public static class OrderUpdate {
        public String orderId;          // 订单ID
        public String symbol;           // 交易对
        public String instType;         // 产品类型(SPOT/SWAP)
        public String side;             // 买卖方向(buy/sell)
        public String state;            // 订单状态(filled/canceled)
        public BigDecimal fillPrice;    // 成交价格
        public BigDecimal fillSize;     // 成交数量
        public BigDecimal fee;          // 手续费金额
        public String feeCcy;           // 手续费币种
        public long timestamp;          // 时间戳
    }
    
    /**
     * 待确认订单
     */
    public static class PendingOrder implements java.io.Serializable {
        private static final long serialVersionUID = 1L;
        
        public String symbol;
        public String action;           // OPEN/CLOSE
        public String spotOrderId;
        public String swapOrderId;
        public boolean spotFilled;
        public boolean swapFilled;
        public BigDecimal spotFillPrice;
        public BigDecimal swapFillPrice;
        public BigDecimal spotFillSize;     // 现货成交数量
        public BigDecimal swapFillSize;     // 合约成交数量
        public BigDecimal spotFee;          // 现货手续费
        public String spotFeeCcy;           // 现货手续费币种
        public BigDecimal swapFee;          // 合约手续费
        public String swapFeeCcy;           // 合约手续费币种
        public long createTime;
    }
    
    /**
     * 订单更新解析器
     */
    public static class OrderUpdateParser extends RichMapFunction<String, OrderUpdate> {
        
        private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
        
        @Override
        public OrderUpdate map(String json) throws Exception {
            JsonNode rootNode = OBJECT_MAPPER.readTree(json);
            
            // 检查是否是订单数据
            if (!rootNode.has("data")) {
                return null;
            }
            
            JsonNode dataArray = rootNode.get("data");
            if (!dataArray.isArray() || dataArray.size() == 0) {
                return null;
            }
            
            JsonNode orderNode = dataArray.get(0);
            
            OrderUpdate order = new OrderUpdate();
            order.orderId = orderNode.get("ordId").asText();
            order.symbol = extractSymbol(orderNode.get("instId").asText());
            order.instType = orderNode.get("instType").asText();
            order.side = orderNode.get("side").asText();
            order.state = orderNode.get("state").asText();
            
            // 成交价格和数量
            if (orderNode.has("fillPx")) {
                order.fillPrice = new BigDecimal(orderNode.get("fillPx").asText());
            }
            if (orderNode.has("fillSz")) {
                order.fillSize = new BigDecimal(orderNode.get("fillSz").asText());
            }
            
            // 手续费信息
            if (orderNode.has("fee")) {
                order.fee = new BigDecimal(orderNode.get("fee").asText());
            }
            if (orderNode.has("feeCcy")) {
                order.feeCcy = orderNode.get("feeCcy").asText();
            }
            
            order.timestamp = System.currentTimeMillis();
            
            return order;
        }
        
        private String extractSymbol(String instId) {
            // BTC-USDT-SWAP → BTC-USDT
            // BTC-USDT → BTC-USDT
            if (instId.endsWith("-SWAP")) {
                return instId.substring(0, instId.length() - 5);
            }
            return instId;
        }
    }
    
    /**
     * 交易决策处理器
     * 
     * 功能:
     * 1. 接收套利机会,判断是否开仓/平仓
     * 2. 接收订单更新,确认开仓/平仓
     * 3. 维护持仓状态
     * 4. 输出交易明细
     */
    public static class TradingDecisionProcessor 
            extends KeyedCoProcessFunction<String, ArbitrageOpportunity, OrderUpdate, TradeRecord> {
        
        private final ConfigLoader config;
        private final boolean tradingEnabled;
        private final BigDecimal tradeAmountUsdt;  // 交易金额(USDT)
        private final int leverage;  // 杠杆倍数
        private final BigDecimal openThreshold;
        private final BigDecimal closeThreshold;
        private final long maxHoldTimeMs;
        private final BigDecimal maxLossPerTrade;
        private final int maxPositions;  // 新增: 最大持仓数量
        
        private transient OKXTradingService tradingService;
        private transient ValueState<PositionState> positionState;
        private transient MapState<String, PendingOrder> pendingOrders;
        private transient ValueState<OpportunityTracker> opportunityTracker;  // 新增:跟踪套利机会持续时间
        
        // 新增: 失败策略黑名单,为每个币种维护两种策略的独立黑名单
        // Key: symbol, Value: Set<strategy> (LONG_SPOT_SHORT_SWAP 或 SHORT_SPOT_LONG_SWAP)
        private transient Map<String, Set<String>> failedStrategies;
        
        // 新增: CSV 日志记录器 - 改为两个文件
        private transient BufferedWriter orderDetailWriter;  // 订单明细日志
        private transient BufferedWriter positionSummaryWriter;  // 持仓汇总日志
        private transient String currentLogDate;  // 当前日志文件的日期
        private transient int currentLogHour;  // 当前日志文件的小时
        
        // 新增: Redis 连接管理器,用于全局持仓计数
        private transient RedisConnectionManager redisManager;
        private static final String POSITION_COUNT_KEY = "okx:arbitrage:position:count";  // Redis Key,存储当前持仓数量
        
        public TradingDecisionProcessor(ConfigLoader config) {
            this.config = config;
            this.tradingEnabled = config.getBoolean("arbitrage.trading.enabled", false);
            this.tradeAmountUsdt = new BigDecimal(config.getString("arbitrage.trading.trade-amount-usdt", "6"));
            this.leverage = config.getInt("arbitrage.trading.leverage", 1);  // 读取杠杆倍数配置,默认1倍
            this.openThreshold = new BigDecimal(config.getString("arbitrage.trading.open-threshold", "0.005"));
            this.closeThreshold = new BigDecimal(config.getString("arbitrage.trading.close-threshold", "0.002"));
            this.maxPositions = config.getInt("arbitrage.trading.max-positions", 5);  // 读取最大持仓数量配置
            
            int maxHoldMinutes = config.getInt("arbitrage.trading.max-hold-time-minutes", 60);
            this.maxHoldTimeMs = maxHoldMinutes * 60 * 1000L;
            
            this.maxLossPerTrade = new BigDecimal(config.getString("arbitrage.trading.max-loss-per-trade", "10"));
        }
        
        @Override
        public void open(Configuration parameters) {
            // 初始化交易服务
            tradingService = new OKXTradingService(config);
            
            // 初始化持仓状态
            ValueStateDescriptor<PositionState> positionDescriptor = new ValueStateDescriptor<>(
                "position-state-v3",
                PositionState.class
            );
            positionState = getRuntimeContext().getState(positionDescriptor);
            
            // 初始化待确认订单
            MapStateDescriptor<String, PendingOrder> pendingDescriptor = new MapStateDescriptor<>(
                "pending-orders",
                String.class,
                PendingOrder.class
            );
            pendingOrders = getRuntimeContext().getMapState(pendingDescriptor);
            
            // 初始化套利机会跟踪器
            ValueStateDescriptor<OpportunityTracker> trackerDescriptor = new ValueStateDescriptor<>(
                "opportunity-tracker",
                OpportunityTracker.class
            );
            opportunityTracker = getRuntimeContext().getState(trackerDescriptor);
            
            // 初始化失败策略黑名单
            failedStrategies = new HashMap<>();
            
            // 初始化 CSV 日志记录器（两个文件）
            try {
                initCsvWriters();
            } catch (IOException e) {
                logger.error("初始化 CSV 日志记录器失败: {}", e.getMessage(), e);
            }
            
            // 初始化 Redis 连接管理器(用于全局持仓计数)
            try {
                this.redisManager = new RedisConnectionManager(config);
                logger.info("✓ Redis 连接管理器初始化成功,用于全局持仓计数");
                
                // 初始化持仓计数为 0
                redisManager.set(POSITION_COUNT_KEY, "0");
            } catch (Exception e) {
                logger.error("✗ Redis 连接管理器初始化失败: {}", e.getMessage(), e);
                this.redisManager = null;
            }
            
            logger.info("TradingDecisionProcessor 初始化完成");
            logger.info("  交易开关: {}", tradingEnabled ? "开启" : "关闭");
            logger.info("  交易金额: {} USDT (会根据价格自动计算币的数量)", tradeAmountUsdt);
            logger.info("  最大持仓: {} 个", maxPositions);
            logger.info("  开仓阈值: {}%", openThreshold.multiply(new BigDecimal("100")));
            logger.info("  平仓阈值: {}%", closeThreshold.multiply(new BigDecimal("100")));
        }
        
        /**
         * 初始化 CSV 日志记录器
         * 每小时2个文件:
         * 1. logs/trading/order_detail_YYYYMMDDHH.csv - 订单明细
         * 2. logs/trading/position_summary_YYYYMMDDHH.csv - 持仓汇总
         */
        private void initCsvWriters() throws IOException {
            LocalDateTime now = LocalDateTime.now();
            String today = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
            int hour = now.getHour();
            
            // 如果日期或小时变了,关闭旧文件,创建新文件
            if (orderDetailWriter != null && (!today.equals(currentLogDate) || hour != currentLogHour)) {
                orderDetailWriter.close();
                orderDetailWriter = null;
            }
            if (positionSummaryWriter != null && (!today.equals(currentLogDate) || hour != currentLogHour)) {
                positionSummaryWriter.close();
                positionSummaryWriter = null;
            }
            
            if (orderDetailWriter == null || positionSummaryWriter == null) {
                currentLogDate = today;
                currentLogHour = hour;
                
                // 确保 logs/trading 目录存在
                File tradingDir = new File("logs/trading");
                if (!tradingDir.exists()) {
                    tradingDir.mkdirs();
                }
                
                String hourStr = String.format("%02d", hour);
                
                // 1. 订单明细日志
                File orderDetailFile = new File(tradingDir, "order_detail_" + today + hourStr + ".csv");
                boolean isNewOrderFile = !orderDetailFile.exists();
                orderDetailWriter = new BufferedWriter(new FileWriter(orderDetailFile, true));
                
                if (isNewOrderFile) {
                    // 订单明细表头
                    orderDetailWriter.write("时间,币种,订单ID,订单类型,方向,数量,价格,成交价格,成交数量,手续费,手续费币种,状态,错误信息\n");
                    orderDetailWriter.flush();
                }
                
                // 2. 持仓汇总日志
                File positionSummaryFile = new File(tradingDir, "position_summary_" + today + hourStr + ".csv");
                boolean isNewSummaryFile = !positionSummaryFile.exists();
                positionSummaryWriter = new BufferedWriter(new FileWriter(positionSummaryFile, true));
                
                if (isNewSummaryFile) {
                    // 持仓汇总表头
                    positionSummaryWriter.write("时间,币种,操作,方向,现货订单ID,合约订单ID,数量,开仓现货价,开仓合约价,平仓现货价,平仓合约价,价差率,盈亏,持仓时长(秒),状态\n");
                    positionSummaryWriter.flush();
                }
                
                logger.info("✓ CSV 日志文件已创建:");
                logger.info("  订单明细: {}", orderDetailFile.getAbsolutePath());
                logger.info("  持仓汇总: {}", positionSummaryFile.getAbsolutePath());
            }
        }
        
        /**
         * 记录订单明细到 CSV 文件
         * 每条订单一行，包含所有详细信息
         */
        private void logOrderDetail(
                String symbol,
                String orderId,
                String orderType,  // SPOT_BUY, SPOT_SELL, SWAP_LONG, SWAP_SHORT, SWAP_CLOSE_LONG, SWAP_CLOSE_SHORT
                String direction,  // BUY, SELL
                BigDecimal amount,
                BigDecimal price,
                BigDecimal fillPrice,
                BigDecimal fillAmount,
                BigDecimal fee,
                String feeCurrency,
                String status,
                String errorMsg
        ) {
            try {
                // 检查是否需要切换日志文件
                initCsvWriters();
                
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                
                orderDetailWriter.write(String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                    timestamp,
                    symbol,
                    orderId != null ? orderId : "",
                    orderType != null ? orderType : "",
                    direction != null ? direction : "",
                    amount != null ? amount.toPlainString() : "",
                    price != null ? price.toPlainString() : "",
                    fillPrice != null ? fillPrice.toPlainString() : "",
                    fillAmount != null ? fillAmount.toPlainString() : "",
                    fee != null ? fee.toPlainString() : "",
                    feeCurrency != null ? feeCurrency : "",
                    status,
                    errorMsg != null ? errorMsg.replace(",", ";") : ""
                ));
                orderDetailWriter.flush();
                
            } catch (IOException e) {
                logger.error("写入订单明细日志失败: {}", e.getMessage(), e);
            }
        }
        
        /**
         * 记录持仓汇总到 CSV 文件
         * 开仓一条，平仓一条
         */
        private void logPositionSummary(
                String symbol,
                String action,  // OPEN, CLOSE
                String direction,  // LONG_SPOT_SHORT_SWAP, SHORT_SPOT_LONG_SWAP
                String spotOrderId,
                String swapOrderId,
                BigDecimal amount,
                BigDecimal openSpotPrice,
                BigDecimal openSwapPrice,
                BigDecimal closeSpotPrice,
                BigDecimal closeSwapPrice,
                BigDecimal spreadRate,
                BigDecimal profit,
                Long holdTimeSeconds,
                String status
        ) {
            try {
                // 检查是否需要切换日志文件
                initCsvWriters();
                
                String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
                
                positionSummaryWriter.write(String.format("%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s,%s\n",
                    timestamp,
                    symbol,
                    action,
                    direction != null ? direction : "",
                    spotOrderId != null ? spotOrderId : "",
                    swapOrderId != null ? swapOrderId : "",
                    amount != null ? amount.toPlainString() : "",
                    openSpotPrice != null ? openSpotPrice.toPlainString() : "",
                    openSwapPrice != null ? openSwapPrice.toPlainString() : "",
                    closeSpotPrice != null ? closeSpotPrice.toPlainString() : "",
                    closeSwapPrice != null ? closeSwapPrice.toPlainString() : "",
                    spreadRate != null ? spreadRate.multiply(new BigDecimal("100")).toPlainString() + "%" : "",
                    profit != null ? profit.toPlainString() : "",
                    holdTimeSeconds != null ? holdTimeSeconds.toString() : "",
                    status
                ));
                positionSummaryWriter.flush();
                
            } catch (IOException e) {
                logger.error("写入持仓汇总日志失败: {}", e.getMessage(), e);
            }
        }
        
        /**
         * 旧的日志方法 - 保留兼容性，内部调用新方法
         */
        private void logTradeToCsv(
                String symbol,
                String action,
                String direction,
                String spotOrderId,
                String swapOrderId,
                BigDecimal amount,
                BigDecimal spotPrice,
                BigDecimal swapPrice,
                BigDecimal spreadRate,
                String status,
                String errorMsg
        ) {
            // 记录到持仓汇总日志
            logPositionSummary(
                symbol,
                action,
                direction,
                spotOrderId,
                swapOrderId,
                amount,
                "OPEN".equals(action) ? spotPrice : null,
                "OPEN".equals(action) ? swapPrice : null,
                "CLOSE".equals(action) ? spotPrice : null,
                "CLOSE".equals(action) ? swapPrice : null,
                spreadRate,
                null,  // profit 在这里不可用
                null,  // holdTimeSeconds 在这里不可用
                status
            );
            
            // 如果有错误信息，也记录到订单明细
            if (errorMsg != null && !errorMsg.isEmpty()) {
                if (spotOrderId != null) {
                    logOrderDetail(symbol, spotOrderId, "SPOT", null, amount, spotPrice, null, null, null, null, status, errorMsg);
                }
                if (swapOrderId != null) {
                    logOrderDetail(symbol, swapOrderId, "SWAP", null, amount, swapPrice, null, null, null, null, status, errorMsg);
                }
            }
        }
        
        @Override
        public void close() throws Exception {
            // 关闭 CSV 日志记录器
            if (orderDetailWriter != null) {
                try {
                    orderDetailWriter.close();
                    logger.info("✓ 订单明细日志记录器已关闭");
                } catch (IOException e) {
                    logger.error("关闭订单明细日志记录器失败: {}", e.getMessage(), e);
                }
            }
            if (positionSummaryWriter != null) {
                try {
                    positionSummaryWriter.close();
                    logger.info("✓ 持仓汇总日志记录器已关闭");
                } catch (IOException e) {
                    logger.error("关闭持仓汇总日志记录器失败: {}", e.getMessage(), e);
                }
            }
            super.close();
        }
        
        @Override
        public void processElement1(
                ArbitrageOpportunity opportunity,
                Context ctx,
                Collector<TradeRecord> out) throws Exception {
            
            if (!tradingEnabled || !tradingService.isConfigured()) {
                return;
            }
            
            PositionState position = positionState.value();
            OpportunityTracker tracker = opportunityTracker.value();
            long now = System.currentTimeMillis();
            
            // 决策 1: 开仓逻辑
            if (position == null || !position.isOpen()) {
                if (shouldOpen(opportunity)) {
                    // 检查是否是新的套利机会
                    if (tracker == null || !tracker.isActive()) {
                        // 首次发现套利机会,开始跟踪
                        tracker = new OpportunityTracker();
                        tracker.setFirstSeenTime(now);
                        tracker.setLastSeenTime(now);
                        tracker.setActive(true);
                        tracker.setSpreadRate(opportunity.spreadRate);
                        tracker.setLastLogTime(now);  // 记录日志时间
                        opportunityTracker.update(tracker);
                        
                        logger.info("🔍 发现新套利机会: {} | 价差率: {}% | 开始观察...", 
                            opportunity.symbol, opportunity.spreadRate);
                    } else {
                        // 套利机会持续存在,更新最后看到时间
                        tracker.setLastSeenTime(now);
                        tracker.setSpreadRate(opportunity.spreadRate);
                        
                        // 检查是否持续超过5秒
                        long duration = tracker.getLastSeenTime() - tracker.getFirstSeenTime();
                        if (duration >= 5000) {
                            // 持续超过5秒,执行开仓
                            double durationSec = duration / 1000.0;
                            logger.debug("⏰{} 套利机会持续 {} 秒,满足开仓条件", opportunity.symbol,String.format("%.1f", durationSec));
                            openPosition(opportunity, out);
                            
                            // 清除跟踪器
                            opportunityTracker.clear();
                        } else {
                            // 每2秒输出一次观察日志
                            if (now - tracker.getLastLogTime() >= 2000) {
                                double durationSec = duration / 1000.0;
                                logger.info("⏳ 套利机会持续中: {} | 价差率: {}% | 已持续 {} 秒 / 需要 5 秒",
                                    opportunity.symbol, 
                                    opportunity.spreadRate,
                                    String.format("%.1f", durationSec));
                                tracker.setLastLogTime(now);
                            }
                        }
                        
                        opportunityTracker.update(tracker);
                    }
                } else {
                    // 套利机会消失,清除跟踪器
                    if (tracker != null && tracker.isActive()) {
                        long duration = now - tracker.getFirstSeenTime();
                        double durationSec = duration / 1000.0;
                        logger.info("❌ 套利机会消失: {} | 持续时间 {} 秒(不足5秒)", 
                            opportunity.symbol, String.format("%.1f", durationSec));
                        opportunityTracker.clear();
                    }
                }
            }
            // 决策 2: 持仓状态下的逻辑
            else {
                // 清除跟踪器(已经持仓,不需要再跟踪)
                if (tracker != null) {
                    opportunityTracker.clear();
                }
                
                // 更新预估利润
                updateUnrealizedProfit(opportunity, position, out);
                
                // 检查是否需要平仓
                if (shouldClose(opportunity, position)) {
                    closePosition(opportunity, position, out);
                }
            }
        }
        
        @Override
        public void processElement2(
                OrderUpdate order,
                Context ctx,
                Collector<TradeRecord> out) throws Exception {
            
            // 处理订单更新
            if ("filled".equals(order.state)) {
                handleOrderFilled(order, out);
            }
        }
        
        private boolean shouldOpen(ArbitrageOpportunity opp) {
            BigDecimal spreadRate = opp.spreadRate.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP);
            return spreadRate.abs().compareTo(openThreshold) > 0;
        }
        
        private boolean shouldClose(ArbitrageOpportunity opp, PositionState pos) {
            BigDecimal spreadRate = opp.spreadRate.divide(new BigDecimal("100"), 6, java.math.RoundingMode.HALF_UP);
            
            // 条件 1: 价差回归
            boolean spreadConverged = spreadRate.abs().compareTo(closeThreshold) <= 0;
            
            // 条件 2: 超时
            long holdTime = System.currentTimeMillis() - pos.getOpenTime();
            boolean timeout = holdTime > maxHoldTimeMs;
            
            // 条件 3: 止损
            BigDecimal entrySpread = pos.getEntrySwapPrice().subtract(pos.getEntrySpotPrice());
            BigDecimal currentSpread = opp.futuresPrice.subtract(opp.spotPrice);
            BigDecimal spreadDiff = currentSpread.subtract(entrySpread);
            
            if ("SHORT_SPOT_LONG_SWAP".equals(pos.getDirection())) {
                spreadDiff = spreadDiff.negate();
            }
            
            BigDecimal fee = pos.getAmount().multiply(new BigDecimal("0.002"));
            BigDecimal unrealizedProfit = spreadDiff.subtract(fee);
            boolean stopLoss = unrealizedProfit.compareTo(maxLossPerTrade.negate()) < 0;
            
            return spreadConverged || timeout || stopLoss;
        }
        
        /**
         * 更新未实现利润
         * 持仓状态下,实时计算并输出预估利润
         */
        private void updateUnrealizedProfit(
                ArbitrageOpportunity opp,
                PositionState pos,
                Collector<TradeRecord> out) throws Exception {
            
            // 计算当前价差
            BigDecimal entrySpread = pos.getEntrySwapPrice().subtract(pos.getEntrySpotPrice());
            BigDecimal currentSpread = opp.futuresPrice.subtract(opp.spotPrice);
            BigDecimal spreadDiff = currentSpread.subtract(entrySpread);
            
            // 根据方向调整价差差异
            if ("SHORT_SPOT_LONG_SWAP".equals(pos.getDirection())) {
                spreadDiff = spreadDiff.negate();
            }
            
            // 计算手续费
            BigDecimal fee = pos.getAmount().multiply(new BigDecimal("0.002"));
            
            // 计算未实现利润
            BigDecimal unrealizedProfit = spreadDiff.subtract(fee);
            
            // 计算利润率
            BigDecimal profitRate = unrealizedProfit.divide(pos.getAmount(), 6, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            
            // 更新持仓状态
            pos.setUnrealizedProfit(unrealizedProfit);
            pos.setLastUpdateTime(System.currentTimeMillis());
            positionState.update(pos);
            
            // 定期输出日志(每10秒输出一次)
            long now = System.currentTimeMillis();
            if (now - pos.getLastLogTime() > 10000) {
                logger.info("📊 持仓更新: {} | 方向: {} | 未实现利润: {} USDT ({} %) | 持仓时间: {} 秒",
                    pos.getSymbol(),
                    pos.getDirection(),
                    unrealizedProfit.setScale(4, java.math.RoundingMode.HALF_UP),
                    profitRate.setScale(2, java.math.RoundingMode.HALF_UP),
                    (now - pos.getOpenTime()) / 1000);
                pos.setLastLogTime(now);
                positionState.update(pos);
            }
        }
        
        private void openPosition(ArbitrageOpportunity opp, Collector<TradeRecord> out) 
                throws Exception {
            
            // 确定策略方向
            String strategyDirection = opp.arbitrageDirection.contains("做多现货") ? 
                "LONG_SPOT_SHORT_SWAP" : "SHORT_SPOT_LONG_SWAP";
            
            // 检查该币种的该策略是否在失败黑名单中
            Set<String> failedStrategiesForSymbol = failedStrategies.get(opp.symbol);
            if (failedStrategiesForSymbol != null && failedStrategiesForSymbol.contains(strategyDirection)) {
                // 该币种的该策略已失败，跳过
                return;
            }
            
            // 检查全局持仓数量是否已达上限（原子性操作：先增加再检查）
            // 这样可以避免竞态条件，确保不会超过最大持仓数量
            boolean positionReserved = false;
            if (redisManager != null) {
                try {
                    // 先原子性增加计数（预留持仓名额）
                    long newCount = redisManager.incr(POSITION_COUNT_KEY);
                    
                    // 检查是否超过上限
                    if (newCount > maxPositions) {
                        // 超过上限，回退计数
                        redisManager.decr(POSITION_COUNT_KEY);
                        logger.warn("⏸ 已达到最大持仓数量 {}/{}，跳过开仓: {}", 
                            newCount - 1, maxPositions, opp.symbol);
                        return;
                    }
                    
                    // 成功预留持仓名额
                    positionReserved = true;
                    logger.info("📊 预留持仓名额: {} -> {}/{}", opp.symbol, newCount, maxPositions);
                    
                } catch (Exception e) {
                    logger.error("检查持仓数量失败: {}", e.getMessage(), e);
                    // 如果 Redis 失败,为了安全起见,不开仓
                    return;
                }
            }
            
            logger.info("🎯 开仓: {} | 价差率: {}%", opp.symbol, opp.spreadRate);
            
            // 使用 OKXTradingService 计算合约张数
            // 会自动查询交易对的 ctVal 和 lotSz,并按规则取整
            BigDecimal contractSize = tradingService.calculateContractSize(
                opp.symbol, 
                tradeAmountUsdt, 
                opp.spotPrice
            );
            
            // 检查计算结果
            if (contractSize == null) {
                logger.error("✗ 无法计算合约张数,跳过交易");
                
                // 回退预留的持仓名额
                if (positionReserved) {
                    try {
                        long currentCount = redisManager.decr(POSITION_COUNT_KEY);
                        logger.info("↩️ 计算失败，回退持仓名额: {} | 当前持仓: {}/{}", 
                            opp.symbol, currentCount, maxPositions);
                    } catch (Exception e) {
                        logger.error("回退持仓计数失败: {}", e.getMessage(), e);
                    }
                }
                
                // 记录失败日志
                logTradeToCsv(
                    opp.symbol,
                    "OPEN",
                    null,
                    null,
                    null,
                    null,
                    opp.spotPrice,
                    opp.futuresPrice,
                    opp.spreadRate,
                    "FAILED",
                    "无法计算合约张数"
                );
                
                // 加入失败黑名单（该币种的该策略）
                failedStrategies.computeIfAbsent(opp.symbol, k -> new HashSet<>())
                    .add(strategyDirection);
                logger.warn("🚫 {} 的策略 {} 已加入失败黑名单", opp.symbol, strategyDirection);
                
                return;
            }
            
            if (contractSize.compareTo(BigDecimal.ZERO) == 0) {
                logger.warn("⚠ 跳过交易: 计算的张数为 0,建议增加 trade-amount-usdt 配置(当前: {} USDT)", tradeAmountUsdt);
                
                // 回退预留的持仓名额
                if (positionReserved) {
                    try {
                        long currentCount = redisManager.decr(POSITION_COUNT_KEY);
                        logger.info("↩️ 张数为0，回退持仓名额: {} | 当前持仓: {}/{}", 
                            opp.symbol, currentCount, maxPositions);
                    } catch (Exception e) {
                        logger.error("回退持仓计数失败: {}", e.getMessage(), e);
                    }
                }
                
                // 记录失败日志
                logTradeToCsv(
                    opp.symbol,
                    "OPEN",
                    null,
                    null,
                    null,
                    contractSize,
                    opp.spotPrice,
                    opp.futuresPrice,
                    opp.spreadRate,
                    "FAILED",
                    "计算的张数为 0"
                );
                
                // 加入失败黑名单（该币种的该策略）
                failedStrategies.computeIfAbsent(opp.symbol, k -> new HashSet<>())
                    .add(strategyDirection);
                logger.warn("🚫 {} 的策略 {} 已加入失败黑名单", opp.symbol, strategyDirection);
                
                return;
            }
            
            String spotOrderId = null;
            String swapOrderId = null;
            String direction = null;
            boolean orderSuccess = false;
            boolean strategySkipped = false;  // 标记策略是否因不适用而跳过
            String errorMsg = null;
            
            try {
                // 检查币对是否支持杠杆交易
                boolean marginSupported = tradingService.checkMarginSupport(opp.symbol);
                
                if (opp.arbitrageDirection.contains("做多现货")) {
                    // 策略 A: 做多现货 + 做空合约（现货模式，不需要杠杆）
                    direction = "LONG_SPOT_SHORT_SWAP";
                    
                    // 先下现货单（传入价格用于检查订单金额）
                    spotOrderId = tradingService.buySpot(opp.symbol, contractSize, opp.spotPrice);
                    
                    // 检查现货单是否成功
                    if (spotOrderId == null) {
                        // 获取详细错误信息
                        errorMsg = tradingService.getLastErrorMessage();
                        if (errorMsg == null || errorMsg.isEmpty()) {
                            errorMsg = "现货下单失败,跳过合约下单";
                        }
                        logger.error("❌ {}: {}", opp.symbol, errorMsg);
                        
                        // 记录现货下单失败的订单明细
                        logOrderDetail(
                            opp.symbol,
                            null,  // 订单ID为空
                            "SPOT",
                            "BUY",
                            contractSize,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "FAILED",
                            errorMsg
                        );
                    } else {
                        // 现货单成功,再下合约单（使用配置的杠杆倍数）
                        swapOrderId = tradingService.shortSwap(opp.symbol, contractSize, leverage);
                    }
                } else {
                    // 策略 B: 做空现货 + 做多合约（需要杠杆模式）
                    
                    // 检查是否支持杠杆交易
                    if (!marginSupported) {
                        errorMsg = "币对不支持杠杆交易,无法做空现货";
                        logger.warn("⚠ {}: {}", opp.symbol, errorMsg);
                        
                        // 标记为策略跳过（不是真正的下单失败）
                        strategySkipped = true;
                        
                        // 记录失败日志
                        logOrderDetail(
                            opp.symbol,
                            null,
                            "SPOT",
                            "SELL",
                            contractSize,
                            null,
                            null,
                            null,
                            null,
                            null,
                            "SKIPPED",  // 状态改为 SKIPPED
                            errorMsg
                        );
                        
                        // 不执行下单，直接跳过
                        spotOrderId = null;
                        swapOrderId = null;
                    } else {
                        // 支持杠杆，可以做空现货
                        direction = "SHORT_SPOT_LONG_SWAP";
                        
                        // 先下现货单（会自动查询借币利息，并检查订单金额，同时设置杠杆倍数）
                        spotOrderId = tradingService.sellSpot(opp.symbol, contractSize, opp.spotPrice, leverage);
                        
                        // 检查现货单是否成功
                        if (spotOrderId == null) {
                            // 获取详细错误信息
                            errorMsg = tradingService.getLastErrorMessage();
                            if (errorMsg == null || errorMsg.isEmpty()) {
                                errorMsg = "现货下单失败,跳过合约下单";
                            }
                            logger.error("❌ {}: {}", opp.symbol, errorMsg);
                            
                            // 记录现货下单失败的订单明细
                            logOrderDetail(
                                opp.symbol,
                                null,  // 订单ID为空
                                "SPOT",
                                "SELL",
                                contractSize,
                                null,
                                null,
                                null,
                                null,
                                null,
                                "FAILED",
                                errorMsg
                            );
                        } else {
                            // 现货单成功,再下合约单（使用配置的杠杆倍数）
                            swapOrderId = tradingService.longSwap(opp.symbol, contractSize, leverage);
                        }
                    }
                }
                
                if (spotOrderId != null && swapOrderId != null) {
                    orderSuccess = true;
                    
                    // 记录现货订单明细（下单时）
                    logOrderDetail(
                        opp.symbol,
                        spotOrderId,
                        "SPOT",
                        direction.contains("LONG_SPOT") ? "BUY" : "SELL",
                        contractSize,
                        null,  // 市价单没有委托价格
                        null,  // 成交价格（待确认）
                        null,  // 成交数量（待确认）
                        null,  // 手续费（待确认）
                        null,  // 手续费币种（待确认）
                        "SUBMITTED",
                        null
                    );
                    
                    // 记录合约订单明细（下单时）
                    logOrderDetail(
                        opp.symbol,
                        swapOrderId,
                        "SWAP",
                        direction.contains("SHORT_SWAP") ? "SHORT" : "LONG",
                        contractSize,
                        null,  // 市价单没有委托价格
                        null,  // 成交价格（待确认）
                        null,  // 成交数量（待确认）
                        null,  // 手续费（待确认）
                        null,  // 手续费币种（待确认）
                        "SUBMITTED",
                        null
                    );
                    
                    // 保存待确认订单
                    PendingOrder pending = new PendingOrder();
                    pending.symbol = opp.symbol;
                    pending.action = "OPEN";
                    pending.spotOrderId = spotOrderId;
                    pending.swapOrderId = swapOrderId;
                    pending.createTime = System.currentTimeMillis();
                    
                    pendingOrders.put(spotOrderId, pending);
                    pendingOrders.put(swapOrderId, pending);
                    
                    // 立即创建临时持仓状态,防止重复开仓
                    PositionState tempPosition = new PositionState();
                    tempPosition.setSymbol(opp.symbol);
                    tempPosition.setOpen(true);
                    tempPosition.setDirection(direction);
                    tempPosition.setAmount(contractSize);
                    tempPosition.setEntrySpotPrice(opp.spotPrice);
                    tempPosition.setEntrySwapPrice(opp.futuresPrice);
                    tempPosition.setOpenTime(System.currentTimeMillis());
                    tempPosition.setSpotOrderId(spotOrderId);
                    tempPosition.setSwapOrderId(swapOrderId);
                    tempPosition.setLastLogTime(System.currentTimeMillis());
                    
                    // 如果是做空现货,保存借币利息率
                    if (direction.equals("SHORT_SPOT_LONG_SWAP")) {
                        BigDecimal borrowRate = tradingService.getLastBorrowInterestRate();
                        if (borrowRate != null) {
                            tempPosition.setBorrowInterestRate(borrowRate);
                            logger.info("💰 保存借币利息率: {} (小时利率)", borrowRate);
                        }
                    }
                    
                    positionState.update(tempPosition);
                    
                    // 订单成功，持仓名额已在开头预留，这里只记录日志
                    long currentCount = redisManager.getCounter(POSITION_COUNT_KEY);
                    logger.info("✅ 开仓成功: {} | 当前持仓: {}/{}", opp.symbol, currentCount, maxPositions);
                    
                    logger.info("📝 订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
                    
                    // 记录成功日志（兼容旧格式）
                    logTradeToCsv(
                        opp.symbol,
                        "OPEN",
                        direction,
                        spotOrderId,
                        swapOrderId,
                        contractSize,
                        opp.spotPrice,
                        opp.futuresPrice,
                        opp.spreadRate,
                        "SUCCESS",
                        null
                    );
                } else {
                    errorMsg = "订单提交失败: spotOrderId=" + spotOrderId + ", swapOrderId=" + swapOrderId;
                    logger.warn("⚠ {}", errorMsg);
                }
            } catch (Exception e) {
                errorMsg = e.getMessage();
                logger.error("❌ 开仓失败: {}", errorMsg, e);
            }
            
            // 如果下单失败，回退预留的持仓名额
            if (!orderSuccess && positionReserved) {
                try {
                    long currentCount = redisManager.decr(POSITION_COUNT_KEY);
                    logger.info("↩️ 开仓失败，回退持仓名额: {} | 当前持仓: {}/{}", 
                        opp.symbol, currentCount, maxPositions);
                } catch (Exception e) {
                    logger.error("回退持仓计数失败: {}", e.getMessage(), e);
                }
            }
            
            // 如果下单失败,记录日志
            // 注意：只有真正的下单失败才加入黑名单，策略跳过不加入黑名单
            if (!orderSuccess) {
                logTradeToCsv(
                    opp.symbol,
                    "OPEN",
                    direction,
                    spotOrderId,
                    swapOrderId,
                    contractSize,
                    opp.spotPrice,
                    opp.futuresPrice,
                    opp.spreadRate,
                    strategySkipped ? "SKIPPED" : "FAILED",  // 区分跳过和失败
                    errorMsg
                );
                
                // 只有真正的下单失败才加入黑名单（该币种的该策略）
                // 策略跳过（如不支持杠杆）不加入黑名单，因为该币种仍可执行其他策略
                if (!strategySkipped) {
                    failedStrategies.computeIfAbsent(opp.symbol, k -> new HashSet<>())
                        .add(strategyDirection);
                    logger.warn("🚫 {} 的策略 {} 已加入失败黑名单", opp.symbol, strategyDirection);
                } else {
                    logger.info("ℹ️ {} 策略B跳过（不支持杠杆），但仍可执行策略A", opp.symbol);
                }
            }
        }
        
        private void closePosition(
                ArbitrageOpportunity opp, 
                PositionState pos, 
                Collector<TradeRecord> out) throws Exception {
            
            logger.info("🔄 平仓: {} | 价差率: {}%", opp.symbol, opp.spreadRate);
            
            String spotOrderId = null;
            String swapOrderId = null;
            boolean orderSuccess = false;
            String errorMsg = null;
            
            try {
                // 策略 A 平仓: 卖出现货 + 平空合约
                // 只使用这一种策略
                // 注意: 平仓时的 sellSpot 不需要杠杆，因为是卖出已持有的币
                // 但为了保持接口一致，传入 leverage 参数（实际不会用到）
                spotOrderId = tradingService.sellSpot(pos.getSymbol(), pos.getAmount(), opp.spotPrice, leverage);
                swapOrderId = tradingService.closeShortSwap(pos.getSymbol(), pos.getAmount());
                
                if (spotOrderId != null && swapOrderId != null) {
                    orderSuccess = true;
                    
                    // 记录现货订单明细（平仓时）
                    logOrderDetail(
                        pos.getSymbol(),
                        spotOrderId,
                        "SPOT",
                        "LONG_SPOT_SHORT_SWAP".equals(pos.getDirection()) ? "SELL" : "BUY",
                        pos.getAmount(),
                        null,  // 市价单没有委托价格
                        null,  // 成交价格（待确认）
                        null,  // 成交数量（待确认）
                        null,  // 手续费（待确认）
                        null,  // 手续费币种（待确认）
                        "SUBMITTED",
                        null
                    );
                    
                    // 记录合约订单明细（平仓时）
                    logOrderDetail(
                        pos.getSymbol(),
                        swapOrderId,
                        "SWAP",
                        "LONG_SPOT_SHORT_SWAP".equals(pos.getDirection()) ? "CLOSE_SHORT" : "CLOSE_LONG",
                        pos.getAmount(),
                        null,  // 市价单没有委托价格
                        null,  // 成交价格（待确认）
                        null,  // 成交数量（待确认）
                        null,  // 手续费（待确认）
                        null,  // 手续费币种（待确认）
                        "SUBMITTED",
                        null
                    );
                    
                    // 保存待确认订单
                    PendingOrder pending = new PendingOrder();
                    pending.symbol = pos.getSymbol();
                    pending.action = "CLOSE";
                    pending.spotOrderId = spotOrderId;
                    pending.swapOrderId = swapOrderId;
                    pending.createTime = System.currentTimeMillis();
                    
                    pendingOrders.put(spotOrderId, pending);
                    pendingOrders.put(swapOrderId, pending);
                    
                    // 注意: 不在这里减少持仓计数,而是在 confirmClose 中确认成交后再减少
                    // 这样可以避免订单还没成交就开新仓的问题
                    
                    logger.info("📝 平仓订单已提交: spotOrderId={}, swapOrderId={}", spotOrderId, swapOrderId);
                    
                    // 记录成功日志（兼容旧格式）
                    logTradeToCsv(
                        pos.getSymbol(),
                        "CLOSE",
                        pos.getDirection(),
                        spotOrderId,
                        swapOrderId,
                        pos.getAmount(),
                        opp.spotPrice,
                        opp.futuresPrice,
                        opp.spreadRate,
                        "SUCCESS",
                        null
                    );
                } else {
                    errorMsg = "平仓订单提交失败: spotOrderId=" + spotOrderId + ", swapOrderId=" + swapOrderId;
                    logger.warn("⚠ {}", errorMsg);
                }
            } catch (Exception e) {
                errorMsg = e.getMessage();
                logger.error("❌ 平仓失败: {}", errorMsg, e);
            }
            
            // 如果平仓失败,记录日志
            if (!orderSuccess) {
                logTradeToCsv(
                    pos.getSymbol(),
                    "CLOSE",
                    pos.getDirection(),
                    spotOrderId,
                    swapOrderId,
                    pos.getAmount(),
                    opp.spotPrice,
                    opp.futuresPrice,
                    opp.spreadRate,
                    "FAILED",
                    errorMsg
                );
            }
        }
        
        private void handleOrderFilled(OrderUpdate order, Collector<TradeRecord> out) 
                throws Exception {
            
            PendingOrder pending = pendingOrders.get(order.orderId);
            if (pending == null) {
                return; // 不是我们的订单
            }
            
            // 更新订单状态
            if (order.orderId.equals(pending.spotOrderId)) {
                pending.spotFilled = true;
                pending.spotFillPrice = order.fillPrice;
                pending.spotFillSize = order.fillSize;
                pending.spotFee = order.fee;
                pending.spotFeeCcy = order.feeCcy;
            } else if (order.orderId.equals(pending.swapOrderId)) {
                pending.swapFilled = true;
                pending.swapFillPrice = order.fillPrice;
                pending.swapFillSize = order.fillSize;
                pending.swapFee = order.fee;
                pending.swapFeeCcy = order.feeCcy;
            }
            
            // 检查是否都成交
            if (pending.spotFilled && pending.swapFilled) {
                if ("OPEN".equals(pending.action)) {
                    confirmOpen(pending, out);
                } else {
                    confirmClose(pending, out);
                }
                
                // 清理待确认订单
                pendingOrders.remove(pending.spotOrderId);
                pendingOrders.remove(pending.swapOrderId);
            }
        }
        
        private void confirmOpen(PendingOrder pending, Collector<TradeRecord> out) 
                throws Exception {
            
            logger.info("✅ 开仓确认: {}", pending.symbol);
            
            // 记录现货订单明细
            logOrderDetail(
                pending.symbol,
                pending.spotOrderId,
                "SPOT",
                "BUY",
                pending.spotFillSize,
                null,  // 市价单没有委托价格
                pending.spotFillPrice,
                pending.spotFillSize,
                pending.spotFee,
                pending.spotFeeCcy,
                "FILLED",
                null
            );
            
            // 记录合约订单明细
            logOrderDetail(
                pending.symbol,
                pending.swapOrderId,
                "SWAP",
                "SHORT",
                pending.swapFillSize,
                null,  // 市价单没有委托价格
                pending.swapFillPrice,
                pending.swapFillSize,
                pending.swapFee,
                pending.swapFeeCcy,
                "FILLED",
                null
            );
            
            // 记录持仓汇总（开仓）
            logPositionSummary(
                pending.symbol,
                "OPEN",
                "LONG_SPOT_SHORT_SWAP",
                pending.spotOrderId,
                pending.swapOrderId,
                pending.spotFillSize,
                pending.spotFillPrice,
                pending.swapFillPrice,
                null,  // 平仓价格
                null,  // 平仓价格
                null,  // 价差率（开仓时不计算）
                null,  // 盈亏
                null,  // 持仓时长
                "SUCCESS"
            );
            
            // 计算开仓手续费（现货 + 合约）
            BigDecimal openFeeUsdt = BigDecimal.ZERO;
            if (pending.spotFee != null) {
                // 现货手续费需要转换为USDT
                BigDecimal spotFeeUsdt = pending.spotFee.abs().multiply(pending.spotFillPrice);
                openFeeUsdt = openFeeUsdt.add(spotFeeUsdt);
                logger.info("💸 开仓现货手续费: {} {} (约 {} USDT)", pending.spotFee.abs(), pending.spotFeeCcy, spotFeeUsdt);
            }
            if (pending.swapFee != null) {
                // 合约手续费通常是USDT
                BigDecimal swapFeeUsdt = pending.swapFee.abs();
                openFeeUsdt = openFeeUsdt.add(swapFeeUsdt);
                logger.info("💸 开仓合约手续费: {} {}", pending.swapFee.abs(), pending.swapFeeCcy);
            }
            logger.info("💸 开仓总手续费: {} USDT", openFeeUsdt);
            
            // 更新持仓状态
            PositionState position = new PositionState();
            position.setSymbol(pending.symbol);
            position.setOpen(true);
            position.setDirection("LONG_SPOT_SHORT_SWAP");
            
            // 使用实际成交价格重新计算交易数量
            // 注意: 这里使用现货成交价格计算,确保金额准确
            BigDecimal actualTradeSize = tradeAmountUsdt.divide(pending.spotFillPrice, 8, java.math.RoundingMode.DOWN);
            position.setAmount(actualTradeSize);
            
            position.setEntrySpotPrice(pending.spotFillPrice);
            position.setEntrySwapPrice(pending.swapFillPrice);
            position.setOpenTime(System.currentTimeMillis());
            position.setOpenFeeUsdt(openFeeUsdt);  // 保存开仓手续费
            
            positionState.update(position);
            
            // 输出交易明细
            TradeRecord record = new TradeRecord();
            record.symbol = pending.symbol;
            record.action = "OPEN";
            record.direction = "LONG_SPOT_SHORT_SWAP";
            record.amount = actualTradeSize;
            record.spotPrice = pending.spotFillPrice;
            record.swapPrice = pending.swapFillPrice;
            record.timestamp = System.currentTimeMillis();
            
            out.collect(record);
        }
        
        private void confirmClose(PendingOrder pending, Collector<TradeRecord> out) 
                throws Exception {
            
            logger.info("✅ 平仓确认: {}", pending.symbol);
            
            PositionState position = positionState.value();
            
            // 记录现货订单明细（平仓时卖出现货）
            logOrderDetail(
                pending.symbol,
                pending.spotOrderId,
                "SPOT",
                "SELL",
                pending.spotFillSize,
                null,  // 市价单没有委托价格
                pending.spotFillPrice,
                pending.spotFillSize,
                pending.spotFee,
                pending.spotFeeCcy,
                "FILLED",
                null
            );
            
            // 记录合约订单明细（平仓时平空合约）
            logOrderDetail(
                pending.symbol,
                pending.swapOrderId,
                "SWAP",
                "CLOSE_SHORT",
                pending.swapFillSize,
                null,  // 市价单没有委托价格
                pending.swapFillPrice,
                pending.swapFillSize,
                pending.swapFee,
                pending.swapFeeCcy,
                "FILLED",
                null
            );
            
            // 计算盈亏
            BigDecimal entrySpread = position.getEntrySwapPrice().subtract(position.getEntrySpotPrice());
            BigDecimal exitSpread = pending.swapFillPrice.subtract(pending.spotFillPrice);
            BigDecimal spreadChange = exitSpread.subtract(entrySpread);
            BigDecimal profit = spreadChange.multiply(position.getAmount());
            
            // 计算持仓时长（秒和小时）
            long holdTimeSeconds = (System.currentTimeMillis() - position.getOpenTime()) / 1000;
            int holdTimeHours = (int) Math.ceil(holdTimeSeconds / 3600.0);  // 向上取整到小时
            
            // 扣除开仓手续费
            if (position.getOpenFeeUsdt() != null && position.getOpenFeeUsdt().compareTo(BigDecimal.ZERO) > 0) {
                logger.info("💸 开仓手续费: {} USDT", position.getOpenFeeUsdt());
                profit = profit.subtract(position.getOpenFeeUsdt());
            }
            
            // 扣除平仓手续费（现货 + 合约）
            // 注意: OKX返回的手续费已经是负数,表示扣除的金额
            BigDecimal closeFeeUsdt = BigDecimal.ZERO;
            if (pending.spotFee != null) {
                // 现货手续费需要转换为USDT（如果手续费币种是交易币种）
                BigDecimal spotFeeUsdt = pending.spotFee.abs().multiply(pending.spotFillPrice);
                closeFeeUsdt = closeFeeUsdt.add(spotFeeUsdt);
                logger.info("💸 平仓现货手续费: {} {} (约 {} USDT)", pending.spotFee.abs(), pending.spotFeeCcy, spotFeeUsdt);
            }
            if (pending.swapFee != null) {
                // 合约手续费通常是USDT
                BigDecimal swapFeeUsdt = pending.swapFee.abs();
                closeFeeUsdt = closeFeeUsdt.add(swapFeeUsdt);
                logger.info("💸 平仓合约手续费: {} {}", pending.swapFee.abs(), pending.swapFeeCcy);
            }
            
            logger.info("💸 平仓手续费: {} USDT", closeFeeUsdt);
            profit = profit.subtract(closeFeeUsdt);
            
            BigDecimal totalFeeUsdt = (position.getOpenFeeUsdt() != null ? position.getOpenFeeUsdt() : BigDecimal.ZERO).add(closeFeeUsdt);
            logger.info("💸 总手续费(开仓+平仓): {} USDT", totalFeeUsdt);
            logger.info("📊 扣除手续费后收益: {} USDT", profit);
            
            // 如果是做空现货,需要扣除借币利息成本
            BigDecimal borrowInterestCost = BigDecimal.ZERO;
            if (position.getDirection() != null && position.getDirection().equals("SHORT_SPOT_LONG_SWAP")) {
                BigDecimal borrowRate = position.getBorrowInterestRate();
                if (borrowRate != null && borrowRate.compareTo(BigDecimal.ZERO) > 0) {
                    // 计算借币利息: 利息 = 借币数量 * 小时利率 * 持仓小时数
                    borrowInterestCost = position.getAmount()
                        .multiply(borrowRate)
                        .multiply(new BigDecimal(holdTimeHours))
                        .setScale(8, java.math.RoundingMode.HALF_UP);
                    
                    // 将利息转换为 USDT 价值（利息是币的数量,需要乘以平仓价格）
                    BigDecimal borrowInterestCostUsdt = borrowInterestCost.multiply(pending.spotFillPrice);
                    
                    logger.info("💰 借币利息成本: {} {} (约 {} USDT) | 利率: {} | 持仓: {} 小时", 
                        borrowInterestCost, pending.symbol, borrowInterestCostUsdt, borrowRate, holdTimeHours);
                    
                    // 从盈亏中扣除利息成本（USDT）
                    profit = profit.subtract(borrowInterestCostUsdt);
                    logger.info("📊 扣除利息后净收益: {} USDT", profit);
                }
            }
            
            // 计算价差率
            BigDecimal spreadRate = exitSpread.divide(pending.spotFillPrice, 6, java.math.RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            
            // 记录持仓汇总（平仓）
            logPositionSummary(
                pending.symbol,
                "CLOSE",
                position.getDirection(),
                pending.spotOrderId,
                pending.swapOrderId,
                position.getAmount(),
                position.getEntrySpotPrice(),
                position.getEntrySwapPrice(),
                pending.spotFillPrice,
                pending.swapFillPrice,
                spreadRate,
                profit,
                holdTimeSeconds,
                "SUCCESS"
            );
            
            // 更新持仓状态
            position.setOpen(false);
            positionState.update(position);
            
            // 平仓成功后，减少全局持仓计数
            if (redisManager != null) {
                try {
                    long currentCount = redisManager.decr(POSITION_COUNT_KEY);
                    logger.info("📉 持仓数量减少: {} -> {}/{}", pending.symbol, currentCount, maxPositions);
                } catch (Exception e) {
                    logger.error("减少持仓计数失败: {}", e.getMessage(), e);
                }
            }
            
            // 输出交易明细
            TradeRecord record = new TradeRecord();
            record.symbol = pending.symbol;
            record.action = "CLOSE";
            record.direction = position.getDirection();
            record.amount = position.getAmount();
            record.spotPrice = pending.spotFillPrice;
            record.swapPrice = pending.swapFillPrice;
            record.profit = profit;
            record.holdTimeMs = System.currentTimeMillis() - position.getOpenTime();
            record.timestamp = System.currentTimeMillis();
            
            out.collect(record);
        }
    }
}
