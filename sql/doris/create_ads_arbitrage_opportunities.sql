-- ADS 层：套利机会与交易状态明细宽表（V2 设计稿）
-- 用途：
-- 1. 保存套利机会发现日志
-- 2. 保存开仓/持仓/平仓阶段的详细指标
-- 3. 尽量覆盖控制台状态报告、CSV 状态日志、订单明细日志中的关键信息
-- 4. 方便后续做核对、排障、复盘、报表分析
DROP TABLE IF EXISTS ads_arbitrage_opportunities;
CREATE TABLE IF NOT EXISTS crypto_dw.ads_arbitrage_opportunities (
    record_id VARCHAR(128) COMMENT '记录唯一ID，建议代码侧生成：symbol_eventType_eventTime_ms',
    symbol VARCHAR(32) COMMENT '币种符号，如 BTC、ETH、KMNO',
    event_time_ms BIGINT COMMENT '事件时间戳（毫秒）',
    event_type VARCHAR(32) COMMENT '事件类型：DISCOVER/OPEN_PENDING/OPENED/POSITION_STATUS/CLOSE_PENDING/CLOSED/REJECTED/ERROR',
    event_stage VARCHAR(32) COMMENT '阶段：DISCOVERY/ORDER/POSITION/CLOSE/SUMMARY',
    log_time DATETIME COMMENT '日志时间，东八区时间',

    spot_inst_id VARCHAR(64) COMMENT '现货产品ID，如 BTC-USDT',
    swap_inst_id VARCHAR(64) COMMENT '合约产品ID，如 BTC-USDT-SWAP',
    arbitrage_direction VARCHAR(64) COMMENT '套利方向，如 LONG_SPOT_SHORT_SWAP / SHORT_SPOT_LONG_SWAP',
    position_status VARCHAR(32) COMMENT '持仓状态：OPEN/CLOSED/N/A',
    action VARCHAR(32) COMMENT '动作类型：OPEN/CLOSE/STATUS/REJECT/ERROR',
    close_reason VARCHAR(64) COMMENT '平仓原因：spread_converged/timeout/stop_loss/manual 等',
    error_code VARCHAR(64) COMMENT '错误码或外部接口返回码',
    error_message VARCHAR(1024) COMMENT '错误信息或失败原因',

    -- 发现阶段行情
    discover_spot_price DECIMAL(20, 8) COMMENT '发现阶段现货价格',
    discover_swap_price DECIMAL(20, 8) COMMENT '发现阶段合约价格',
    discover_spread DECIMAL(20, 8) COMMENT '发现阶段价差 = swap - spot',
    discover_spread_rate DECIMAL(20, 8) COMMENT '发现阶段价差率（百分比值）',
    unit_profit_estimate DECIMAL(20, 8) COMMENT '单位预估利润',
    profit_estimate DECIMAL(20, 8) COMMENT '发现阶段预估利润',

    -- 交易配置快照
    trading_enabled BOOLEAN COMMENT '交易开关',
    trade_amount_usdt DECIMAL(20, 8) COMMENT '配置的单笔交易金额（USDT）',
    open_threshold DECIMAL(20, 8) COMMENT '开仓阈值（百分比/小数按代码口径落库）',
    close_threshold DECIMAL(20, 8) COMMENT '平仓阈值（百分比/小数按代码口径落库）',
    max_hold_time_minutes INT COMMENT '最大持仓时长（分钟）',
    max_loss_usdt DECIMAL(20, 8) COMMENT '单笔最大亏损（USDT）',
    leverage_config INT COMMENT '策略配置杠杆倍数',
    spot_margin_mode VARCHAR(32) COMMENT '现货保证金模式，如 cross/isolated/cash',
    swap_margin_mode VARCHAR(32) COMMENT '合约保证金模式，如 cross/isolated',

    -- 开仓订单基础信息
    spot_order_id VARCHAR(64) COMMENT '现货订单ID',
    swap_order_id VARCHAR(64) COMMENT '合约订单ID',
    spot_order_type VARCHAR(32) COMMENT '现货订单类型，如 market/limit',
    swap_order_type VARCHAR(32) COMMENT '合约订单类型，如 market/limit',
    spot_order_side VARCHAR(16) COMMENT '现货订单方向，如 buy/sell',
    swap_order_side VARCHAR(16) COMMENT '合约订单方向，如 buy/sell',
    spot_pos_side VARCHAR(16) COMMENT '现货持仓方向/净仓方向',
    swap_pos_side VARCHAR(16) COMMENT '合约持仓方向，如 long/short/net',
    spot_order_state VARCHAR(32) COMMENT '现货订单状态，如 filled/partially_filled/canceled',
    swap_order_state VARCHAR(32) COMMENT '合约订单状态，如 filled/partially_filled/canceled',

    -- 开仓委托价格（日志打印口径）
    order_spot_price DECIMAL(20, 8) COMMENT '下单时现货价格/委托参考价',
    order_swap_price DECIMAL(20, 8) COMMENT '下单时合约价格/委托参考价',
    entry_spread_rate DECIMAL(20, 8) COMMENT '开仓时价差率（日志口径）',

    -- 实际成交明细
    actual_spot_price DECIMAL(20, 8) COMMENT '现货实际成交均价',
    actual_swap_price DECIMAL(20, 8) COMMENT '合约实际成交均价',
    actual_spot_filled_qty DECIMAL(20, 8) COMMENT '现货实际成交数量（币）',
    actual_swap_filled_contracts DECIMAL(20, 8) COMMENT '合约实际成交数量（张）',
    actual_swap_filled_coin DECIMAL(20, 8) COMMENT '合约折算后的币数量',
    ct_val DECIMAL(20, 8) COMMENT '合约面值 ctVal',
    lot_sz DECIMAL(20, 8) COMMENT '合约最小下单单位 lotSz',
    min_sz DECIMAL(20, 8) COMMENT '合约最小下单张数 minSz',
    instrument_max_leverage INT COMMENT '品种支持最大杠杆',

    -- 成本与费用
    amount_coin DECIMAL(20, 8) COMMENT '对冲币数量',
    amount_usdt DECIMAL(20, 8) COMMENT '标准名义投入（按现货真实成交价折算）',
    spot_cost DECIMAL(20, 8) COMMENT '现货成本 = 实际成交价 × 成交数量',
    swap_cost DECIMAL(20, 8) COMMENT '合约成本 = 实际成交价 × 合约张数 × ctVal',
    total_cost DECIMAL(20, 8) COMMENT '总成本 = 现货成本 + 合约成本',
    spot_fee DECIMAL(20, 8) COMMENT '现货手续费',
    spot_fee_ccy VARCHAR(32) COMMENT '现货手续费币种',
    swap_fee DECIMAL(20, 8) COMMENT '合约手续费',
    swap_fee_ccy VARCHAR(32) COMMENT '合约手续费币种',
    total_fee DECIMAL(20, 8) COMMENT '总手续费（统一按 USDT 口径）',
    total_expense DECIMAL(20, 8) COMMENT '总费用 = 总成本 + 总手续费',

    -- 持仓实时状态
    hold_time_seconds BIGINT COMMENT '持仓时长（秒）',
    current_spot_price DECIMAL(20, 8) COMMENT '当前现货价格',
    current_swap_price DECIMAL(20, 8) COMMENT '当前合约价格',
    current_spread DECIMAL(20, 8) COMMENT '当前价差 = 当前合约价 - 当前现货价',
    current_spread_rate DECIMAL(20, 8) COMMENT '当前价差率（百分比值）',
    hedged_coin_qty DECIMAL(20, 8) COMMENT '已对冲数量（币）',
    unhedged_coin_qty DECIMAL(20, 8) COMMENT '未对冲敞口数量（币）',
    unrealized_profit DECIMAL(20, 8) COMMENT '未实现利润（USDT）',
    profit_rate DECIMAL(20, 8) COMMENT '利润率（百分比值）',

    -- 平仓结果
    close_spot_price DECIMAL(20, 8) COMMENT '平仓现货实际成交价',
    close_swap_price DECIMAL(20, 8) COMMENT '平仓合约实际成交价',
    close_spot_fee DECIMAL(20, 8) COMMENT '平仓现货手续费',
    close_swap_fee DECIMAL(20, 8) COMMENT '平仓合约手续费',
    realized_profit DECIMAL(20, 8) COMMENT '最终已实现利润（USDT）',
    realized_profit_rate DECIMAL(20, 8) COMMENT '最终已实现利润率（百分比值）',

    -- 机会跟踪状态
    tracker_active BOOLEAN COMMENT '是否存在活跃机会跟踪',
    tracker_spread_rate DECIMAL(20, 8) COMMENT '跟踪器记录的价差率',
    tracker_duration_seconds BIGINT COMMENT '跟踪器持续时长（秒）',

    -- 日志富文本/原始快照
    status_message VARCHAR(2048) COMMENT '状态摘要，如控制台状态报告核心内容',
    log_source VARCHAR(64) COMMENT '日志来源：DISCOVERY_LOG/STATUS_LOG/ORDER_DETAIL/SUMMARY/ERROR_LOG',
    raw_payload_json STRING COMMENT '原始 JSON 快照，建议保存机会对象/订单详情/状态快照',
    ext_json STRING COMMENT '扩展字段 JSON，后续新增字段可先写入这里',

    created_at DATETIME DEFAULT CURRENT_TIMESTAMP COMMENT '写入时间'
)
DUPLICATE KEY(record_id, symbol, event_time_ms)
COMMENT 'ADS 层 - 套利机会、持仓状态与日志明细宽表'
DISTRIBUTED BY HASH(symbol) BUCKETS 16
PROPERTIES (
    "replication_num" = "1",
    "storage_format" = "V2",
    "compression" = "LZ4"
);
