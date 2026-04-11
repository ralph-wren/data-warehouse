package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.source.OKXRestApiSource;
import com.crypto.dw.flink.source.OKXWebSocketSourceFunction;
import com.crypto.dw.redis.RedisConnectionManager;
import com.crypto.dw.trading.OKXTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.serialization.SimpleStringSchema;
import org.apache.flink.connector.base.DeliveryGuarantee;
import org.apache.flink.connector.kafka.sink.KafkaRecordSerializationSchema;
import org.apache.flink.connector.kafka.sink.KafkaSink;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.datastream.SingleOutputStreamOperator;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.functions.ProcessFunction;
import org.apache.flink.util.Collector;
import org.apache.flink.util.OutputTag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;

import java.util.*;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Flink 数据采集作业 (增强版 - 支持从 Redis 读取订阅列表)
 * <p>
 * 从 OKX WebSocket 接收实时行情数据并发送到 Kafka
 * <p>
 * 功能:
 * - 使用 Flink Source Function 封装 WebSocket 客户端
 * - 同时订阅现货（SPOT）和合约（SWAP）
 * - 根据数据类型写入不同的 Kafka Topic
 * - 自动重连和错误处理
 * - 支持 Flink 的 Checkpoint 机制
 * - 统一的监控和管理
 * - 【新增】支持从 Redis 读取订阅列表 (由 FlinkPriceSpreadCalculatorJob 写入)
 * <p>
 * 数据流:
 * <pre>
 * Redis (crypto:top_spread_symbols) - 读取价差最大的币对列表
 *     │
 *     ↓
 * OKX WebSocket (订阅币对)
 *     │
 *     ├─→ 现货数据 → Kafka Topic: crypto-ticker-spot
 *     │
 *     └─→ 合约数据 → Kafka Topic: crypto-ticker-swap
 * </pre>
 * <p>
 * 配合使用:
 * 1. 先启动 FlinkPriceSpreadCalculatorJob (计算价差并写入 Redis)
 * 2. 再启动 FlinkTickerCollectorJob (从 Redis 读取币对并订阅)
 * 3. 如果 Redis 中没有数据,则使用配置文件或默认币对
 * <p>
 * 优势:
 * - 利用 Flink 的容错机制
 * - 统一管理所有数据流
 * - 更好的监控和管理
 * - 支持动态扩缩容
 * - 自动追踪价差最大的币对,无需手动配置
 * <p>
 * 使用方法:
 * <pre>
 * # 方式1: 从 Redis 读取订阅列表 (推荐)
 * # 先启动价差计算作业
 * bash run-flink-price-spread-calculator.sh
 * # 再启动数据采集作业
 * bash run-flink-collector.sh
 *
 * # 方式2: 手动指定交易对 (静态订阅模式)
 * bash run-flink-collector.sh BTC-USDT ETH-USDT SOL-USDT
 * </pre>
 * 
 * @author Kiro
 * @date 2026-04-11 (增强版)
 */
public class FlinkTickerCollectorJob {

    private static final Logger logger = LoggerFactory.getLogger(FlinkTickerCollectorJob.class);

    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink Data Collector Job");
        logger.info("==========================================");

        // 从程序参数中读取 APP_ENV（支持 StreamPark Remote 模式）
        // 格式：--env docker 或 --APP_ENV docker
        for (int i = 0; i < args.length - 1; i++) {
            if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
                String envFromArgs = args[i + 1];
                logger.info("Found APP_ENV in program arguments: " + envFromArgs);
                // 设置为 System Property，让 ConfigLoader 能读取到
                System.setProperty("APP_ENV", envFromArgs);
                break;
            }
        }

        // 加载配置
        ConfigLoader config = ConfigLoader.getInstance();
        logger.info("Configuration loaded successfully");

        // 打印配置信息（调试用）
        logger.info("=== 配置信息 ===");
        logger.info("Kafka Bootstrap Servers: " + config.getString("kafka.bootstrap-servers"));
        logger.info("Kafka Topic: " + config.getString("kafka.topic.crypto-ticker"));
        logger.info("OKX WebSocket URL: " + config.getString("okx.websocket.url"));
        logger.info("================");

        // 使用工厂类创建 Flink Stream Environment (减少重复代码)
        // 注意: 使用端口 8085 避免与其他作业冲突
        FlinkEnvironmentFactory envFactory = new FlinkEnvironmentFactory(config);
        StreamExecutionEnvironment env = envFactory.createStreamEnvironment("flink-data-collector-job", 8085);

        // ========================================
        // 步骤1: 启动时计算一次价差,获取价差最大的前10个币对
        // ========================================
        logger.info("==========================================");
        logger.info("计算价差,获取价差最大的币对...");
        logger.info("==========================================");
        
        List<String> symbols = getSymbolsWithSpreadCalculation(args, config);
        logger.info("订阅币对列表 ({}个): {}", symbols.size(), symbols);
        
        // ========================================
        // 步骤2: 启动定时任务,定期刷新缓存
        // ========================================
        startCacheRefreshTask(config);

        // 获取 Kafka 配置
        String kafkaBootstrapServers = config.getString("kafka.bootstrap-servers");
        String spotTopic = config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot");
        String swapTopic = config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap");

        logger.info("Kafka Configuration:");
        logger.info("  Bootstrap Servers: {}", kafkaBootstrapServers);
        logger.info("  Spot Topic: {}", spotTopic);
        logger.info("  Swap Topic: {}", swapTopic);

        // 创建数据流: OKX WebSocket Source
        DataStream<String> sourceStream = env.addSource(
                new OKXWebSocketSourceFunction(config, symbols)
        ).name("OKX WebSocket Source");

        // 使用 Side Output 分流：现货和合约
        // 定义 Side Output Tag
        final OutputTag<String> swapOutputTag = new OutputTag<String>("swap-output") {
        };

        // 处理数据流，根据 instId 判断是现货还是合约
        SingleOutputStreamOperator<String> spotStream = sourceStream
                .process(new ProcessFunction<String, String>() {

                    private static final long serialVersionUID = 1L;
                    private transient ObjectMapper objectMapper;

                    @Override
                    public void open(org.apache.flink.configuration.Configuration parameters) {
                        objectMapper = new ObjectMapper();
                    }

                    @Override
                    public void processElement(
                            String value,
                            Context ctx,
                            Collector<String> out) throws Exception {

                        try {
                            // 解析 JSON
                            JsonNode rootNode = objectMapper.readTree(value);

                            // 检查是否是订阅确认消息或错误消息
                            if (rootNode.has("event")) {
                                String event = rootNode.get("event").asText();
                                if ("subscribe".equals(event)) {
                                    logger.info("Subscription confirmed: {}", value);
                                    return;
                                } else if ("error".equals(event)) {
                                    logger.error("Subscription error: {}", value);
                                    return;
                                }
                            }

                            // 处理 Ticker 数据
                            if (rootNode.has("data")) {
                                JsonNode dataArray = rootNode.get("data");
                                if (dataArray.isArray() && dataArray.size() > 0) {
                                    for (JsonNode dataNode : dataArray) {
                                        if (dataNode.has("instId")) {
                                            String instId = dataNode.get("instId").asText();
                                            String jsonData = objectMapper.writeValueAsString(dataNode);

                                            // 判断是现货还是合约
                                            if (instId.endsWith("-SWAP")) {
                                                // 合约数据：发送到 Side Output
                                                ctx.output(swapOutputTag, jsonData);
                                                logger.debug("SWAP data: {}", instId);
                                            } else {
                                                // 现货数据：发送到主流
                                                out.collect(jsonData);
                                                logger.debug("SPOT data: {}", instId);
                                            }
                                        }
                                    }
                                }
                            }

                        } catch (Exception e) {
                            logger.error("Error processing message: {}", e.getMessage(), e);
                        }
                    }
                })
                .name("Split SPOT/SWAP");

        // 获取合约数据流
        DataStream<String> swapStream = spotStream.getSideOutput(swapOutputTag);

        // 创建 Kafka Sink（现货）
        logger.info("Creating Kafka Sink for SPOT...");
        KafkaSink<String> spotKafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaBootstrapServers)
                .setRecordSerializer(
                        KafkaRecordSerializationSchema.builder()
                                .setTopic(spotTopic)
                                .setValueSerializationSchema(new SimpleStringSchema())
                                .build()
                )
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        // 创建 Kafka Sink（合约）
        logger.info("Creating Kafka Sink for SWAP...");
        KafkaSink<String> swapKafkaSink = KafkaSink.<String>builder()
                .setBootstrapServers(kafkaBootstrapServers)
                .setRecordSerializer(
                        KafkaRecordSerializationSchema.builder()
                                .setTopic(swapTopic)
                                .setValueSerializationSchema(new SimpleStringSchema())
                                .build()
                )
                .setDeliveryGuarantee(DeliveryGuarantee.AT_LEAST_ONCE)
                .build();

        // 现货数据写入 Kafka
        spotStream.sinkTo(spotKafkaSink).name("Kafka Sink (SPOT)");

        // 合约数据写入 Kafka
        swapStream.sinkTo(swapKafkaSink).name("Kafka Sink (SWAP)");

        logger.info("==========================================");
        logger.info("Starting Flink Data Collector Job...");
        logger.info("Web UI: http://localhost:8085");
        logger.info("==========================================");

        // 执行作业
        env.execute("Flink Data Collector Job");
    }

    /**
     * 获取订阅的交易对列表 (支持价差计算)
     * <p>
     * 逻辑:
     * 1. 如果命令行提供了交易对,直接使用
     * 2. 否则,调用 OKX REST API 计算价差,取价差最大的前10个币对
     * 3. 如果 API 调用失败,使用配置文件中的交易对
     * 4. 如果配置文件也没有,使用默认交易对
     * <p>
     * 注意：为了让数据均匀分布到 Kafka 的多个分区，建议订阅多个交易对
     * Kafka 使用 key（交易对名称）的 hash 值来决定分区，不同的交易对会分布到不同分区
     *
     * @param args   命令行参数
     * @param config 配置加载器
     * @return 交易对列表
     */
    private static List<String> getSymbolsWithSpreadCalculation(String[] args, ConfigLoader config) {
        // 过滤掉 --env 和 --APP_ENV 参数
        List<String> filteredArgs = new ArrayList<>();
        for (int i = 0; i < args.length; i++) {
            if ("--env".equals(args[i]) || "--APP_ENV".equals(args[i])) {
                i++; // 跳过下一个参数（环境值）
                continue;
            }
            filteredArgs.add(args[i]);
        }

        // 如果命令行参数提供了交易对，使用命令行参数
        if (!filteredArgs.isEmpty()) {
            logger.info("使用命令行参数指定的交易对: {}", filteredArgs);
            return filteredArgs;
        }
        
        // 尝试通过 REST API 计算价差,获取价差最大的前10个币对
        try {
            logger.info("未指定交易对,开始计算价差...");
            List<String> topSpreadSymbols = calculateTopSpreadSymbols(config, 10);
            if (topSpreadSymbols != null && !topSpreadSymbols.isEmpty()) {
                logger.info("价差计算完成,获取到 {} 个币对: {}", topSpreadSymbols.size(), topSpreadSymbols);
                return topSpreadSymbols;
            }
        } catch (Exception e) {
            logger.warn("价差计算失败: {}, 将使用配置文件或默认交易对", e.getMessage());
        }

        // 尝试从配置文件读取（支持逗号分隔的字符串）
        String symbolsSpotConfig = config.getString("okx.symbols.spot", "");
        String symbolsSwapConfig = config.getString("okx.symbols.swap", "");
        String symbolsConfig = symbolsSpotConfig + "," + symbolsSwapConfig;

        logger.info("Reading okx.symbols from config: '{}'", symbolsConfig);

        if (!symbolsConfig.isEmpty()) {
            // 支持逗号分隔的多个交易对
            String[] symbolArray = symbolsConfig.split(",");
            List<String> symbols = new ArrayList<>();
            for (String symbol : symbolArray) {
                String trimmed = symbol.trim();
                if (!trimmed.isEmpty()) {
                    symbols.add(trimmed);
                }
            }
            if (!symbols.isEmpty()) {
                logger.info("Using symbols from config file: {}", symbols);
                return symbols;
            }
        }

        // 默认订阅 4 个主流交易对（对应 4 个 Kafka 分区）
        // 这样数据会均匀分布到不同分区
        List<String> defaultSymbols = Arrays.asList("BTC-USDT", "ETH-USDT", "SOL-USDT", "BNB-USDT");
        logger.info("Config not found or empty, using default symbols: {}", defaultSymbols);
        return defaultSymbols;
    }

    /**
     * 启动定时任务,定期刷新缓存
     * <p>
     * 缓存内容:
     * 1. 杠杆支持信息 (Redis Hash: okx:margin:support)
     * 2. 交易对信息 (Redis Hash: okx:instrument:cache)
     * 3. 借币利息率 (Redis Hash: okx:borrow:interest)
     * <p>
     * 缓存格式:
     * - okx:margin:support: {symbol: flags}
     *   - flags: "1" = 支持策略A (现货卖出+合约买入)
     *   - flags: "2" = 支持策略B (现货买入+合约卖出)
     *   - flags: "12" = 都支持
     * - okx:instrument:cache: {symbol: JSON}
     *   - JSON: {"minSz": "0.01", "ctVal": "0.01", ...}
     * - okx:borrow:interest: {symbol: interestRate}
     *   - interestRate: 小时利率,如 "0.0000040833333334"
     * 
     * @param config 配置加载器
     */
    private static void startCacheRefreshTask(ConfigLoader config) {
        // 读取配置
        int topSymbolsCount = config.getInt("arbitrage.monitor.top-symbols-count", 10);
        int refreshIntervalSeconds = config.getInt("arbitrage.cache.refresh-interval-seconds", 300);
        
        logger.info("==========================================");
        logger.info("启动缓存刷新定时任务");
        logger.info("监控币对数量: {}", topSymbolsCount);
        logger.info("刷新间隔: {} 秒", refreshIntervalSeconds);
        logger.info("==========================================");
        
        // 创建定时任务
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "cache-refresh-task");
            t.setDaemon(true); // 设置为守护线程,主线程退出时自动退出
            return t;
        });
        
        // 立即执行一次,然后定期执行
        scheduler.scheduleAtFixedRate(() -> {
            try {
                refreshCache(config, topSymbolsCount);
            } catch (Exception e) {
                logger.error("缓存刷新失败: {}", e.getMessage(), e);
            }
        }, 0, refreshIntervalSeconds, TimeUnit.SECONDS);
        
        logger.info("缓存刷新定时任务已启动");
    }
    
    /**
     * 刷新缓存
     * 
     * @param config 配置加载器
     * @param topN 监控的币对数量
     */
    private static void refreshCache(ConfigLoader config, int topN) {
        logger.info("开始刷新缓存...");
        long startTime = System.currentTimeMillis();
        
        try {
            // 1. 计算价差最大的币对
            List<String> topSymbols = calculateTopSpreadSymbols(config, topN);
            if (topSymbols == null || topSymbols.isEmpty()) {
                logger.warn("未获取到价差数据,跳过缓存刷新");
                return;
            }
            
            logger.info("获取到 {} 个价差最大的币对: {}", topSymbols.size(), topSymbols);
            
            // 2. 创建 OKXTradingService 实例
            OKXTradingService tradingService = new OKXTradingService(config);
            
            // 3. 创建 Redis 连接管理器
            RedisConnectionManager redisManager = new RedisConnectionManager(config);
            
            try (Jedis jedis = redisManager.getConnection()) {
                // 4. 遍历每个币对,查询并缓存信息
                for (String symbolWithSuffix : topSymbols) {
                    try {
                        // 移除 -USDT 后缀,得到币种名称
                        String symbol = symbolWithSuffix.replace("-USDT", "");
                        
                        // 4.1 查询杠杆支持信息
                        boolean supportsMargin = tradingService.checkMarginSupport(symbol);
                        
                        // 确定支持的策略标识
                        // 策略A: 现货卖出 + 合约买入 (需要杠杆)
                        // 策略B: 现货买入 + 合约卖出 (不需要杠杆)
                        String flags;
                        if (supportsMargin) {
                            flags = "12"; // 都支持
                        } else {
                            flags = "2";  // 只支持策略B
                        }
                        
                        // 存入 Redis Hash: okx:margin:support
                        jedis.hset("okx:margin:support", symbolWithSuffix, flags);
                        logger.debug("缓存杠杆支持信息: {} -> {}", symbolWithSuffix, flags);
                        
                        // 4.2 查询交易对信息
                        OKXTradingService.InstrumentInfo instrumentInfo = tradingService.getInstrumentInfo(symbol);
                        if (instrumentInfo != null) {
                            // 转换为 JSON 字符串
                            ObjectMapper mapper = new ObjectMapper();
                            String json = mapper.writeValueAsString(instrumentInfo);
                            
                            // 存入 Redis Hash: okx:instrument:cache
                            jedis.hset("okx:instrument:cache", symbol, json);
                            logger.debug("缓存交易对信息: {} -> minSz={}, ctVal={}", 
                                symbol, instrumentInfo.minSz, instrumentInfo.ctVal);
                        }
                        
                        // 4.3 查询借币利息率（仅当支持杠杆时）
                        if (supportsMargin) {
                            java.math.BigDecimal interestRate = tradingService.queryBorrowInterestRate(symbol);
                            if (interestRate != null) {
                                // 存入 Redis Hash: okx:borrow:interest
                                jedis.hset("okx:borrow:interest", symbol, interestRate.toPlainString());
                                logger.debug("缓存借币利息率: {} -> {} (小时利率)", symbol, interestRate);
                            } else {
                                logger.warn("未查询到 {} 的借币利息率", symbol);
                            }
                        }
                        
                    } catch (Exception e) {
                        logger.error("缓存币对 {} 信息失败: {}", symbolWithSuffix, e.getMessage());
                    }
                }
                
                // 5. 设置缓存过期时间 (2倍刷新间隔,避免缓存失效)
                int refreshIntervalSeconds = config.getInt("arbitrage.cache.refresh-interval-seconds", 300);
                int expireSeconds = refreshIntervalSeconds * 2;
                jedis.expire("okx:margin:support", expireSeconds);
                jedis.expire("okx:instrument:cache", expireSeconds);
                jedis.expire("okx:borrow:interest", expireSeconds);
                
                long elapsed = System.currentTimeMillis() - startTime;
                logger.info("缓存刷新完成,耗时 {} ms", elapsed);
                
            } catch (Exception e) {
                logger.error("Redis 操作失败: {}", e.getMessage(), e);
            }
            
        } catch (Exception e) {
            logger.error("缓存刷新失败: {}", e.getMessage(), e);
        }
    }
    
    /**
     * 计算价差最大的币对
     * 
     * @param config 配置加载器
     * @param topN 取前 N 个
     * @return 币对列表 (格式: BTC-USDT, ETH-USDT, ...)
     */
    private static List<String> calculateTopSpreadSymbols(ConfigLoader config, int topN) throws Exception {
        logger.info("开始调用 OKX REST API 获取价格数据...");
        
        // 创建临时的 REST API Source 实例
        OKXRestApiSource apiSource = new OKXRestApiSource(config, 0, topN);
        
        // 初始化 Source (调用 open 方法)
        org.apache.flink.configuration.Configuration flinkConfig = new org.apache.flink.configuration.Configuration();
        apiSource.open(flinkConfig);
        
        // 获取现货价格
        java.util.Map<String, java.math.BigDecimal> spotPrices = apiSource.fetchSpotPrices();
        logger.info("获取到 {} 个现货价格", spotPrices.size());
        
        // 获取合约价格
        java.util.Map<String, java.math.BigDecimal> swapPrices = apiSource.fetchSwapPrices();
        logger.info("获取到 {} 个合约价格", swapPrices.size());
        
        // 计算价差
        java.util.List<OKXRestApiSource.PriceSpreadInfo> spreadList = 
            apiSource.calculateSpreads(spotPrices, swapPrices);
        logger.info("计算出 {} 个币种的价差", spreadList.size());
        
        // 取前 N 个
        List<String> topSymbols = new ArrayList<>();
        int count = Math.min(topN, spreadList.size());
        for (int i = 0; i < count; i++) {
            OKXRestApiSource.PriceSpreadInfo info = spreadList.get(i);
            String symbol = info.symbol + "-USDT";
            topSymbols.add(symbol);
            
            logger.info("价差排名 {}: {} - 现货: {}, 合约: {}, 价差率: {}%", 
                i + 1, symbol, info.spotPrice, info.swapPrice, 
                info.spreadRate.multiply(new java.math.BigDecimal("100")));
        }
        
        return topSymbols;
    }
}
