package com.crypto.dw.strategy;


import com.crypto.dw.config.BacktestParameterConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Rule;
import org.ta4j.core.Strategy;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.rules.OrRule;
import org.ta4j.core.rules.StopLossRule;
import org.ta4j.core.rules.TrailingStopLossRule;
import com.crypto.dw.strategy.*;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Function;

import static com.crypto.dw.constants.IndicatorInfo.*;


public class StrategyRegisterCenter {

    private static final Logger log = LoggerFactory.getLogger(StrategyRegisterCenter.class);

    private static BacktestParameterConfig backtestParameterConfig=new BacktestParameterConfig();

    // 策略创建函数映射
    public static final Map<String, Function<BarSeries, Strategy>> strategyCreators = new HashMap<>();

    /**
     * 创建策略
     *
     * @param series       BarSeries对象
     * @param strategyType 策略类型
     * @param params       策略参数
     * @return 策略对象
     */
    public static Strategy createStrategy(BarSeries series, String strategyType) {
        Function<BarSeries, Strategy> strategyCreator = strategyCreators.get(strategyType);

        if (strategyCreator == null) {
            throw new IllegalArgumentException("不支持的策略类型: " + strategyType);
        }

        if (series == null || series.getBarCount() == 0) {
            throw new IllegalArgumentException("K线数据不能为空");
        }

        return strategyCreator.apply(series);
    }

    static {
        // 注册所有策略创建函数
        // 移动平均线策略
        strategyCreators.put(STRATEGY_SMA, StrategyFactory1 ::createSMAStrategy);
        strategyCreators.put(STRATEGY_EMA, StrategyFactory1::createEMAStrategy);
        strategyCreators.put(STRATEGY_TRIPLE_EMA, StrategyFactory1::createTripleEMAStrategy);
        strategyCreators.put(STRATEGY_WMA, StrategyFactory1::createWMAStrategy);
        strategyCreators.put(STRATEGY_HMA, StrategyFactory1::createHMAStrategy);
        strategyCreators.put(STRATEGY_KAMA, StrategyFactory1::createKAMAStrategy);
        strategyCreators.put(STRATEGY_ZLEMA, StrategyFactory1::createZLEMAStrategy);
        strategyCreators.put(STRATEGY_DEMA, StrategyFactory1::createDEMAStrategy);
        strategyCreators.put(STRATEGY_TEMA, StrategyFactory1::createTEMAStrategy);
        strategyCreators.put(STRATEGY_VWAP, StrategyFactory1::createVWAPStrategy);

        // 震荡指标策略
        strategyCreators.put(STRATEGY_RSI, StrategyFactory1::createRSIStrategy);
        strategyCreators.put(STRATEGY_STOCHASTIC, StrategyFactory1::createStochasticStrategy);
        strategyCreators.put(STRATEGY_STOCHASTIC_RSI, StrategyFactory1::createStochasticRSIStrategy);
        strategyCreators.put(STRATEGY_WILLIAMS_R, StrategyFactory1::createWilliamsRStrategy);
        strategyCreators.put(STRATEGY_CCI, StrategyFactory1::createCCIStrategy);
        strategyCreators.put(STRATEGY_CMO, StrategyFactory1::createCMOStrategy);
        strategyCreators.put(STRATEGY_ROC, StrategyFactory1::createROCStrategy);
        strategyCreators.put(STRATEGY_MACD, StrategyFactory1::createMACDStrategy);
        strategyCreators.put(STRATEGY_PPO, StrategyFactory1::createPPOStrategy);
        strategyCreators.put(STRATEGY_DPO, StrategyFactory1::createDPOStrategy);
        strategyCreators.put(STRATEGY_TRIX, StrategyFactory1::createTRIXStrategy);

        // 趋势指标策略
        strategyCreators.put(STRATEGY_ADX, StrategyFactory1::createADXStrategy);
        strategyCreators.put(STRATEGY_AROON, StrategyFactory1::createAroonStrategy);
        strategyCreators.put(STRATEGY_ICHIMOKU, StrategyFactory1::createIchimokuStrategy);
        strategyCreators.put(STRATEGY_PARABOLIC_SAR, StrategyFactory1::createParabolicSARStrategy);
        strategyCreators.put(STRATEGY_DMA, StrategyFactory1::createDMAStrategy);
        strategyCreators.put(STRATEGY_DMI, StrategyFactory1::createDMIStrategy);
        strategyCreators.put(STRATEGY_SUPERTREND, StrategyFactory1::createSupertrendStrategy);
        strategyCreators.put(STRATEGY_ICHIMOKU_CLOUD_BREAKOUT, StrategyFactory1::createIchimokuCloudBreakoutStrategy);
        strategyCreators.put(STRATEGY_AWESOME_OSCILLATOR, StrategyFactory1::createAwesomeOscillatorStrategy);

        // 波动指标策略
        strategyCreators.put(STRATEGY_BOLLINGER_BANDS, StrategyFactory1::createBollingerBandsStrategy);
        strategyCreators.put(STRATEGY_CHANDELIER_EXIT, StrategyFactory1::createChandelierExitStrategy);
        strategyCreators.put(STRATEGY_ULCER_INDEX, StrategyFactory1::createUlcerIndexStrategy);
        strategyCreators.put(STRATEGY_KELTNER_CHANNEL, StrategyFactory1::createKeltnerChannelStrategy);
        strategyCreators.put(STRATEGY_ATR, StrategyFactory1::createATRStrategy);

        // 成交量指标策略
        strategyCreators.put(STRATEGY_OBV, StrategyFactory1::createOBVStrategy);
        strategyCreators.put(STRATEGY_MASS_INDEX, StrategyFactory1::createMassIndexStrategy);
        strategyCreators.put(STRATEGY_KDJ, StrategyFactory1::createKDJStrategy);

        // 蜡烛图形态策略
        strategyCreators.put(STRATEGY_DOJI, StrategyFactory1::createDojiStrategy);
        strategyCreators.put(STRATEGY_BULLISH_ENGULFING, StrategyFactory1::createBullishEngulfingStrategy);
        strategyCreators.put(STRATEGY_BEARISH_ENGULFING, StrategyFactory1::createBearishEngulfingStrategy);
        strategyCreators.put(STRATEGY_BULLISH_HARAMI, StrategyFactory1::createBullishHaramiStrategy);
        strategyCreators.put(STRATEGY_BEARISH_HARAMI, StrategyFactory1::createBearishHaramiStrategy);
        strategyCreators.put(STRATEGY_THREE_WHITE_SOLDIERS, StrategyFactory1::createThreeWhiteSoldiersStrategy);
        strategyCreators.put(STRATEGY_THREE_BLACK_CROWS, StrategyFactory1::createThreeBlackCrowsStrategy);
        strategyCreators.put(STRATEGY_HANGING_MAN, StrategyFactory1::createHangingManStrategy);

        // 组合策略
        strategyCreators.put(STRATEGY_TURTLE_TRADING, StrategyFactory1::createTurtleTradingStrategy);
        strategyCreators.put(STRATEGY_MEAN_REVERSION, StrategyFactory1::createMeanReversionStrategy);
        strategyCreators.put(STRATEGY_DUAL_THRUST, StrategyFactory1::createDualThrustStrategy);
        strategyCreators.put(STRATEGY_TREND_FOLLOWING, StrategyFactory1::createTrendFollowingStrategy);
        strategyCreators.put(STRATEGY_BREAKOUT, StrategyFactory1::createBreakoutStrategy);
        strategyCreators.put(STRATEGY_GOLDEN_CROSS, StrategyFactory1::createGoldenCrossStrategy);
        strategyCreators.put(STRATEGY_DEATH_CROSS, StrategyFactory1::createDeathCrossStrategy);
        strategyCreators.put(STRATEGY_DUAL_MA_WITH_RSI, StrategyFactory1::createDualMAWithRSIStrategy);
        strategyCreators.put(STRATEGY_MACD_WITH_BOLLINGER, StrategyFactory1::createMACDWithBollingerStrategy);

        // 添加新的移动平均线策略
        strategyCreators.put(STRATEGY_TRIMA, StrategyFactory1::createTrimaStrategy);
        strategyCreators.put(STRATEGY_T3, StrategyFactory1::createT3Strategy);
        strategyCreators.put(STRATEGY_MAMA, StrategyFactory1::createMamaStrategy);
        strategyCreators.put(STRATEGY_VIDYA, StrategyFactory1::createVidyaStrategy);
        strategyCreators.put(STRATEGY_WILDERS, StrategyFactory1::createWildersStrategy);

        // 添加新的震荡指标策略
        strategyCreators.put(STRATEGY_FISHER, StrategyFactory1::createFisherStrategy);
        strategyCreators.put(STRATEGY_FOSC, StrategyFactory1::createFoscStrategy);
        strategyCreators.put(STRATEGY_EOM, StrategyFactory1::createEomStrategy);
        strategyCreators.put(STRATEGY_CHOP, StrategyFactory1::createChopStrategy);
        strategyCreators.put(STRATEGY_KVO, StrategyFactory1::createKvoStrategy);
        strategyCreators.put(STRATEGY_RVGI, StrategyFactory1::createRvgiStrategy);
        strategyCreators.put(STRATEGY_STC, StrategyFactory1::createStcStrategy);

        // 添加新的趋势指标策略
        strategyCreators.put(STRATEGY_VORTEX, StrategyFactory1::createVortexStrategy);
        strategyCreators.put(STRATEGY_QSTICK, StrategyFactory1::createQstickStrategy);
        strategyCreators.put(STRATEGY_WILLIAMS_ALLIGATOR, StrategyFactory1::createWilliamsAlligatorStrategy);
        strategyCreators.put(STRATEGY_HT_TRENDLINE, StrategyFactory1::createHtTrendlineStrategy);

        // 添加新的波动指标策略
        strategyCreators.put(STRATEGY_NATR, StrategyFactory1::createNatrStrategy);
        strategyCreators.put(STRATEGY_MASS, StrategyFactory1::createMassStrategy);
        strategyCreators.put(STRATEGY_STDDEV, StrategyFactory1::createStddevStrategy);
        strategyCreators.put(STRATEGY_SQUEEZE, StrategyFactory1::createSqueezeStrategy);
        strategyCreators.put(STRATEGY_BBW, StrategyFactory1::createBbwStrategy);
        strategyCreators.put(STRATEGY_VOLATILITY, StrategyFactory1::createVolatilityStrategy);
        strategyCreators.put(STRATEGY_DONCHIAN_CHANNELS, StrategyFactory1::createDonchianChannelsStrategy);

        // 添加新的成交量指标策略
        strategyCreators.put(STRATEGY_AD, StrategyFactory1::createAdStrategy);
        strategyCreators.put(STRATEGY_ADOSC, StrategyFactory1::createAdoscStrategy);
        strategyCreators.put(STRATEGY_NVI, StrategyFactory1::createNviStrategy);
        strategyCreators.put(STRATEGY_PVI, StrategyFactory1::createPviStrategy);
        strategyCreators.put(STRATEGY_VWMA, StrategyFactory1::createVwmaStrategy);
        strategyCreators.put(STRATEGY_VOSC, StrategyFactory1::createVoscStrategy);
        strategyCreators.put(STRATEGY_MARKETFI, StrategyFactory1::createMarketfiStrategy);

        // 添加新的蜡烛图形态策略
        strategyCreators.put(STRATEGY_HAMMER, StrategyFactory1::createHammerStrategy);
        strategyCreators.put(STRATEGY_INVERTED_HAMMER, StrategyFactory1::createInvertedHammerStrategy);
        strategyCreators.put(STRATEGY_SHOOTING_STAR, StrategyFactory1::createShootingStarStrategy);
        strategyCreators.put(STRATEGY_MORNING_STAR, StrategyFactory1::createMorningStarStrategy);
        strategyCreators.put(STRATEGY_EVENING_STAR, StrategyFactory1::createEveningStarStrategy);
        strategyCreators.put(STRATEGY_PIERCING, StrategyFactory1::createPiercingStrategy);
        strategyCreators.put(STRATEGY_DARK_CLOUD_COVER, StrategyFactory1::createDarkCloudCoverStrategy);
        strategyCreators.put(STRATEGY_MARUBOZU, StrategyFactory1::createMarubozuStrategy);

        // 添加统计函数策略
        strategyCreators.put(STRATEGY_BETA, StrategyFactory1::createBetaStrategy);
        strategyCreators.put(STRATEGY_CORREL, StrategyFactory1::createCorrelStrategy);
        strategyCreators.put(STRATEGY_LINEARREG, StrategyFactory1::createLinearregStrategy);
        strategyCreators.put(STRATEGY_LINEARREG_ANGLE, StrategyFactory1::createLinearregAngleStrategy);
        strategyCreators.put(STRATEGY_LINEARREG_INTERCEPT, StrategyFactory1::createLinearregInterceptStrategy);
        strategyCreators.put(STRATEGY_LINEARREG_SLOPE, StrategyFactory1::createLinearregSlopeStrategy);
        strategyCreators.put(STRATEGY_TSF, StrategyFactory1::createTsfStrategy);
        strategyCreators.put(STRATEGY_VAR, StrategyFactory1::createVarStrategy);

        // 添加希尔伯特变换策略
        strategyCreators.put(STRATEGY_HT_DCPERIOD, StrategyFactory1::createHtDcperiodStrategy);
        strategyCreators.put(STRATEGY_HT_DCPHASE, StrategyFactory1::createHtDcphaseStrategy);
        strategyCreators.put(STRATEGY_HT_PHASOR, StrategyFactory1::createHtPhasorStrategy);
        strategyCreators.put(STRATEGY_HT_SINE, StrategyFactory1::createHtSineStrategy);
        strategyCreators.put(STRATEGY_HT_TRENDMODE, StrategyFactory1::createHtTrendmodeStrategy);
        strategyCreators.put(STRATEGY_MSW, StrategyFactory1::createMswStrategy);

        // 集成AdvancedStrategyFactory的50个新策略
        // 注册所有高级策略创建函数
        strategyCreators.put(STRATEGY_ADAPTIVE_BOLLINGER, StrategyFactory2::createAdaptiveBollingerStrategy);
        strategyCreators.put(STRATEGY_MULTI_TIMEFRAME_MACD, StrategyFactory2::createMultiTimeframeMACDStrategy);
        strategyCreators.put(STRATEGY_VOLATILITY_BREAKOUT, StrategyFactory2::createVolatilityBreakoutStrategy);
        strategyCreators.put(STRATEGY_MOMENTUM_REVERSAL, StrategyFactory2::createMomentumReversalStrategy);
        strategyCreators.put(STRATEGY_PRICE_CHANNEL_BREAKOUT, StrategyFactory2::createPriceChannelBreakoutStrategy);
        strategyCreators.put(STRATEGY_ADAPTIVE_RSI, StrategyFactory2::createAdaptiveRSIStrategy);
        strategyCreators.put(STRATEGY_TRIPLE_SCREEN, StrategyFactory2::createTripleScreenStrategy);
        strategyCreators.put(STRATEGY_ELDER_RAY, StrategyFactory2::createElderRayStrategy);
        strategyCreators.put(STRATEGY_FORCE_INDEX, StrategyFactory2::createForceIndexStrategy);
        strategyCreators.put(STRATEGY_CHAIKIN_OSCILLATOR, StrategyFactory2::createChaikinOscillatorStrategy);
        strategyCreators.put(STRATEGY_MONEY_FLOW_INDEX, StrategyFactory2::createMoneyFlowIndexStrategy);
        strategyCreators.put(STRATEGY_PRICE_VOLUME_TREND, StrategyFactory2::createPriceVolumeTrendStrategy);
        strategyCreators.put(STRATEGY_EASE_OF_MOVEMENT, StrategyFactory2::createEaseOfMovementStrategy);
        strategyCreators.put(STRATEGY_NEGATIVE_VOLUME_INDEX, StrategyFactory2::createNegativeVolumeIndexStrategy);
        strategyCreators.put(STRATEGY_POSITIVE_VOLUME_INDEX, StrategyFactory2::createPositiveVolumeIndexStrategy);
        strategyCreators.put(STRATEGY_VOLUME_RATE_OF_CHANGE, StrategyFactory2::createVolumeRateOfChangeStrategy);
        strategyCreators.put(STRATEGY_ACCUMULATION_DISTRIBUTION, StrategyFactory2::createAccumulationDistributionStrategy);
        strategyCreators.put(STRATEGY_WILLIAMS_ACCUMULATION, StrategyFactory2::createWilliamsAccumulationStrategy);
        strategyCreators.put(STRATEGY_KLINGER_OSCILLATOR, StrategyFactory2::createKlingerOscillatorStrategy);
        strategyCreators.put(STRATEGY_VOLUME_WEIGHTED_RSI, StrategyFactory2::createVolumeWeightedRSIStrategy);
        strategyCreators.put(STRATEGY_ADAPTIVE_MOVING_AVERAGE, StrategyFactory2::createAdaptiveMovingAverageStrategy);
        strategyCreators.put(STRATEGY_FRACTAL_ADAPTIVE_MA, StrategyFactory2::createFractalAdaptiveMAStrategy);
        strategyCreators.put(STRATEGY_ZERO_LAG_EMA, StrategyFactory2::createZeroLagEMAStrategy);
        strategyCreators.put(STRATEGY_DOUBLE_EXPONENTIAL_MA, StrategyFactory2::createDoubleExponentialMAStrategy);
        strategyCreators.put(STRATEGY_TRIPLE_EXPONENTIAL_MA, StrategyFactory2::createTripleExponentialMAStrategy);
        strategyCreators.put(STRATEGY_VARIABLE_MA, StrategyFactory2::createVariableMAStrategy);
        strategyCreators.put(STRATEGY_ADAPTIVE_LAGUERRE, StrategyFactory2::createAdaptiveLaguerreStrategy);
        strategyCreators.put(STRATEGY_EHLERS_FILTER, StrategyFactory2::createEhlersFilterStrategy);
        strategyCreators.put(STRATEGY_GAUSSIAN_FILTER, StrategyFactory2::createGaussianFilterStrategy);
        strategyCreators.put(STRATEGY_BUTTERWORTH_FILTER, StrategyFactory2::createButterworthFilterStrategy);
        strategyCreators.put(STRATEGY_CYBER_CYCLE, StrategyFactory2::createCyberCycleStrategy);
        strategyCreators.put(STRATEGY_ROCKET_RSI, StrategyFactory2::createRocketRSIStrategy);
        strategyCreators.put(STRATEGY_CONNORS_RSI, StrategyFactory2::createConnorsRSIStrategy);
        strategyCreators.put(STRATEGY_STOCHASTIC_MOMENTUM, StrategyFactory2::createStochasticMomentumStrategy);
        strategyCreators.put(STRATEGY_TRUE_STRENGTH_INDEX, StrategyFactory2::createTrueStrengthIndexStrategy);
        strategyCreators.put(STRATEGY_ULTIMATE_OSCILLATOR, StrategyFactory2::createUltimateOscillatorStrategy);
        strategyCreators.put(STRATEGY_BALANCE_OF_POWER, StrategyFactory2::createBalanceOfPowerStrategy);
        strategyCreators.put(STRATEGY_COMMODITY_SELECTION_INDEX, StrategyFactory2::createCommoditySelectionIndexStrategy);
        strategyCreators.put(STRATEGY_DIRECTIONAL_MOVEMENT_INDEX, StrategyFactory2::createDirectionalMovementIndexStrategy);
        strategyCreators.put(STRATEGY_PLUS_DIRECTIONAL_INDICATOR, StrategyFactory2::createPlusDirectionalIndicatorStrategy);
        strategyCreators.put(STRATEGY_MINUS_DIRECTIONAL_INDICATOR, StrategyFactory2::createMinusDirectionalIndicatorStrategy);
        strategyCreators.put(STRATEGY_TREND_INTENSITY_INDEX, StrategyFactory2::createTrendIntensityIndexStrategy);
        strategyCreators.put(STRATEGY_MASS_INDEX_REVERSAL, StrategyFactory2::createMassIndexReversalStrategy);
        strategyCreators.put(STRATEGY_COPPOCK_CURVE, StrategyFactory2::createCoppockCurveStrategy);
        strategyCreators.put(STRATEGY_KNOW_SURE_THING, StrategyFactory2::createKnowSureThingStrategy);
        strategyCreators.put(STRATEGY_PRICE_OSCILLATOR, StrategyFactory2::createPriceOscillatorStrategy);
        strategyCreators.put(STRATEGY_DETRENDED_PRICE_OSCILLATOR, StrategyFactory2::createDetrendedPriceOscillatorStrategy);
        strategyCreators.put(STRATEGY_VERTICAL_HORIZONTAL_FILTER, StrategyFactory2::createVerticalHorizontalFilterStrategy);
        strategyCreators.put(STRATEGY_RAINBOW_OSCILLATOR, StrategyFactory2::createRainbowOscillatorStrategy);
        strategyCreators.put(STRATEGY_RELATIVE_MOMENTUM_INDEX, StrategyFactory2::createRelativeMomentumIndexStrategy);
        strategyCreators.put(STRATEGY_INTRADAY_MOMENTUM_INDEX, StrategyFactory2::createIntradayMomentumIndexStrategy);
        strategyCreators.put(STRATEGY_RANDOM_WALK_INDEX, StrategyFactory2::createRandomWalkIndexStrategy);

        // 注册 Batch 2 策略 (策略 51-90)
        // 动量反转策略 (51-60)
        strategyCreators.put(STRATEGY_RSI_REVERSAL, StrategyFactory3::createRSIReversalStrategy);
        strategyCreators.put(STRATEGY_WILLIAMS_R_REVERSAL, StrategyFactory3::createWilliamsRReversalStrategy);
        strategyCreators.put(STRATEGY_MOMENTUM_OSCILLATOR, StrategyFactory3::createMomentumOscillatorStrategy);
        strategyCreators.put(STRATEGY_ROC_DIVERGENCE, StrategyFactory3::createROCDivergenceStrategy);
        strategyCreators.put(STRATEGY_TRIX_SIGNAL, StrategyFactory3::createTRIXSignalStrategy);
        strategyCreators.put(STRATEGY_PARABOLIC_SAR_REVERSAL, StrategyFactory3::createParabolicSARReversalStrategy);
        strategyCreators.put(STRATEGY_ATR_BREAKOUT, StrategyFactory3::createATRBreakoutStrategy);
        strategyCreators.put(STRATEGY_DONCHIAN_BREAKOUT, StrategyFactory3::createDonchianBreakoutStrategy);
        strategyCreators.put(STRATEGY_KELTNER_BREAKOUT, StrategyFactory3::createKeltnerBreakoutStrategy);
        strategyCreators.put(STRATEGY_PRICE_CHANNEL, StrategyFactory3::createPriceChannelStrategy);

        // 成交量价格关系策略 (61-70)
        strategyCreators.put(STRATEGY_VWMA_CROSSOVER, StrategyFactory3::createVWMACrossoverStrategy);
        strategyCreators.put(STRATEGY_ACCUMULATION_DISTRIBUTION_DIVERGENCE, StrategyFactory3::createAccumulationDistributionDivergenceStrategy);
        strategyCreators.put(STRATEGY_OBV_DIVERGENCE, StrategyFactory3::createOBVDivergenceStrategy);
        strategyCreators.put(STRATEGY_PRICE_VOLUME_CONFIRMATION, StrategyFactory3::createPriceVolumeConfirmationStrategy);
        strategyCreators.put(STRATEGY_VOLUME_OSCILLATOR_SIGNAL, StrategyFactory3::createVolumeOscillatorSignalStrategy);
        strategyCreators.put(STRATEGY_POSITIVE_VOLUME_INDEX_SIGNAL, StrategyFactory3::createPositiveVolumeIndexSignalStrategy);
        strategyCreators.put(STRATEGY_NEGATIVE_VOLUME_INDEX_SIGNAL, StrategyFactory3::createNegativeVolumeIndexSignalStrategy);
        strategyCreators.put(STRATEGY_VOLUME_RSI, StrategyFactory3::createVolumeRSIStrategy);
        strategyCreators.put(STRATEGY_VOLUME_WEIGHTED_RSI_SIGNAL, StrategyFactory3::createVolumeWeightedRSISignalStrategy);
        strategyCreators.put(STRATEGY_VOLUME_BREAKOUT_CONFIRMATION, StrategyFactory3::createVolumeBreakoutConfirmationStrategy);

        // 波动率统计分析策略 (71-80)
        strategyCreators.put(STRATEGY_HISTORICAL_VOLATILITY, StrategyFactory3::createHistoricalVolatilityStrategy);
        strategyCreators.put(STRATEGY_STANDARD_DEVIATION_CHANNEL, StrategyFactory3::createStandardDeviationChannelStrategy);
        strategyCreators.put(STRATEGY_COEFFICIENT_OF_VARIATION, StrategyFactory3::createCoefficientOfVariationStrategy);
        strategyCreators.put(STRATEGY_SKEWNESS, StrategyFactory3::createSkewnessStrategy);
        strategyCreators.put(STRATEGY_KURTOSIS, StrategyFactory3::createKurtosisStrategy);
        strategyCreators.put(STRATEGY_Z_SCORE, StrategyFactory3::createZScoreStrategy);
        strategyCreators.put(STRATEGY_PERCENTILE, StrategyFactory3::createPercentileStrategy);
        strategyCreators.put(STRATEGY_LINEAR_REGRESSION, StrategyFactory3::createLinearRegressionStrategy);
        strategyCreators.put(STRATEGY_LINEAR_REGRESSION_SLOPE, StrategyFactory3::createLinearRegressionSlopeStrategy);
        strategyCreators.put(STRATEGY_R_SQUARED, StrategyFactory3::createRSquaredStrategy);

        // 复合指标和多重确认策略 (81-90)
        strategyCreators.put(STRATEGY_MULTIPLE_MA_CONFIRMATION, StrategyFactory3::createMultipleMAConfirmationStrategy);
        strategyCreators.put(STRATEGY_RSI_MACD_CONFIRMATION, StrategyFactory3::createRSIMACDConfirmationStrategy);
        strategyCreators.put(STRATEGY_BOLLINGER_RSI_COMBO, StrategyFactory3::createBollingerRSIComboStrategy);
        strategyCreators.put(STRATEGY_TRIPLE_INDICATOR_CONFIRMATION, StrategyFactory3::createTripleIndicatorConfirmationStrategy);
        strategyCreators.put(STRATEGY_MOMENTUM_BREAKOUT, StrategyFactory3::createMomentumBreakoutStrategy);
        strategyCreators.put(STRATEGY_VOLATILITY_BREAKOUT_SYSTEM, StrategyFactory3::createVolatilityBreakoutSystemStrategy);
        strategyCreators.put(STRATEGY_TREND_STRENGTH, StrategyFactory3::createTrendStrengthStrategy);
        strategyCreators.put(STRATEGY_SUPPORT_RESISTANCE_BREAKOUT, StrategyFactory3::createSupportResistanceBreakoutStrategy);
        strategyCreators.put(STRATEGY_PRICE_PATTERN_RECOGNITION, StrategyFactory3::createPricePatternRecognitionStrategy);
        strategyCreators.put(STRATEGY_COMPREHENSIVE_SCORING, StrategyFactory3::createComprehensiveScoringStrategy);


        // ================= 策略工厂4注册 (策略91-130) =================
        // 机器学习启发策略 (91-100)
        strategyCreators.put(STRATEGY_NEURAL_NETWORK, StrategyFactory4::createMultiIndicatorVotingStrategy);
        strategyCreators.put(STRATEGY_GENETIC_ALGORITHM, StrategyFactory4::createGeneticAlgorithmStrategy);
        strategyCreators.put(STRATEGY_RANDOM_FOREST, StrategyFactory4::createRandomForestStrategy);
        strategyCreators.put(STRATEGY_SVM, StrategyFactory4::createSVMStrategy);
        strategyCreators.put(STRATEGY_LSTM, StrategyFactory4::createLSTMStrategy);
        strategyCreators.put(STRATEGY_KNN, StrategyFactory4::createKNNStrategy);
        strategyCreators.put(STRATEGY_NAIVE_BAYES, StrategyFactory4::createNaiveBayesStrategy);
        strategyCreators.put(STRATEGY_DECISION_TREE, StrategyFactory4::createDecisionTreeStrategy);
        strategyCreators.put(STRATEGY_ENSEMBLE, StrategyFactory4::createEnsembleStrategy);
        strategyCreators.put(STRATEGY_REINFORCEMENT_LEARNING, StrategyFactory4::createReinforcementLearningStrategy);

        // 量化因子策略 (101-105)
        strategyCreators.put(STRATEGY_MOMENTUM_FACTOR, StrategyFactory4::createMomentumFactorStrategy);
        strategyCreators.put(STRATEGY_VALUE_FACTOR, StrategyFactory4::createValueFactorStrategy);
        strategyCreators.put(STRATEGY_QUALITY_FACTOR, StrategyFactory4::createQualityFactorStrategy);
        strategyCreators.put(STRATEGY_SIZE_FACTOR, StrategyFactory4::createSizeFactorStrategy);
        strategyCreators.put(STRATEGY_LOW_VOLATILITY_FACTOR, StrategyFactory4::createLowVolatilityFactorStrategy);

        // 高频和微观结构策略 (106-110)
        strategyCreators.put(STRATEGY_MICROSTRUCTURE_IMBALANCE, StrategyFactory4::createMicrostructureImbalanceStrategy);
        strategyCreators.put(STRATEGY_MEAN_REVERSION_INTRADAY, StrategyFactory4::createMeanReversionIntradayStrategy);
        strategyCreators.put(STRATEGY_MOMENTUM_INTRADAY, StrategyFactory4::createMomentumIntradayStrategy);
        strategyCreators.put(STRATEGY_ARBITRAGE_STATISTICAL, StrategyFactory4::createArbitrageStatisticalStrategy);
        strategyCreators.put(STRATEGY_PAIRS_TRADING, StrategyFactory4::createPairsTradingStrategy);

        // 期权和波动率策略 (111-115)
        strategyCreators.put(STRATEGY_VOLATILITY_SURFACE, StrategyFactory4::createVolatilitySurfaceStrategy);
        strategyCreators.put(STRATEGY_GAMMA_SCALPING, StrategyFactory4::createGammaScalpingStrategy);
        strategyCreators.put(STRATEGY_VOLATILITY_MEAN_REVERSION, StrategyFactory4::createVolatilityMeanReversionStrategy);
        strategyCreators.put(STRATEGY_VOLATILITY_MOMENTUM, StrategyFactory4::createVolatilityMomentumStrategy);
        strategyCreators.put(STRATEGY_IMPLIED_VOLATILITY_RANK, StrategyFactory4::createImpliedVolatilityRankStrategy);

        // 宏观和基本面策略 (116-120)
        strategyCreators.put(STRATEGY_CARRY_TRADE, StrategyFactory4::createCarryTradeStrategy);
        strategyCreators.put(STRATEGY_FUNDAMENTAL_SCORE, StrategyFactory4::createFundamentalScoreStrategy);
        strategyCreators.put(STRATEGY_MACRO_MOMENTUM, StrategyFactory4::createMacroMomentumStrategy);
        strategyCreators.put(STRATEGY_SEASONALITY, StrategyFactory4::createSeasonalityStrategy);
        strategyCreators.put(STRATEGY_CALENDAR_SPREAD, StrategyFactory4::createCalendarSpreadStrategy);

        // 创新和实验性策略 (121-125)
        strategyCreators.put(STRATEGY_SENTIMENT_ANALYSIS, StrategyFactory4::createSentimentAnalysisStrategy);
        strategyCreators.put(STRATEGY_NETWORK_ANALYSIS, StrategyFactory4::createNetworkAnalysisStrategy);
        strategyCreators.put(STRATEGY_FRACTAL_GEOMETRY, StrategyFactory4::createFractalGeometryStrategy);
        strategyCreators.put(STRATEGY_CHAOS_THEORY, StrategyFactory4::createChaosTheoryStrategy);
        strategyCreators.put(STRATEGY_QUANTUM_INSPIRED, StrategyFactory4::createQuantumInspiredStrategy);

        // 风险管理策略 (126-130)
        strategyCreators.put(STRATEGY_KELLY_CRITERION, StrategyFactory4::createKellyCriterionStrategy);
        strategyCreators.put(STRATEGY_VAR_RISK_MANAGEMENT, StrategyFactory4::createVarRiskManagementStrategy);
        strategyCreators.put(STRATEGY_MAXIMUM_DRAWDOWN_CONTROL, StrategyFactory4::createMaximumDrawdownControlStrategy);
        strategyCreators.put(STRATEGY_POSITION_SIZING, StrategyFactory4::createPositionSizingStrategy);
        strategyCreators.put(STRATEGY_CORRELATION_FILTER, StrategyFactory4::createCorrelationFilterStrategy);
    }

    /**
     * 统一添加移动止盈和止损规则
     *
     * @param exitRule
     * @param series
     * @return
     */
    public static Rule addExtraStopRule(Rule exitRule, BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        StopLossRule stopLossRule = new StopLossRule(closePrice, DecimalNum.valueOf(backtestParameterConfig.getStopLossPercent().doubleValue()));
        TrailingStopLossRule trailingStopLossRule = new TrailingStopLossRule(closePrice, DecimalNum.valueOf(backtestParameterConfig.getTrailingProfitPercent().doubleValue()));
        Rule finalExitRule = new OrRule(stopLossRule, trailingStopLossRule).or(exitRule);
        return finalExitRule;
    }
}
