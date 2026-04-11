package com.crypto.dw.flink;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.factory.DorisSinkFactory;
import com.crypto.dw.flink.factory.FlinkEnvironmentFactory;
import com.crypto.dw.flink.factory.KafkaSourceFactory;
import com.crypto.dw.flink.model.ArbitrageOpportunity;
import com.crypto.dw.flink.model.FuturesPrice;
import com.crypto.dw.flink.model.OrderUpdate;
import com.crypto.dw.flink.model.SpotPrice;
import com.crypto.dw.flink.processor.ArbitrageCalculator;
import com.crypto.dw.flink.processor.OrderUpdateParser;
import com.crypto.dw.processor.TradingDecisionProcessor;
import com.crypto.dw.model.TickerData;
import com.crypto.dw.model.TradeRecord;
import com.crypto.dw.trading.OKXOrderWebSocketSource;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;

/**
 * Flink ADS 跨市场套利机会计算作业 - 重构版
 * 
 * 业务场景：
 * 计算加密货币现货和合约（永续合约）之间的价差套利机会
 * 
 * 技术特性：
 * 1. 双流 Join：现货价格流 + 合约价格流
 * 2. Interval Join：时间窗口内的流关联
 * 3. 订单流：WebSocket 订单更新
 * 4. 性能优化：异步 CSV 写入、内存缓存、移除黑名单过滤
 * 
 * 数据流图：
 * <pre>
 * Kafka(现货 crypto-ticker-spot) ──┐
 *                                   ├─→ Interval Join ──→ 计算套利空间 ──┐
 * Kafka(合约 crypto-ticker-swap) ──┘                                   │
 *                                                                      ├─→ 交易决策 ──→ Doris
 * WebSocket(订单更新) ────────────────────────────────────────────────┘
 * </pre>
 * 
 * 重构优化：
 * - 移除黑名单过滤（简化数据流）
 * - 使用异步 CSV 写入（提升性能）
 * - 使用内存缓存杠杆支持信息（减少 Redis 查询）
 * - 拆分内部类到独立文件（提升可维护性）
 * 
 * @author Kiro AI Assistant
 * @date 2026-04-11
 */
public class FlinkADSArbitrageJob {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkADSArbitrageJob.class);
    
    // 静态 ObjectMapper 实例，线程安全
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ADS Arbitrage Job (重构版)");
        logger.info("双流 Join + 订单流 + 异步优化");
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
        KafkaSourceFactory kafkaSourceFactory = new KafkaSourceFactory(config);
        
        // ========== 步骤 1: 创建现货价格流 ==========
        logger.info("创建现货价格流...");
        KafkaSource<String> spotKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
            "ads-arbitrage",
            config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot")
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
        logger.info("创建合约价格流...");
        KafkaSource<String> swapKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
            "ads-arbitrage",
            config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap")
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
            String instId = ticker.getSymbol();
            if (instId.endsWith("-SWAP")) {
                futures.symbol = instId.substring(0, instId.length() - 5);
            } else {
                futures.symbol = instId;
            }
            futures.price = ticker.getLastPrice();
            futures.timestamp = ticker.getTimestamp();
            return futures;
        })
        .filter(futures -> futures.price != null && futures.price.compareTo(BigDecimal.ZERO) > 0)
        .name("Parse Swap Price");
        
        logger.info("✓ 合约价格流创建成功");
        
        // ========== 步骤 3: Interval Join 关联两个流 ==========
        logger.info("配置 Interval Join...");
        
        DataStream<ArbitrageOpportunity> arbitrageStream = spotStream
            .keyBy(spot -> spot.symbol)
            .intervalJoin(futuresStream.keyBy(futures -> futures.symbol))
            .between(Time.seconds(-10), Time.seconds(10))
            .process(new ArbitrageCalculator())
            .name("Calculate Arbitrage");
        
        logger.info("✓ Interval Join 配置成功（时间窗口: ±10 秒）");
        
        // ========== 步骤 4: 创建订单流 ==========
        logger.info("创建订单 WebSocket 流...");
        
        DataStream<OrderUpdate> orderStream = env
            .addSource(new OKXOrderWebSocketSource(config))
            .map(new OrderUpdateParser())
            .filter(order -> order != null)
            .name("Parse Order Update");
        
        logger.info("✓ 订单流创建成功");
        
        // ========== 步骤 5: Connect 套利机会流和订单流 ==========
        logger.info("配置双流 Join（套利机会 + 订单流）...");
        
        DataStream<TradeRecord> tradeStream = arbitrageStream
            .keyBy(opp -> opp.symbol)
            .connect(orderStream.keyBy(order -> order.symbol))
            .process(new TradingDecisionProcessor(config))
            .name("Trading Decision");
        
        logger.info("✓ 双流 Join 配置成功");
        
        // ========== 步骤 6: 创建 Doris Sink Factory ==========
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        
        // ========== 步骤 7: 输出交易明细到 Doris ==========
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
        
        DorisSink<String> tradeSink = dorisSinkFactory.createDorisSink(
            "crypto_dw",
            "dwd_arbitrage_trades",
            "ads-arbitrage-trades"
        );
        
        tradeJsonStream.sinkTo(tradeSink).name("Doris Trade Sink");
        
        logger.info("✓ Doris Trade Sink 创建成功");
        logger.info("  Database: crypto_dw");
        logger.info("  Table: dwd_arbitrage_trades");
        
        // ========== 步骤 8: 输出套利机会到 Doris ==========
        DataStream<String> jsonStream = arbitrageStream
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
        env.execute("Flink ADS Arbitrage Job (Refactored)");
    }
}
