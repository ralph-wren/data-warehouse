-- ========================================
-- 套利作业相关数据库表
-- ========================================
-- 创建时间: 2026-04-08
-- 说明: 用于存储套利交易记录和持仓信息
-- ========================================

-- 1. 套利交易记录表
-- 用途: 记录所有套利交易操作(开仓、平仓)
CREATE TABLE IF NOT EXISTS crypto_dw.ads_arbitrage_trades (
    symbol VARCHAR(50) COMMENT '交易对,如 BTC-USDT',
    action VARCHAR(20) COMMENT '操作类型: OPEN_PENDING(下单等待)/OPEN_CONFIRMED(开仓确认)/CLOSE(平仓)',
    direction VARCHAR(50) COMMENT '套利方向: LONG_SPOT_SHORT_SWAP(做多现货做空合约) / SHORT_SPOT_LONG_SWAP(做空现货做多合约)',
    amount DECIMAL(20, 8) COMMENT '交易金额(USDT)',
    spread_rate DECIMAL(10, 6) COMMENT '价差率(%)',
    spot_price DECIMAL(20, 8) COMMENT '现货价格',
    swap_price DECIMAL(20, 8) COMMENT '合约价格',
    profit DECIMAL(20, 8) COMMENT '盈亏(USDT),仅平仓时有值',
    close_reason VARCHAR(100) COMMENT '平仓原因,如: 价差回归、止损、超时等',
    hold_time_ms BIGINT COMMENT '持仓时间(毫秒),仅平仓时有值',
    timestamp BIGINT COMMENT '时间戳(毫秒)'
)
DUPLICATE KEY(symbol, timestamp)
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2"
)
COMMENT '套利交易记录表';

-- 2. 套利机会表(已存在,这里仅作说明)
-- 表名: crypto_dw.ads_arbitrage_opportunities
-- 用途: 存储发现的套利机会
-- 说明: 该表在 FlinkADSArbitrageJob 中已经创建

-- ========================================
-- 查询示例
-- ========================================

-- 查询最近的交易记录
-- SELECT 
--     symbol,
--     action,
--     direction,
--     amount,
--     spread_rate,
--     profit,
--     close_reason,
--     FROM_UNIXTIME(timestamp/1000) as trade_time
-- FROM crypto_dw.ads_arbitrage_trades
-- ORDER BY timestamp DESC
-- LIMIT 20;

-- 查询盈亏统计
-- SELECT 
--     symbol,
--     COUNT(*) as trade_count,
--     SUM(CASE WHEN action = 'CLOSE' THEN profit ELSE 0 END) as total_profit,
--     AVG(CASE WHEN action = 'CLOSE' THEN profit ELSE NULL END) as avg_profit,
--     MAX(CASE WHEN action = 'CLOSE' THEN profit ELSE NULL END) as max_profit,
--     MIN(CASE WHEN action = 'CLOSE' THEN profit ELSE NULL END) as min_profit
-- FROM crypto_dw.ads_arbitrage_trades
-- WHERE action = 'CLOSE'
-- GROUP BY symbol
-- ORDER BY total_profit DESC;

-- 查询持仓时间统计
-- SELECT 
--     symbol,
--     AVG(hold_time_ms / 1000 / 60) as avg_hold_minutes,
--     MAX(hold_time_ms / 1000 / 60) as max_hold_minutes,
--     MIN(hold_time_ms / 1000 / 60) as min_hold_minutes
-- FROM crypto_dw.ads_arbitrage_trades
-- WHERE action = 'CLOSE'
-- GROUP BY symbol;

-- ========================================
-- 注意事项
-- ========================================
-- 1. 该表使用 DUPLICATE KEY 模型,允许重复数据
-- 2. 使用 symbol 和 timestamp 作为排序键,优化查询性能
-- 3. 使用 HASH 分桶,分布在 10 个 bucket 中
-- 4. replication_num = 1 表示单副本,生产环境建议设置为 3
-- 5. 定期清理历史数据,避免表过大影响性能

-- ========================================
-- 数据清理(可选)
-- ========================================
-- 清理 30 天前的数据
-- DELETE FROM crypto_dw.ads_arbitrage_trades
-- WHERE timestamp < UNIX_TIMESTAMP(DATE_SUB(NOW(), INTERVAL 30 DAY)) * 1000;
