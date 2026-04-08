-- ========================================
-- 套利交易明细表和状态表
-- ========================================
-- 创建时间: 2026-04-08
-- 用途: 记录套利交易的所有明细和持仓状态
-- ========================================

USE crypto_dw;

-- ========================================
-- 1. 交易明细表 (DWD层)
-- ========================================
-- 记录每一笔开仓和平仓的详细信息
CREATE TABLE IF NOT EXISTS dwd_arbitrage_trades (
    symbol VARCHAR(50) COMMENT '交易对,如 BTC-USDT',
    timestamp BIGINT COMMENT '时间戳(毫秒)',
    action VARCHAR(20) COMMENT '动作: OPEN(开仓) / CLOSE(平仓)',
    direction VARCHAR(50) COMMENT '方向: LONG_SPOT_SHORT_SWAP / SHORT_SPOT_LONG_SWAP',
    amount DECIMAL(20, 8) COMMENT '交易金额(USDT)',
    spot_price DECIMAL(20, 8) COMMENT '现货价格',
    swap_price DECIMAL(20, 8) COMMENT '合约价格',
    spread_rate DECIMAL(10, 6) COMMENT '价差率(%)',
    profit DECIMAL(20, 8) COMMENT '盈亏(USDT),仅平仓时有值',
    close_reason VARCHAR(50) COMMENT '平仓原因: 价差回归/超时平仓/止损',
    hold_time_ms BIGINT COMMENT '持仓时间(毫秒),仅平仓时有值',

)
DUPLICATE KEY(symbol, timestamp)
COMMENT '套利交易明细表 - 记录所有开仓和平仓操作'
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2"
);

-- ========================================
-- 2. 持仓状态表 (ADS层)
-- ========================================
-- 记录每个币对的当前持仓状态
CREATE TABLE IF NOT EXISTS ads_position_status (
    symbol VARCHAR(50) COMMENT '交易对,如 BTC-USDT',
    is_open BOOLEAN COMMENT '是否持仓: true(持仓中) / false(已平仓)',
    direction VARCHAR(50) COMMENT '方向: LONG_SPOT_SHORT_SWAP / SHORT_SPOT_LONG_SWAP',
    amount DECIMAL(20, 8) COMMENT '持仓金额(USDT)',
    entry_spot_price DECIMAL(20, 8) COMMENT '开仓现货价格',
    entry_swap_price DECIMAL(20, 8) COMMENT '开仓合约价格',
    entry_spread_rate DECIMAL(10, 6) COMMENT '开仓价差率(%)',
    open_time BIGINT COMMENT '开仓时间(毫秒)',
    update_time BIGINT COMMENT '最后更新时间(毫秒)'
)
UNIQUE KEY(symbol)
COMMENT '持仓状态表 - 记录每个币对的当前持仓状态'
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2"
);

-- ========================================
-- 3. 交易统计表 (ADS层)
-- ========================================
-- 按币对统计交易表现
CREATE TABLE IF NOT EXISTS ads_arbitrage_stats (
    symbol VARCHAR(50) COMMENT '交易对',
    total_trades INT COMMENT '总交易次数',
    win_trades INT COMMENT '盈利次数',
    loss_trades INT COMMENT '亏损次数',
    win_rate DECIMAL(10, 4) COMMENT '胜率(%)',
    total_profit DECIMAL(20, 8) COMMENT '总盈亏(USDT)',
    avg_profit DECIMAL(20, 8) COMMENT '平均盈亏(USDT)',
    max_profit DECIMAL(20, 8) COMMENT '最大盈利(USDT)',
    max_loss DECIMAL(20, 8) COMMENT '最大亏损(USDT)',
    avg_hold_time_ms BIGINT COMMENT '平均持仓时间(毫秒)',
    update_time BIGINT COMMENT '更新时间(毫秒)'
)
UNIQUE KEY(symbol)
COMMENT '套利交易统计表 - 按币对统计交易表现'
DISTRIBUTED BY HASH(symbol) BUCKETS 10
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2"
);

-- ========================================
-- 查询示例
-- ========================================

-- 查询所有交易明细
-- SELECT * FROM dwd_arbitrage_trades ORDER BY timestamp DESC LIMIT 100;

-- 查询当前持仓
-- SELECT * FROM ads_position_status WHERE is_open = true;

-- 查询交易统计
-- SELECT * FROM ads_arbitrage_stats ORDER BY total_profit DESC;

-- 查询某个币对的交易历史
-- SELECT * FROM dwd_arbitrage_trades WHERE symbol = 'BTC-USDT' ORDER BY timestamp DESC;

-- 计算总盈亏
-- SELECT 
--     SUM(profit) as total_profit,
--     COUNT(*) as total_trades,
--     AVG(profit) as avg_profit
-- FROM dwd_arbitrage_trades 
-- WHERE action = 'CLOSE';

-- 查询最近24小时的交易
-- SELECT * FROM dwd_arbitrage_trades 
-- WHERE timestamp > UNIX_TIMESTAMP(NOW() - INTERVAL 1 DAY) * 1000
-- ORDER BY timestamp DESC;

