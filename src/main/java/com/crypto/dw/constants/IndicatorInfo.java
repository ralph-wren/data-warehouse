package com.crypto.dw.constants;



import java.util.HashMap;
import java.util.Map;


public class IndicatorInfo{

    public static final String BUY = "BUY";
    public static final String SELL = "SELL";
    public static final String RUNNING = "RUNNING";
    public static final String STOPPED = "STOPPED";
    public static final String FILLED = "FILLED";
    public static final String CANCELED = "CANCELED";
    public static final String SUCCESS = "SUCCESS";
    public static final String FAILURE = "FAILURE";


    public static final String SOURCE_KLINE_PREFIX = "coin-rt-kline:";
    public static final String TARGET_INDICATOR_PREFIX = "coin-rt-indicator:";
    public static final String INDICATOR_SUBSCRIPTION_KEY = "kline:subscriptions";
    // 最少需要的K线数量
    public static final int MIN_KLINE_COUNT = 50;
    public static final Map<String, String> indicatorParamMap = new HashMap<>();

    // 策略类型常量
    // 移动平均线策略
    public static final String STRATEGY_SMA = "SMA";
    public static final String STRATEGY_EMA = "EMA";
    public static final String STRATEGY_TRIPLE_EMA = "TRIPLE_EMA";
    public static final String STRATEGY_WMA = "WMA";
    public static final String STRATEGY_HMA = "HMA";
    public static final String STRATEGY_KAMA = "KAMA";
    public static final String STRATEGY_ZLEMA = "ZLEMA";
    public static final String STRATEGY_DEMA = "DEMA";
    public static final String STRATEGY_TEMA = "TEMA";
    public static final String STRATEGY_VWAP = "VWAP";
    public static final String STRATEGY_TRIMA = "TRIMA"; // 三角移动平均线
    public static final String STRATEGY_T3 = "T3"; // 三重指数移动平均线
    public static final String STRATEGY_MAMA = "MAMA"; // MESA自适应移动平均线
    public static final String STRATEGY_VIDYA = "VIDYA"; // 可变指数动态平均线
    public static final String STRATEGY_WILDERS = "WILDERS"; // 威尔德平滑

    // 震荡指标策略
    public static final String STRATEGY_RSI = "RSI";
    public static final String STRATEGY_STOCHASTIC = "STOCHASTIC";
    public static final String STRATEGY_STOCHASTIC_RSI = "STOCHASTIC_RSI";
    public static final String STRATEGY_WILLIAMS_R = "WILLIAMS_R";
    public static final String STRATEGY_CCI = "CCI";
    public static final String STRATEGY_CMO = "CMO";
    public static final String STRATEGY_ROC = "ROC";
    public static final String STRATEGY_MACD = "MACD";
    public static final String STRATEGY_PPO = "PPO";
    public static final String STRATEGY_DPO = "DPO";
    public static final String STRATEGY_TRIX = "TRIX";
    public static final String STRATEGY_AWESOME_OSCILLATOR = "AWESOME_OSCILLATOR";
    public static final String STRATEGY_FISHER = "FISHER"; // Fisher变换
    public static final String STRATEGY_FOSC = "FOSC"; // 预测振荡器
    public static final String STRATEGY_EOM = "EOM"; // 移动便利性
    public static final String STRATEGY_CHOP = "CHOP"; // 震荡指数
    public static final String STRATEGY_KVO = "KVO"; // 克林格交易量振荡器
    public static final String STRATEGY_RVGI = "RVGI"; // 相对活力指数
    public static final String STRATEGY_STC = "STC"; // 沙夫趋势周期

    // 趋势指标策略
    public static final String STRATEGY_ADX = "ADX";
    public static final String STRATEGY_AROON = "AROON";
    public static final String STRATEGY_ICHIMOKU = "ICHIMOKU";
    public static final String STRATEGY_ICHIMOKU_CLOUD_BREAKOUT = "ICHIMOKU_CLOUD_BREAKOUT";
    public static final String STRATEGY_PARABOLIC_SAR = "PARABOLIC_SAR";
    public static final String STRATEGY_DMA = "DMA";
    public static final String STRATEGY_DMI = "DMI";
    public static final String STRATEGY_SUPERTREND = "SUPERTREND";
    public static final String STRATEGY_VORTEX = "VORTEX"; // 涡流指标
    public static final String STRATEGY_QSTICK = "QSTICK"; // Q棒指标
    public static final String STRATEGY_WILLIAMS_ALLIGATOR = "WILLIAMS_ALLIGATOR"; // 威廉姆斯鳄鱼指标
    public static final String STRATEGY_HT_TRENDLINE = "HT_TRENDLINE"; // 希尔伯特变换-瞬时趋势线

    // 波动指标策略
    public static final String STRATEGY_BOLLINGER_BANDS = "BOLLINGER_BANDS";
    public static final String STRATEGY_KELTNER_CHANNEL = "KELTNER_CHANNEL";
    public static final String STRATEGY_CHANDELIER_EXIT = "CHANDELIER_EXIT";
    public static final String STRATEGY_ULCER_INDEX = "ULCER_INDEX";
    public static final String STRATEGY_ATR = "ATR";
    public static final String STRATEGY_KDJ = "KDJ";
    public static final String STRATEGY_NATR = "NATR"; // 归一化平均真实范围
    public static final String STRATEGY_MASS = "MASS"; // 质量指数
    public static final String STRATEGY_STDDEV = "STDDEV"; // 标准差
    public static final String STRATEGY_SQUEEZE = "SQUEEZE"; // 挤压动量指标
    public static final String STRATEGY_BBW = "BBW"; // 布林带宽度
    public static final String STRATEGY_VOLATILITY = "VOLATILITY"; // 年化历史波动率
    public static final String STRATEGY_DONCHIAN_CHANNELS = "DONCHIAN_CHANNELS"; // 唐奇安通道

    // 成交量指标策略
    public static final String STRATEGY_OBV = "OBV";
    public static final String STRATEGY_MASS_INDEX = "MASS_INDEX";
    public static final String STRATEGY_AD = "AD"; // 累积/派发线
    public static final String STRATEGY_ADOSC = "ADOSC"; // 累积/派发振荡器
    public static final String STRATEGY_NVI = "NVI"; // 负成交量指数
    public static final String STRATEGY_PVI = "PVI"; // 正成交量指数
    public static final String STRATEGY_VWMA = "VWMA"; // 成交量加权移动平均线
    public static final String STRATEGY_VOSC = "VOSC"; // 成交量振荡器
    public static final String STRATEGY_MARKETFI = "MARKETFI"; // 市场便利指数

    // 机器学习策略 (91-100)
    public static final String STRATEGY_NEURAL_NETWORK = "NEURAL_NETWORK";
    public static final String STRATEGY_GENETIC_ALGORITHM = "GENETIC_ALGORITHM";
    public static final String STRATEGY_RANDOM_FOREST = "RANDOM_FOREST";
    public static final String STRATEGY_SVM = "SVM";
    public static final String STRATEGY_LSTM = "LSTM";
    public static final String STRATEGY_KNN = "KNN";
    public static final String STRATEGY_NAIVE_BAYES = "NAIVE_BAYES";
    public static final String STRATEGY_DECISION_TREE = "DECISION_TREE";
    public static final String STRATEGY_ENSEMBLE = "ENSEMBLE";
    public static final String STRATEGY_REINFORCEMENT_LEARNING = "REINFORCEMENT_LEARNING";

    // 量化因子策略 (101-105)
    public static final String STRATEGY_MOMENTUM_FACTOR = "MOMENTUM_FACTOR";
    public static final String STRATEGY_VALUE_FACTOR = "VALUE_FACTOR";
    public static final String STRATEGY_QUALITY_FACTOR = "QUALITY_FACTOR";
    public static final String STRATEGY_SIZE_FACTOR = "SIZE_FACTOR";
    public static final String STRATEGY_LOW_VOLATILITY_FACTOR = "LOW_VOLATILITY_FACTOR";

    // 高频和微观结构策略 (106-110)
    public static final String STRATEGY_MICROSTRUCTURE_IMBALANCE = "MICROSTRUCTURE_IMBALANCE";
    public static final String STRATEGY_MEAN_REVERSION_INTRADAY = "MEAN_REVERSION_INTRADAY";
    public static final String STRATEGY_MOMENTUM_INTRADAY = "MOMENTUM_INTRADAY";
    public static final String STRATEGY_ARBITRAGE_STATISTICAL = "ARBITRAGE_STATISTICAL";
    public static final String STRATEGY_PAIRS_TRADING = "PAIRS_TRADING";

    // 期权和波动率策略 (111-115)
    public static final String STRATEGY_VOLATILITY_SURFACE = "VOLATILITY_SURFACE";
    public static final String STRATEGY_GAMMA_SCALPING = "GAMMA_SCALPING";
    public static final String STRATEGY_VOLATILITY_MEAN_REVERSION = "VOLATILITY_MEAN_REVERSION";
    public static final String STRATEGY_VOLATILITY_MOMENTUM = "VOLATILITY_MOMENTUM";
    public static final String STRATEGY_IMPLIED_VOLATILITY_RANK = "IMPLIED_VOLATILITY_RANK";

    // 宏观和基本面策略 (116-120)
    public static final String STRATEGY_CARRY_TRADE = "CARRY_TRADE";
    public static final String STRATEGY_FUNDAMENTAL_SCORE = "FUNDAMENTAL_SCORE";
    public static final String STRATEGY_MACRO_MOMENTUM = "MACRO_MOMENTUM";
    public static final String STRATEGY_SEASONALITY = "SEASONALITY";
    public static final String STRATEGY_CALENDAR_SPREAD = "CALENDAR_SPREAD";

    // 创新和实验性策略 (121-125)
    public static final String STRATEGY_SENTIMENT_ANALYSIS = "SENTIMENT_ANALYSIS";
    public static final String STRATEGY_NETWORK_ANALYSIS = "NETWORK_ANALYSIS";
    public static final String STRATEGY_FRACTAL_GEOMETRY = "FRACTAL_GEOMETRY";
    public static final String STRATEGY_CHAOS_THEORY = "CHAOS_THEORY";
    public static final String STRATEGY_QUANTUM_INSPIRED = "QUANTUM_INSPIRED";

    // 风险管理策略 (126-130)
    public static final String STRATEGY_KELLY_CRITERION = "KELLY_CRITERION";
    public static final String STRATEGY_VAR_RISK_MANAGEMENT = "VAR_RISK_MANAGEMENT";
    public static final String STRATEGY_MAXIMUM_DRAWDOWN_CONTROL = "MAXIMUM_DRAWDOWN_CONTROL";
    public static final String STRATEGY_POSITION_SIZING = "POSITION_SIZING";
    public static final String STRATEGY_CORRELATION_FILTER = "CORRELATION_FILTER";

    // 蜡烛图形态策略
    public static final String STRATEGY_DOJI = "DOJI";
    public static final String STRATEGY_BULLISH_ENGULFING = "BULLISH_ENGULFING";
    public static final String STRATEGY_BEARISH_ENGULFING = "BEARISH_ENGULFING";
    public static final String STRATEGY_BULLISH_HARAMI = "BULLISH_HARAMI";
    public static final String STRATEGY_BEARISH_HARAMI = "BEARISH_HARAMI";
    public static final String STRATEGY_THREE_WHITE_SOLDIERS = "THREE_WHITE_SOLDIERS";
    public static final String STRATEGY_THREE_BLACK_CROWS = "THREE_BLACK_CROWS";
    public static final String STRATEGY_HANGING_MAN = "HANGING_MAN";
    public static final String STRATEGY_HAMMER = "HAMMER"; // 锤子线
    public static final String STRATEGY_INVERTED_HAMMER = "INVERTED_HAMMER"; // 倒锤子线
    public static final String STRATEGY_SHOOTING_STAR = "SHOOTING_STAR"; // 流星线
    public static final String STRATEGY_MORNING_STAR = "MORNING_STAR"; // 晨星
    public static final String STRATEGY_EVENING_STAR = "EVENING_STAR"; // 暮星
    public static final String STRATEGY_PIERCING = "PIERCING"; // 刺透形态
    public static final String STRATEGY_DARK_CLOUD_COVER = "DARK_CLOUD_COVER"; // 乌云盖顶
    public static final String STRATEGY_MARUBOZU = "MARUBOZU"; // 光头光脚阳线/阴线

    // 组合策略
    public static final String STRATEGY_DUAL_THRUST = "DUAL_THRUST";
    public static final String STRATEGY_TURTLE_TRADING = "TURTLE_TRADING";
    public static final String STRATEGY_MEAN_REVERSION = "MEAN_REVERSION";
    public static final String STRATEGY_TREND_FOLLOWING = "TREND_FOLLOWING";
    public static final String STRATEGY_BREAKOUT = "BREAKOUT";
    public static final String STRATEGY_GOLDEN_CROSS = "GOLDEN_CROSS";
    public static final String STRATEGY_DEATH_CROSS = "DEATH_CROSS";
    public static final String STRATEGY_DUAL_MA_WITH_RSI = "DUAL_MA_WITH_RSI";
    public static final String STRATEGY_MACD_WITH_BOLLINGER = "MACD_WITH_BOLLINGER";

    // 统计函数策略
    public static final String STRATEGY_BETA = "BETA"; // Beta系数
    public static final String STRATEGY_CORREL = "CORREL"; // 皮尔逊相关系数
    public static final String STRATEGY_LINEARREG = "LINEARREG"; // 线性回归
    public static final String STRATEGY_LINEARREG_ANGLE = "LINEARREG_ANGLE"; // 线性回归角度
    public static final String STRATEGY_LINEARREG_INTERCEPT = "LINEARREG_INTERCEPT"; // 线性回归截距
    public static final String STRATEGY_LINEARREG_SLOPE = "LINEARREG_SLOPE"; // 线性回归斜率
    public static final String STRATEGY_TSF = "TSF"; // 时间序列预测
    public static final String STRATEGY_VAR = "VAR"; // 方差

    // 希尔伯特变换策略
    public static final String STRATEGY_HT_DCPERIOD = "HT_DCPERIOD"; // 希尔伯特变换-主导周期
    public static final String STRATEGY_HT_DCPHASE = "HT_DCPHASE"; // 希尔伯特变换-主导相位
    public static final String STRATEGY_HT_PHASOR = "HT_PHASOR"; // 希尔伯特变换-相量分量
    public static final String STRATEGY_HT_SINE = "HT_SINE"; // 希尔伯特变换-正弦波
    public static final String STRATEGY_HT_TRENDMODE = "HT_TRENDMODE"; // 希尔伯特变换-趋势与周期模式
    public static final String STRATEGY_MSW = "MSW"; // MESA正弦波

    // 策略参数说明
    // 移动平均线策略参数
    public static final String SMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String EMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String TRIPLE_EMA_PARAMS_DESC = "短期EMA,中期EMA,长期EMA (例如：5,10,20)";
    public static final String WMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String HMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String KAMA_PARAMS_DESC = "周期,快速EMA周期,慢速EMA周期 (例如：10,2,30)";
    public static final String ZLEMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String DEMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String TEMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String VWAP_PARAMS_DESC = "周期 (例如：14)";
    public static final String TRIMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：5,20)";
    public static final String T3_PARAMS_DESC = "周期,成交量因子 (例如：5,0.7)";
    public static final String MAMA_PARAMS_DESC = "快速限制,慢速限制 (例如：0.5,0.05)";
    public static final String VIDYA_PARAMS_DESC = "短期周期,长期周期,alpha (例如：9,12,0.2)";
    public static final String WILDERS_PARAMS_DESC = "周期 (例如：14)";

    // 震荡指标策略参数
    public static final String RSI_PARAMS_DESC = "RSI周期,超卖阈值,超买阈值 (例如：14,30,70)";
    public static final String STOCHASTIC_PARAMS_DESC = "K周期,%K平滑周期,%D平滑周期,超卖阈值,超买阈值 (例如：14,3,3,20,80)";
    public static final String STOCHASTIC_RSI_PARAMS_DESC = "RSI周期,随机指标周期,K平滑周期,D平滑周期,超卖阈值,超买阈值 (例如：14,14,3,3,20,80)";
    public static final String WILLIAMS_R_PARAMS_DESC = "周期,超卖阈值,超买阈值 (例如：14,-80,-20)";
    public static final String CCI_PARAMS_DESC = "CCI周期,超卖阈值,超买阈值 (例如：20,-100,100)";
    public static final String CMO_PARAMS_DESC = "周期,超卖阈值,超买阈值 (例如：14,-30,30)";
    public static final String ROC_PARAMS_DESC = "周期,阈值 (例如：12,0)";
    public static final String MACD_PARAMS_DESC = "短周期,长周期,信号周期 (例如：12,26,9)";
    public static final String PPO_PARAMS_DESC = "短周期,长周期,信号周期 (例如：12,26,9)";
    public static final String DPO_PARAMS_DESC = "周期 (例如：20)";
    public static final String TRIX_PARAMS_DESC = "TRIX周期,信号周期 (例如：15,9)";
    public static final String AWESOME_OSCILLATOR_PARAMS_DESC = "短周期,长周期 (例如：5,34)";
    public static final String FISHER_PARAMS_DESC = "周期 (例如：10)";
    public static final String FOSC_PARAMS_DESC = "周期 (例如：14)";
    public static final String EOM_PARAMS_DESC = "周期,除数 (例如：14,100000000)";
    public static final String CHOP_PARAMS_DESC = "周期 (例如：14)";
    public static final String KVO_PARAMS_DESC = "短周期,长周期,信号周期 (例如：34,55,13)";
    public static final String RVGI_PARAMS_DESC = "周期,信号周期 (例如：10,4)";
    public static final String STC_PARAMS_DESC = "快周期,慢周期,信号周期,K平滑,D平滑 (例如：23,50,10,3,3)";

    // 趋势指标策略参数
    public static final String ADX_PARAMS_DESC = "ADX周期,DI周期,阈值 (例如：14,14,25)";
    public static final String AROON_PARAMS_DESC = "周期,阈值 (例如：25,70)";
    public static final String ICHIMOKU_PARAMS_DESC = "转换线周期,基准线周期,延迟跨度 (例如：9,26,52)";
    public static final String ICHIMOKU_CLOUD_BREAKOUT_PARAMS_DESC = "转换线周期,基准线周期,延迟跨度 (例如：9,26,52)";
    public static final String PARABOLIC_SAR_PARAMS_DESC = "步长,最大步长 (例如：0.02,0.2)";
    public static final String DMA_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：10,50)";
    public static final String DMI_PARAMS_DESC = "周期,ADX阈值 (例如：14,20)";
    public static final String SUPERTREND_PARAMS_DESC = "ATR周期,乘数 (例如：10,3.0)";
    public static final String VORTEX_PARAMS_DESC = "周期 (例如：14)";
    public static final String QSTICK_PARAMS_DESC = "周期 (例如：14)";
    public static final String WILLIAMS_ALLIGATOR_PARAMS_DESC = "下颚周期,牙齿周期,嘴唇周期,下颚偏移,牙齿偏移,嘴唇偏移 (例如：13,8,5,8,5,3)";
    public static final String HT_TRENDLINE_PARAMS_DESC = "周期 (例如：14)";

    // 波动指标策略参数
    public static final String BOLLINGER_PARAMS_DESC = "周期,标准差倍数 (例如：20,2.0)";
    public static final String KELTNER_CHANNEL_PARAMS_DESC = "EMA周期,ATR周期,乘数 (例如：20,10,2.0)";
    public static final String CHANDELIER_EXIT_PARAMS_DESC = "周期,乘数 (例如：22,3.0)";
    public static final String ULCER_INDEX_PARAMS_DESC = "周期,阈值 (例如：14,5.0)";
    public static final String ATR_PARAMS_DESC = "周期,乘数 (例如：14,2.0)";
    public static final String KDJ_PARAMS_DESC = "K周期,D周期,J周期,超卖阈值,超买阈值 (例如：9,3,3,20,80)";
    public static final String NATR_PARAMS_DESC = "周期 (例如：14)";
    public static final String MASS_PARAMS_DESC = "EMA周期,累积周期,阈值 (例如：9,25,27)";
    public static final String STDDEV_PARAMS_DESC = "周期,标准差倍数 (例如：20,2.0)";
    public static final String SQUEEZE_PARAMS_DESC = "BB周期,KC周期,BB倍数,KC倍数 (例如：20,20,2.0,1.5)";
    public static final String BBW_PARAMS_DESC = "周期,标准差倍数 (例如：20,2.0)";
    public static final String VOLATILITY_PARAMS_DESC = "周期 (例如：20)";
    public static final String DONCHIAN_CHANNELS_PARAMS_DESC = "周期 (例如：20)";

    // 成交量指标策略参数
    public static final String OBV_PARAMS_DESC = "短期OBV周期,长期OBV周期 (例如：5,20)";
    public static final String MASS_INDEX_PARAMS_DESC = "EMA周期,累积周期,阈值 (例如：9,25,27)";
    public static final String AD_PARAMS_DESC = "短期周期,长期周期 (例如：3,10)";
    public static final String ADOSC_PARAMS_DESC = "快速周期,慢速周期 (例如：3,10)";
    public static final String NVI_PARAMS_DESC = "短期周期,长期周期 (例如：1,255)";
    public static final String PVI_PARAMS_DESC = "短期周期,长期周期 (例如：1,255)";
    public static final String VWMA_PARAMS_DESC = "周期 (例如：20)";
    public static final String VOSC_PARAMS_DESC = "短周期,长周期 (例如：5,10)";
    public static final String MARKETFI_PARAMS_DESC = "周期 (例如：14)";

    // 蜡烛图形态策略参数
    public static final String DOJI_PARAMS_DESC = "影线比例阈值 (例如：0.1)";
    public static final String BULLISH_ENGULFING_PARAMS_DESC = "确认周期 (例如：1)";
    public static final String BEARISH_ENGULFING_PARAMS_DESC = "确认周期 (例如：1)";
    public static final String BULLISH_HARAMI_PARAMS_DESC = "确认周期 (例如：1)";
    public static final String BEARISH_HARAMI_PARAMS_DESC = "确认周期 (例如：1)";
    public static final String THREE_WHITE_SOLDIERS_PARAMS_DESC = "确认周期 (例如：3)";
    public static final String THREE_BLACK_CROWS_PARAMS_DESC = "确认周期 (例如：3)";
    public static final String HANGING_MAN_PARAMS_DESC = "上影线与实体比例阈值,下影线与实体比例阈值 (例如：0.1,2.0)";
    public static final String HAMMER_PARAMS_DESC = "影线比例阈值 (例如：2.0)";
    public static final String INVERTED_HAMMER_PARAMS_DESC = "影线比例阈值 (例如：2.0)";
    public static final String SHOOTING_STAR_PARAMS_DESC = "影线比例阈值 (例如：2.0)";
    public static final String MORNING_STAR_PARAMS_DESC = "渗透率 (例如：3.0)";
    public static final String EVENING_STAR_PARAMS_DESC = "渗透率 (例如：3.0)";
    public static final String PIERCING_PARAMS_DESC = "渗透率 (例如：50.0)";
    public static final String DARK_CLOUD_COVER_PARAMS_DESC = "渗透率 (例如：50.0)";
    public static final String MARUBOZU_PARAMS_DESC = "影线比例阈值 (例如：0.1)";

    // 组合策略参数
    public static final String DUAL_THRUST_PARAMS_DESC = "周期,K1,K2 (例如：14,0.5,0.5)";
    public static final String TURTLE_TRADING_PARAMS_DESC = "入场周期,出场周期,ATR周期,ATR乘数 (例如：20,10,14,2.0)";
    public static final String MEAN_REVERSION_PARAMS_DESC = "均线周期,标准差倍数 (例如：20,2.0)";
    public static final String TREND_FOLLOWING_PARAMS_DESC = "短期均线周期,长期均线周期,ADX周期,ADX阈值 (例如：5,20,14,25)";
    public static final String BREAKOUT_PARAMS_DESC = "突破周期,确认周期 (例如：20,3)";
    public static final String GOLDEN_CROSS_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：50,200)";
    public static final String DEATH_CROSS_PARAMS_DESC = "短期均线周期,长期均线周期 (例如：50,200)";
    public static final String DUAL_MA_WITH_RSI_PARAMS_DESC = "短期均线周期,长期均线周期,RSI周期,RSI阈值 (例如：5,20,14,50)";
    public static final String MACD_WITH_BOLLINGER_PARAMS_DESC = "MACD短周期,MACD长周期,MACD信号周期,布林带周期,布林带标准差倍数 (例如：12,26,9,20,2.0)";

    // 统计函数策略参数
    public static final String BETA_PARAMS_DESC = "周期 (例如：5)";
    public static final String CORREL_PARAMS_DESC = "周期 (例如：30)";
    public static final String LINEARREG_PARAMS_DESC = "周期 (例如：14)";
    public static final String LINEARREG_ANGLE_PARAMS_DESC = "周期 (例如：14)";
    public static final String LINEARREG_INTERCEPT_PARAMS_DESC = "周期 (例如：14)";
    public static final String LINEARREG_SLOPE_PARAMS_DESC = "周期 (例如：14)";
    public static final String TSF_PARAMS_DESC = "周期 (例如：14)";
    public static final String VAR_PARAMS_DESC = "周期 (例如：5)";

    // 希尔伯特变换策略参数
    public static final String HT_DCPERIOD_PARAMS_DESC = "周期 (例如：14)";
    public static final String HT_DCPHASE_PARAMS_DESC = "周期 (例如：14)";
    public static final String HT_PHASOR_PARAMS_DESC = "周期 (例如：14)";
    public static final String HT_SINE_PARAMS_DESC = "周期 (例如：14)";
    public static final String HT_TRENDMODE_PARAMS_DESC = "周期 (例如：14)";
    public static final String MSW_PARAMS_DESC = "周期 (例如：14)";

    // 高级策略常量 (50个新策略)
    public static final String STRATEGY_ADAPTIVE_BOLLINGER = "ADAPTIVE_BOLLINGER";
    public static final String STRATEGY_MULTI_TIMEFRAME_MACD = "MULTI_TIMEFRAME_MACD";
    public static final String STRATEGY_VOLATILITY_BREAKOUT = "VOLATILITY_BREAKOUT";
    public static final String STRATEGY_MOMENTUM_REVERSAL = "MOMENTUM_REVERSAL";
    public static final String STRATEGY_PRICE_CHANNEL_BREAKOUT = "PRICE_CHANNEL_BREAKOUT";
    public static final String STRATEGY_ADAPTIVE_RSI = "ADAPTIVE_RSI";
    public static final String STRATEGY_TRIPLE_SCREEN = "TRIPLE_SCREEN";
    public static final String STRATEGY_ELDER_RAY = "ELDER_RAY";
    public static final String STRATEGY_FORCE_INDEX = "FORCE_INDEX";
    public static final String STRATEGY_CHAIKIN_OSCILLATOR = "CHAIKIN_OSCILLATOR";
    public static final String STRATEGY_MONEY_FLOW_INDEX = "MONEY_FLOW_INDEX";
    public static final String STRATEGY_PRICE_VOLUME_TREND = "PRICE_VOLUME_TREND";
    public static final String STRATEGY_EASE_OF_MOVEMENT = "EASE_OF_MOVEMENT";
    public static final String STRATEGY_NEGATIVE_VOLUME_INDEX = "NEGATIVE_VOLUME_INDEX";
    public static final String STRATEGY_POSITIVE_VOLUME_INDEX = "POSITIVE_VOLUME_INDEX";
    public static final String STRATEGY_VOLUME_RATE_OF_CHANGE = "VOLUME_RATE_OF_CHANGE";
    public static final String STRATEGY_ACCUMULATION_DISTRIBUTION = "ACCUMULATION_DISTRIBUTION";
    public static final String STRATEGY_WILLIAMS_ACCUMULATION = "WILLIAMS_ACCUMULATION";
    public static final String STRATEGY_KLINGER_OSCILLATOR = "KLINGER_OSCILLATOR";
    public static final String STRATEGY_VOLUME_WEIGHTED_RSI = "VOLUME_WEIGHTED_RSI";
    public static final String STRATEGY_ADAPTIVE_MOVING_AVERAGE = "ADAPTIVE_MOVING_AVERAGE";
    public static final String STRATEGY_FRACTAL_ADAPTIVE_MA = "FRACTAL_ADAPTIVE_MA";
    public static final String STRATEGY_ZERO_LAG_EMA = "ZERO_LAG_EMA";
    public static final String STRATEGY_DOUBLE_EXPONENTIAL_MA = "DOUBLE_EXPONENTIAL_MA";
    public static final String STRATEGY_TRIPLE_EXPONENTIAL_MA = "TRIPLE_EXPONENTIAL_MA";
    public static final String STRATEGY_VARIABLE_MA = "VARIABLE_MA";
    public static final String STRATEGY_ADAPTIVE_LAGUERRE = "ADAPTIVE_LAGUERRE";
    public static final String STRATEGY_EHLERS_FILTER = "EHLERS_FILTER";
    public static final String STRATEGY_GAUSSIAN_FILTER = "GAUSSIAN_FILTER";
    public static final String STRATEGY_BUTTERWORTH_FILTER = "BUTTERWORTH_FILTER";
    public static final String STRATEGY_CYBER_CYCLE = "CYBER_CYCLE";
    public static final String STRATEGY_ROCKET_RSI = "ROCKET_RSI";
    public static final String STRATEGY_CONNORS_RSI = "CONNORS_RSI";
    public static final String STRATEGY_STOCHASTIC_MOMENTUM = "STOCHASTIC_MOMENTUM";
    public static final String STRATEGY_TRUE_STRENGTH_INDEX = "TRUE_STRENGTH_INDEX";
    public static final String STRATEGY_ULTIMATE_OSCILLATOR = "ULTIMATE_OSCILLATOR";
    public static final String STRATEGY_BALANCE_OF_POWER = "BALANCE_OF_POWER";
    public static final String STRATEGY_COMMODITY_SELECTION_INDEX = "COMMODITY_SELECTION_INDEX";
    public static final String STRATEGY_DIRECTIONAL_MOVEMENT_INDEX = "DIRECTIONAL_MOVEMENT_INDEX";
    public static final String STRATEGY_PLUS_DIRECTIONAL_INDICATOR = "PLUS_DIRECTIONAL_INDICATOR";
    public static final String STRATEGY_MINUS_DIRECTIONAL_INDICATOR = "MINUS_DIRECTIONAL_INDICATOR";
    public static final String STRATEGY_TREND_INTENSITY_INDEX = "TREND_INTENSITY_INDEX";
    public static final String STRATEGY_MASS_INDEX_REVERSAL = "MASS_INDEX_REVERSAL";
    public static final String STRATEGY_COPPOCK_CURVE = "COPPOCK_CURVE";
    public static final String STRATEGY_KNOW_SURE_THING = "KNOW_SURE_THING";
    public static final String STRATEGY_PRICE_OSCILLATOR = "PRICE_OSCILLATOR";
    public static final String STRATEGY_DETRENDED_PRICE_OSCILLATOR = "DETRENDED_PRICE_OSCILLATOR";
    public static final String STRATEGY_VERTICAL_HORIZONTAL_FILTER = "VERTICAL_HORIZONTAL_FILTER";
    public static final String STRATEGY_RAINBOW_OSCILLATOR = "RAINBOW_OSCILLATOR";
    public static final String STRATEGY_RELATIVE_MOMENTUM_INDEX = "RELATIVE_MOMENTUM_INDEX";
    public static final String STRATEGY_INTRADAY_MOMENTUM_INDEX = "INTRADAY_MOMENTUM_INDEX";
    public static final String STRATEGY_RANDOM_WALK_INDEX = "RANDOM_WALK_INDEX";

    // Batch 2 策略常量 (策略 51-90)
    // 动量反转策略 (51-60)
    public static final String STRATEGY_RSI_REVERSAL = "RSI_REVERSAL";
    public static final String STRATEGY_WILLIAMS_R_REVERSAL = "WILLIAMS_R_REVERSAL";
    public static final String STRATEGY_MOMENTUM_OSCILLATOR = "MOMENTUM_OSCILLATOR";
    public static final String STRATEGY_ROC_DIVERGENCE = "ROC_DIVERGENCE";
    public static final String STRATEGY_TRIX_SIGNAL = "TRIX_SIGNAL";
    public static final String STRATEGY_PARABOLIC_SAR_REVERSAL = "PARABOLIC_SAR_REVERSAL";
    public static final String STRATEGY_ATR_BREAKOUT = "ATR_BREAKOUT";
    public static final String STRATEGY_DONCHIAN_BREAKOUT = "DONCHIAN_BREAKOUT";
    public static final String STRATEGY_KELTNER_BREAKOUT = "KELTNER_BREAKOUT";
    public static final String STRATEGY_PRICE_CHANNEL = "PRICE_CHANNEL";

    // 成交量价格关系策略 (61-70)
    public static final String STRATEGY_VWMA_CROSSOVER = "VWMA_CROSSOVER";
    public static final String STRATEGY_ACCUMULATION_DISTRIBUTION_DIVERGENCE = "ACCUMULATION_DISTRIBUTION_DIVERGENCE";
    public static final String STRATEGY_OBV_DIVERGENCE = "OBV_DIVERGENCE";
    public static final String STRATEGY_PRICE_VOLUME_CONFIRMATION = "PRICE_VOLUME_CONFIRMATION";
    public static final String STRATEGY_VOLUME_OSCILLATOR_SIGNAL = "VOLUME_OSCILLATOR_SIGNAL";
    public static final String STRATEGY_POSITIVE_VOLUME_INDEX_SIGNAL = "POSITIVE_VOLUME_INDEX_SIGNAL";
    public static final String STRATEGY_NEGATIVE_VOLUME_INDEX_SIGNAL = "NEGATIVE_VOLUME_INDEX_SIGNAL";
    public static final String STRATEGY_VOLUME_RSI = "VOLUME_RSI";
    public static final String STRATEGY_VOLUME_WEIGHTED_RSI_SIGNAL = "VOLUME_WEIGHTED_RSI_SIGNAL";
    public static final String STRATEGY_VOLUME_BREAKOUT_CONFIRMATION = "VOLUME_BREAKOUT_CONFIRMATION";

    // 波动性统计分析策略 (71-80)
    public static final String STRATEGY_HISTORICAL_VOLATILITY = "HISTORICAL_VOLATILITY";
    public static final String STRATEGY_STANDARD_DEVIATION_CHANNEL = "STANDARD_DEVIATION_CHANNEL";
    public static final String STRATEGY_COEFFICIENT_OF_VARIATION = "COEFFICIENT_OF_VARIATION";
    public static final String STRATEGY_SKEWNESS = "SKEWNESS";
    public static final String STRATEGY_KURTOSIS = "KURTOSIS";
    public static final String STRATEGY_Z_SCORE = "Z_SCORE";
    public static final String STRATEGY_PERCENTILE = "PERCENTILE";
    public static final String STRATEGY_LINEAR_REGRESSION = "LINEAR_REGRESSION";
    public static final String STRATEGY_LINEAR_REGRESSION_SLOPE = "LINEAR_REGRESSION_SLOPE";
    public static final String STRATEGY_R_SQUARED = "R_SQUARED";

    // 复合指标和多重确认策略 (81-90)
    public static final String STRATEGY_MULTIPLE_MA_CONFIRMATION = "MULTIPLE_MA_CONFIRMATION";
    public static final String STRATEGY_RSI_MACD_CONFIRMATION = "RSI_MACD_CONFIRMATION";
    public static final String STRATEGY_BOLLINGER_RSI_COMBO = "BOLLINGER_RSI_COMBO";
    public static final String STRATEGY_TRIPLE_INDICATOR_CONFIRMATION = "TRIPLE_INDICATOR_CONFIRMATION";
    public static final String STRATEGY_MOMENTUM_BREAKOUT = "MOMENTUM_BREAKOUT";
    public static final String STRATEGY_VOLATILITY_BREAKOUT_SYSTEM = "VOLATILITY_BREAKOUT_SYSTEM";
    public static final String STRATEGY_TREND_STRENGTH = "TREND_STRENGTH";
    public static final String STRATEGY_SUPPORT_RESISTANCE_BREAKOUT = "SUPPORT_RESISTANCE_BREAKOUT";
    public static final String STRATEGY_PRICE_PATTERN_RECOGNITION = "PRICE_PATTERN_RECOGNITION";
    public static final String STRATEGY_COMPREHENSIVE_SCORING = "COMPREHENSIVE_SCORING";


    /**
     * Redis中实时价格数据的key
     */
    public static final String COIN_PRICE_KEY = "coin-rt-price";

    public static final String BALANCE = "balance";

    public static final String ALL_COIN_RT_PRICE = "all_coin-rt-price";

    /**
     * Redis中实时K线数据的key前缀
     */
    public static final String COIN_KLINE_PREFIX_KEY = "coin-rt-kline:";

    /**
     * Redis中历史K线数据的key前缀 (Sorted Set)
     */
    public static final String COIN_NRT_KLINE_PREFIX_KEY = "coin_nrt_kline:";
    /**
     * Redis中订阅币种列表的key
     */
    public static final String SUBSCRIBED_COINS_KEY = "subscribe-coins";

    public static final String TRADE_FLAG = "trade-control-flag:";
}
