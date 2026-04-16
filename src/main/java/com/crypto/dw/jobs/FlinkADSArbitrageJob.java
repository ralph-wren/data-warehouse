package com.crypto.dw.jobs;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.factory.DorisSinkFactory;
import com.crypto.dw.factory.FlinkEnvironmentFactory;
import com.crypto.dw.factory.KafkaSourceFactory;
import com.crypto.dw.model.ArbitrageOpportunity;
import com.crypto.dw.model.FuturesPrice;
import com.crypto.dw.model.SpotPrice;
import com.crypto.dw.flink.processor.ArbitrageCalculator;
import com.crypto.dw.processor.TradingDecisionProcessor;
import com.crypto.dw.model.TickerData;
import com.crypto.dw.model.TradeRecord;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.doris.flink.sink.DorisSink;
import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.apache.flink.connector.kafka.source.KafkaSource;
import org.apache.flink.streaming.api.datastream.DataStream;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows;
import org.apache.flink.streaming.api.windowing.time.Time;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

/**
 * Flink ADS 跨市场套利机会计算作业 - 重构版
 * 
 * 业务场景：
 * 计算加密货币现货和合约（永续合约）之间的价差套利机会
 * 
 * 技术特性：
 * 1. 双流 Join：现货价格流 + 合约价格流
 * 2. Interval Join：时间窗口内的流关联
 * 3. 主动查询：订单明细通过异步任务主动查询
 * 4. 性能优化：异步 CSV 写入、内存缓存、移除黑名单过滤
 * 
 * 数据流图：
 * <pre>
 * Kafka(现货 crypto-ticker-spot) ──┐
 *                                   ├─→ Interval Join ──→ 计算套利空间 ──→ 交易决策 ──→ Doris
 * Kafka(合约 crypto-ticker-swap) ──┘
 * </pre>
 * 
 * 重构优化：
 * - 移除 WebSocket 订单流（改为主动查询）
 * - 移除黑名单过滤（简化数据流）
 * - 使用异步 CSV 写入（提升性能）
 * - 使用内存缓存杠杆支持信息（减少 Redis 查询）
 * - 拆分内部类到独立文件（提升可维护性）
 * 
 * @author Kiro AI Assistant
 * @date 2026-04-12
 */
public class FlinkADSArbitrageJob {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkADSArbitrageJob.class);
    
    // 静态 ObjectMapper 实例，线程安全
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    public static void main(String[] args) throws Exception {
        logger.info("==========================================");
        logger.info("Flink ADS Arbitrage Job (重构版)");
        logger.info("双流 Join + 主动查询订单明细");
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
        // 注意: 为现货和合约使用不同的 client.id,避免 JMX MBean 冲突
        String spotTopic = config.getString("kafka.topic.crypto-ticker-spot", "crypto-ticker-spot");
        KafkaSource<String> spotKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
            "ads-arbitrage-spot",
            spotTopic
        );
        
        DataStream<SpotPrice> spotStream = env.fromSource(
            spotKafkaSource,
            WatermarkStrategy
                .<String>forBoundedOutOfOrderness(Duration.ofSeconds(2))
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
        .name("Parse Spot Price")
        // ⭐ 按秒聚合,保留每秒最新的一条记录,减少join数据量
        .keyBy(spot -> spot.symbol)
        .window(TumblingEventTimeWindows.of(Time.seconds(1)))
        .reduce((spot1, spot2) -> spot2.timestamp > spot1.timestamp ? spot2 : spot1)
        .name("Aggregate Spot By Second");
        
        logger.info("✓ 现货价格流创建成功");
        
        // ========== 步骤 2: 创建合约价格流 ==========
        logger.info("创建合约价格流...");
        // 注意: 为现货和合约使用不同的 client.id,避免 JMX MBean 冲突
        String swapTopic = config.getString("kafka.topic.crypto-ticker-swap", "crypto-ticker-swap");
        KafkaSource<String> swapKafkaSource = kafkaSourceFactory.createKafkaSourceForJob(
            "ads-arbitrage-swap",
            swapTopic
        );
        
        DataStream<FuturesPrice> futuresStream = env.fromSource(
            swapKafkaSource,
            WatermarkStrategy
                .<String>forBoundedOutOfOrderness(Duration.ofSeconds(2))
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
        .name("Parse Swap Price")
        // ⭐ 按秒聚合,保留每秒最新的一条记录,减少join数据量
        .keyBy(futures -> futures.symbol)
        .window(org.apache.flink.streaming.api.windowing.assigners.TumblingEventTimeWindows.of(Time.seconds(1)))
        .reduce((futures1, futures2) -> futures2.timestamp > futures1.timestamp ? futures2 : futures1)
        .name("Aggregate Futures By Second");
        
        logger.info("✓ 合约价格流创建成功");
        
        // ========== 步骤 3: Interval Join 关联两个流 ==========
        logger.info("配置 Interval Join...");
        
        DataStream<ArbitrageOpportunity> arbitrageStream = spotStream
            .keyBy(spot -> spot.symbol)
            .intervalJoin(futuresStream.keyBy(futures -> futures.symbol))
            .between(Time.seconds(-2), Time.seconds(2))
            .process(new ArbitrageCalculator(config))  // 传入配置,从配置文件读取套利阈值
            .name("Calculate Arbitrage");
        
        logger.info("✓ Interval Join 配置成功（时间窗口: ±2 秒）");
        
        // ========== 步骤 4: 处理套利机会并执行交易决策 ==========
        logger.info("配置交易决策处理器...");
        
        DataStream<TradeRecord> tradeStream = arbitrageStream
            .keyBy(opp -> opp.symbol)
            .process(new TradingDecisionProcessor(config))
            .name("Trading Decision");
        
        logger.info("✓ 交易决策处理器配置成功");
        
        // ========== 步骤 5: 创建 Doris Sink Factory ==========
        DorisSinkFactory dorisSinkFactory = new DorisSinkFactory(config);
        
        // ========== 步骤 6: 输出交易明细到 Doris ==========
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
        
        // ========== 步骤 7: 输出套利明细事件到 Doris ==========
        DataStream<String> jsonStream = tradeStream
            .map(record -> {
                ObjectNode json = OBJECT_MAPPER.createObjectNode();
                putString(json, "record_id", buildRecordId(record));
                putString(json, "symbol", record.symbol);
                putLong(json, "event_time_ms", record.timestamp);
                putString(json, "event_type", record.eventType);
                putString(json, "event_stage", record.eventStage);
                putString(json, "log_time", formatDateTime(record.timestamp));
                putString(json, "spot_inst_id", record.spotInstId);
                putString(json, "swap_inst_id", record.swapInstId);
                putString(json, "arbitrage_direction", record.direction);
                putString(json, "position_status", record.positionStatus);
                putString(json, "action", record.action);
                putString(json, "close_reason", record.closeReason);
                putString(json, "error_code", record.errorCode);
                putString(json, "error_message", record.errorMessage);

                putDecimal(json, "discover_spot_price", record.discoverSpotPrice);
                putDecimal(json, "discover_swap_price", record.discoverSwapPrice);
                putDecimal(json, "discover_spread", record.discoverSpread);
                putDecimal(json, "discover_spread_rate", record.discoverSpreadRate);
                putDecimal(json, "unit_profit_estimate", record.unitProfitEstimate);
                putDecimal(json, "profit_estimate", record.discoverProfitEstimate);

                putBoolean(json, "trading_enabled", record.tradingEnabled);
                putDecimal(json, "trade_amount_usdt", record.tradeAmountUsdt);
                putDecimal(json, "open_threshold", record.openThreshold);
                putDecimal(json, "close_threshold", record.closeThreshold);
                putInteger(json, "max_hold_time_minutes", record.maxHoldTimeMinutes);
                putDecimal(json, "max_loss_usdt", record.maxLossUsdt);
                putInteger(json, "leverage_config", record.leverageConfig);

                putString(json, "spot_order_id", record.spotOrderId);
                putString(json, "swap_order_id", record.swapOrderId);
                putString(json, "spot_order_type", record.spotOrderType);
                putString(json, "swap_order_type", record.swapOrderType);
                putString(json, "spot_order_side", record.spotOrderSide);
                putString(json, "swap_order_side", record.swapOrderSide);
                putString(json, "spot_pos_side", record.spotPosSide);
                putString(json, "swap_pos_side", record.swapPosSide);
                putString(json, "spot_order_state", record.spotOrderState);
                putString(json, "swap_order_state", record.swapOrderState);
                putDecimal(json, "order_spot_price", record.orderSpotPrice);
                putDecimal(json, "order_swap_price", record.orderSwapPrice);
                putDecimal(json, "entry_spread_rate", record.entrySpreadRate);

                putDecimal(json, "actual_spot_price", record.actualSpotPrice);
                putDecimal(json, "actual_swap_price", record.actualSwapPrice);
                putDecimal(json, "actual_spot_filled_qty", record.actualSpotFilledQty);
                putDecimal(json, "actual_swap_filled_contracts", record.actualSwapFilledContracts);
                putDecimal(json, "actual_swap_filled_coin", record.actualSwapFilledCoin);
                putDecimal(json, "ct_val", record.ctVal);

                putDecimal(json, "amount_coin", record.amountCoin);
                putDecimal(json, "amount_usdt", record.amountUsdt);
                putDecimal(json, "spot_cost", record.spotCost);
                putDecimal(json, "swap_cost", record.swapCost);
                putDecimal(json, "total_cost", record.totalCost);
                putDecimal(json, "spot_fee", record.spotFee);
                putString(json, "spot_fee_ccy", record.spotFeeCcy);
                putDecimal(json, "swap_fee", record.swapFee);
                putString(json, "swap_fee_ccy", record.swapFeeCcy);
                putDecimal(json, "total_fee", record.totalFee);
                putDecimal(json, "total_expense", record.totalExpense);

                putLong(json, "hold_time_seconds", record.holdTimeSeconds);
                putDecimal(json, "current_spot_price", record.currentSpotPrice);
                putDecimal(json, "current_swap_price", record.currentSwapPrice);
                putDecimal(json, "current_spread", record.currentSpread);
                putDecimal(json, "current_spread_rate", record.currentSpreadRate);
                putDecimal(json, "hedged_coin_qty", record.hedgedCoinQty);
                putDecimal(json, "unhedged_coin_qty", record.unhedgedCoinQty);
                putDecimal(json, "unrealized_profit", record.unrealizedProfit);
                putDecimal(json, "profit_rate", record.detailedProfitRate);

                putDecimal(json, "close_spot_price", record.closeSpotPrice);
                putDecimal(json, "close_swap_price", record.closeSwapPrice);
                putDecimal(json, "close_spot_fee", record.closeSpotFee);
                putDecimal(json, "close_swap_fee", record.closeSwapFee);
                putDecimal(json, "realized_profit", record.realizedProfit);
                putDecimal(json, "realized_profit_rate", record.realizedProfitRate);

                putBoolean(json, "tracker_active", record.trackerActive);
                putDecimal(json, "tracker_spread_rate", record.trackerSpreadRate);
                putLong(json, "tracker_duration_seconds", record.trackerDurationSeconds);

                putString(json, "status_message", record.statusMessage);
                putString(json, "log_source", record.logSource);
                putString(json, "raw_payload_json", record.rawPayloadJson);
                putString(json, "ext_json", record.extJson);
                return json.toString();
            })
            .name("Arbitrage Detail To JSON");
        
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

    private static void putString(ObjectNode json, String field, String value) {
        if (value != null) {
            json.put(field, value);
        }
    }

    private static void putDecimal(ObjectNode json, String field, BigDecimal value) {
        if (value != null) {
            json.put(field, value);
        }
    }

    private static void putLong(ObjectNode json, String field, Long value) {
        if (value != null) {
            json.put(field, value);
        }
    }

    private static void putInteger(ObjectNode json, String field, Integer value) {
        if (value != null) {
            json.put(field, value);
        }
    }

    private static void putBoolean(ObjectNode json, String field, Boolean value) {
        if (value != null) {
            json.put(field, value);
        }
    }

    private static String buildRecordId(TradeRecord record) {
        String symbol = record.symbol != null ? record.symbol : "UNKNOWN";
        String eventType = record.eventType != null ? record.eventType : "UNKNOWN";
        return symbol + "_" + eventType + "_" + record.timestamp;
    }

    private static String formatDateTime(long timestampMs) {
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestampMs), ZoneId.of("Asia/Shanghai"))
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
    }
}
