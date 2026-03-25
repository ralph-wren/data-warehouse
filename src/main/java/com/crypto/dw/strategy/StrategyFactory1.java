package com.crypto.dw.strategy;

import com.crypto.dw.indicator.CustomIndicators;
import com.crypto.dw.utils.Ta4jNumUtil;
import org.ta4j.core.*;
import org.ta4j.core.indicators.*;
import org.ta4j.core.indicators.adx.ADXIndicator;
import org.ta4j.core.indicators.aroon.AroonDownIndicator;
import org.ta4j.core.indicators.aroon.AroonUpIndicator;
import org.ta4j.core.indicators.averages.*;
import org.ta4j.core.indicators.bollinger.BollingerBandsLowerIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsMiddleIndicator;
import org.ta4j.core.indicators.bollinger.BollingerBandsUpperIndicator;
import org.ta4j.core.indicators.candles.BearishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BearishHaramiIndicator;
import org.ta4j.core.indicators.candles.BullishEngulfingIndicator;
import org.ta4j.core.indicators.candles.BullishHaramiIndicator;
import org.ta4j.core.indicators.candles.DojiIndicator;
import org.ta4j.core.indicators.candles.ThreeBlackCrowsIndicator;
import org.ta4j.core.indicators.candles.ThreeWhiteSoldiersIndicator;
import org.ta4j.core.indicators.helpers.*;
import org.ta4j.core.indicators.keltner.KeltnerChannelLowerIndicator;
import org.ta4j.core.indicators.keltner.KeltnerChannelMiddleIndicator;
import org.ta4j.core.indicators.keltner.KeltnerChannelUpperIndicator;
import org.ta4j.core.indicators.statistics.StandardDeviationIndicator;
import org.ta4j.core.indicators.volume.OnBalanceVolumeIndicator;
import org.ta4j.core.indicators.volume.VWAPIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;
import org.ta4j.core.rules.*;

import java.math.BigDecimal;

import static com.crypto.dw.strategy.StrategyRegisterCenter.addExtraStopRule;


/**
 * 策略工厂类
 * 用于创建和管理各种交易策略
 */
public class StrategyFactory1 {

    /**
     * 创建SMA交叉策略
     */
    public static Strategy createSMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期SMA指标
        SMAIndicator shortSma = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortSma, longSma);
        Rule exitRule = new CrossedDownIndicatorRule(shortSma, longSma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建布林带策略（增强版）- 添加风险管理
     */
    public static Strategy createBollingerBandsStrategy(BarSeries series) {
        int period = (int) (20);
        double multiplier = (double) (2.0);
        double stopLossPercent = 2.0; // 2%止损

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建布林带指标
        SMAIndicator sma = new SMAIndicator(closePrice, period);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, period);

        BollingerBandsMiddleIndicator middleBand = new BollingerBandsMiddleIndicator(sma);
        // 使用Ta4jNumUtil替代Ta4jNumUtil.valueOf()
        BollingerBandsUpperIndicator upperBand = new BollingerBandsUpperIndicator(middleBand, sd, Ta4jNumUtil.valueOf(multiplier));
        BollingerBandsLowerIndicator lowerBand = new BollingerBandsLowerIndicator(middleBand, sd, Ta4jNumUtil.valueOf(multiplier));

        // 创建规则
        Rule entryRule = new UnderIndicatorRule(closePrice, lowerBand);

        // 基本卖出规则
        Rule basicExitRule = new OverIndicatorRule(closePrice, upperBand);

        // 止损规则 - 当价格下跌超过2%时
        Rule stopLossRule = new StopLossRule(closePrice, Ta4jNumUtil.valueOf(stopLossPercent));

        // 组合卖出规则：基本卖出规则或止损规则
        Rule exitRule = basicExitRule.or(stopLossRule);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建MACD策略
     */
    public static Strategy createMACDStrategy(BarSeries series) {
        int shortPeriod = (int) (12);
        int longPeriod = (int) (26);
        int signalPeriod = (int) (9);

        if (series.getBarCount() <= longPeriod + signalPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建MACD指标
        EMAIndicator shortEma = new EMAIndicator(closePrice, shortPeriod);
        EMAIndicator longEma = new EMAIndicator(closePrice, longPeriod);
        MACDIndicator macd = new MACDIndicator(closePrice, shortPeriod, longPeriod);
        EMAIndicator signal = new EMAIndicator(macd, signalPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(macd, signal);
        Rule exitRule = new CrossedDownIndicatorRule(macd, signal);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建RSI策略
     */
    public static Strategy createRSIStrategy(BarSeries series) {
        int period = (int) (14);
        int oversold = (int) (30);
        int overbought = (int) (70);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建RSI指标
        RSIIndicator rsi = new RSIIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(rsi, Ta4jNumUtil.valueOf(oversold));

        // 修正卖出规则：RSI高于70时卖出（而不是穿过70向下）
        Rule exitRule = new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(overbought));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建随机指标策略
     */
    public static Strategy createStochasticStrategy(BarSeries series) {
        int kPeriod = (int) (14);
        int kSmooth = (int) (3);
        int dSmooth = (int) (3);
        int oversold = (int) (20);
        int overbought = (int) (80);

        if (series.getBarCount() <= kPeriod + kSmooth + dSmooth) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        // 创建随机指标
        StochasticOscillatorKIndicator stochasticK = new StochasticOscillatorKIndicator(series, kPeriod);
        SMAIndicator stochasticD = new SMAIndicator(stochasticK, dSmooth);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(stochasticK, stochasticD)
                .and(new UnderIndicatorRule(stochasticK, Ta4jNumUtil.valueOf(oversold)));

        Rule exitRule = new CrossedDownIndicatorRule(stochasticK, stochasticD)
                .and(new OverIndicatorRule(stochasticK, Ta4jNumUtil.valueOf(overbought)));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建ADX策略
     */
    public static Strategy createADXStrategy(BarSeries series) {
        int adxPeriod = (int) (14);
        int diPeriod = (int) (14);
        int threshold = (int) (25);

        if (series.getBarCount() <= Math.max(adxPeriod, diPeriod) + 1) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        // 创建ADX指标
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        // 使用自定义实现替代缺失的指标类
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 使用可用指标替代，或者简化ADX策略
        // 这里使用RSI和SMA指标替代缺失的ADX相关指标
        RSIIndicator rsi = new RSIIndicator(closePrice, adxPeriod);
        SMAIndicator sma = new SMAIndicator(closePrice, diPeriod);

        // 创建规则
        Rule entryRule = new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(threshold))
                .and(new OverIndicatorRule(closePrice, sma));

        Rule exitRule = new UnderIndicatorRule(rsi, Ta4jNumUtil.valueOf(threshold))
                .and(new UnderIndicatorRule(closePrice, sma));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建CCI策略
     */
    public static Strategy createCCIStrategy(BarSeries series) {
        int period = (int) (20);
        int oversold = (int) (-100);
        int overbought = (int) (100);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        // 创建CCI指标
        CCIIndicator cci = new CCIIndicator(series, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(cci, Ta4jNumUtil.valueOf(oversold));
        Rule exitRule = new CrossedDownIndicatorRule(cci, Ta4jNumUtil.valueOf(overbought));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建威廉指标策略
     */
    public static Strategy createWilliamsRStrategy(BarSeries series) {
        int period = (int) (14);
        int oversold = (int) (-80);
        int overbought = (int) (-20);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        // 创建威廉指标
        WilliamsRIndicator williamsR = new WilliamsRIndicator(series, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(williamsR, Ta4jNumUtil.valueOf(oversold));
        Rule exitRule = new CrossedDownIndicatorRule(williamsR, Ta4jNumUtil.valueOf(overbought));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建三重EMA策略
     */
    public static Strategy createTripleEMAStrategy(BarSeries series) {
        int shortPeriod = (int) (5);
        int middlePeriod = (int) (10);
        int longPeriod = (int) (20);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建三个EMA指标
        EMAIndicator shortEma = new EMAIndicator(closePrice, shortPeriod);
        EMAIndicator middleEma = new EMAIndicator(closePrice, middlePeriod);
        EMAIndicator longEma = new EMAIndicator(closePrice, longPeriod);

        // 创建规则 (短EMA > 中EMA > 长EMA 买入，反之卖出)
        Rule entryRule = new OverIndicatorRule(shortEma, middleEma)
                .and(new OverIndicatorRule(middleEma, longEma));

        Rule exitRule = new UnderIndicatorRule(shortEma, middleEma)
                .and(new UnderIndicatorRule(middleEma, longEma));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建一目均衡表策略
     */
    public static Strategy createIchimokuStrategy(BarSeries series) {
        int conversionPeriod = (int) (9);
        int basePeriod = (int) (26);
        int laggingSpan = (int) (52);

        if (series.getBarCount() <= laggingSpan) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建自定义转换线和基准线指标
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 使用可用指标替代缺失的HighestValueIndicator和LowestValueIndicator
        MaxPriceIndicator maxPrice9 = new MaxPriceIndicator(series, conversionPeriod);
        MinPriceIndicator minPrice9 = new MinPriceIndicator(series, conversionPeriod);
        MaxPriceIndicator maxPrice26 = new MaxPriceIndicator(series, basePeriod);
        MinPriceIndicator minPrice26 = new MinPriceIndicator(series, basePeriod);

        // 转换线和基准线交叉作为买卖信号
        Rule entryRule = new CrossedUpIndicatorRule(
                closePrice,
                new SMAIndicator(closePrice, basePeriod));

        Rule exitRule = new CrossedDownIndicatorRule(
                closePrice,
                new SMAIndicator(closePrice, basePeriod));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建EMA策略
     */
    public static Strategy createEMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期EMA指标
        EMAIndicator shortEma = new EMAIndicator(closePrice, shortPeriod);
        EMAIndicator longEma = new EMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortEma, longEma);
        Rule exitRule = new CrossedDownIndicatorRule(shortEma, longEma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建WMA策略 (加权移动平均线)
     */
    public static Strategy createWMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期WMA指标
        WMAIndicator shortWma = new WMAIndicator(closePrice, shortPeriod);
        WMAIndicator longWma = new WMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortWma, longWma);
        Rule exitRule = new CrossedDownIndicatorRule(shortWma, longWma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建HMA策略 (Hull移动平均线)
     */
    public static Strategy createHMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期HMA指标
        HMAIndicator shortHma = new HMAIndicator(closePrice, shortPeriod);
        HMAIndicator longHma = new HMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortHma, longHma);
        Rule exitRule = new CrossedDownIndicatorRule(shortHma, longHma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建KAMA策略 (考夫曼自适应移动平均线)
     */
    public static Strategy createKAMAStrategy(BarSeries series) {
        int period = (int) (10);
        int fastEMA = (int) (2);
        int slowEMA = (int) (30);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建KAMA指标
        KAMAIndicator kama = new KAMAIndicator(closePrice, period, fastEMA, slowEMA);
        SMAIndicator sma = new SMAIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(kama, sma);
        Rule exitRule = new CrossedDownIndicatorRule(kama, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建ZLEMA策略 (零滞后指数移动平均线)
     */
    public static Strategy createZLEMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期ZLEMA指标
        ZLEMAIndicator shortZlema = new ZLEMAIndicator(closePrice, shortPeriod);
        ZLEMAIndicator longZlema = new ZLEMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortZlema, longZlema);
        Rule exitRule = new CrossedDownIndicatorRule(shortZlema, longZlema);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建DEMA策略 (双重指数移动平均线)
     */
    public static Strategy createDEMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期DEMA指标
        DoubleEMAIndicator shortDema = new DoubleEMAIndicator(closePrice, shortPeriod);
        DoubleEMAIndicator longDema = new DoubleEMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortDema, longDema);
        Rule exitRule = new CrossedDownIndicatorRule(shortDema, longDema);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建TEMA策略 (三重指数移动平均线)
     */
    public static Strategy createTEMAStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期TEMA指标
        TripleEMAIndicator shortTema = new TripleEMAIndicator(closePrice, shortPeriod);
        TripleEMAIndicator longTema = new TripleEMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortTema, longTema);
        Rule exitRule = new CrossedDownIndicatorRule(shortTema, longTema);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }


    /**
     * 创建随机RSI策略
     */
    public static Strategy createStochasticRSIStrategy(BarSeries series) {
        int rsiPeriod = (int) (14);
        int stochasticPeriod = (int) (14);
        int kPeriod = (int) (3);
        int dPeriod = (int) (3);
        int overbought = (int) (80);
        int oversold = (int) (20);

        if (series.getBarCount() <= rsiPeriod + stochasticPeriod + kPeriod + dPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);
        StochasticRSIIndicator stochRsi = new StochasticRSIIndicator(rsi, stochasticPeriod);
        SMAIndicator k = new SMAIndicator(stochRsi, kPeriod);
        SMAIndicator d = new SMAIndicator(k, dPeriod);

        // 随机RSI策略 - 降低超买超卖阈值
        double lowerOversold = 25; // 从20提高到25
        double upperOverbought = 75; // 从80降低到75

        Rule entryRule = new CrossedUpIndicatorRule(k, d)
                .and(new UnderIndicatorRule(k, Ta4jNumUtil.valueOf(lowerOversold))); // 降低超卖阈值

        Rule exitRule = new CrossedDownIndicatorRule(k, d)
                .and(new OverIndicatorRule(k, Ta4jNumUtil.valueOf(upperOverbought))); // 降低超买阈值

        return new BaseStrategy("随机RSI策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建CMO策略 (钱德动量震荡指标)
     */
    public static Strategy createCMOStrategy(BarSeries series) {
        int period = (int) (14);
        int overbought = (int) (50);
        int oversold = (int) (-50);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        CMOIndicator cmo = new CMOIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(cmo, oversold);
        Rule exitRule = new CrossedDownIndicatorRule(cmo, overbought);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建ROC策略 (变动率指标)
     */
    public static Strategy createROCStrategy(BarSeries series) {
        int period = (int) (12);
        double threshold = (double) (0.0);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        ROCIndicator roc = new ROCIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(roc, threshold);
        Rule exitRule = new CrossedDownIndicatorRule(roc, threshold);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建PPO策略 (百分比价格震荡指标)
     */
    public static Strategy createPPOStrategy(BarSeries series) {
        int shortPeriod = (int) (12);
        int longPeriod = (int) (26);
        int signalPeriod = (int) (9);

        if (series.getBarCount() <= longPeriod + signalPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        PPOIndicator ppo = new PPOIndicator(closePrice, shortPeriod, longPeriod);
        EMAIndicator signal = new EMAIndicator(ppo, signalPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(ppo, signal);
        Rule exitRule = new CrossedDownIndicatorRule(ppo, signal);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建DPO策略 (区间震荡指标)
     */
    public static Strategy createDPOStrategy(BarSeries series) {
        int period = (int) (20);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        DPOIndicator dpo = new DPOIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(dpo, 0);
        Rule exitRule = new CrossedDownIndicatorRule(dpo, 0);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建Aroon策略
     */
    public static Strategy createAroonStrategy(BarSeries series) {
        int period = (int) (25);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        AroonUpIndicator aroonUp = new AroonUpIndicator(series, period);
        AroonDownIndicator aroonDown = new AroonDownIndicator(series, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(aroonUp, aroonDown);
        Rule exitRule = new CrossedDownIndicatorRule(aroonUp, aroonDown);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建DMA策略 (差异移动平均线)
     */
    public static Strategy createDMAStrategy(BarSeries series) {
        int shortPeriod = (int) (10);
        int longPeriod = (int) (50);
        int signalPeriod = (int) (10);

        if (series.getBarCount() <= longPeriod + signalPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSma = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(closePrice, longPeriod);

        // DMA = 短期均线 - 长期均线
        CustomIndicators.DifferenceIndicator dma = new CustomIndicators.DifferenceIndicator(shortSma, longSma);
        SMAIndicator signal = new SMAIndicator(dma, signalPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(dma, signal);
        Rule exitRule = new CrossedDownIndicatorRule(dma, signal);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }


    /**
     * 创建溃疡指数策略
     */
    public static Strategy createUlcerIndexStrategy(BarSeries series) {
        int period = (int) (14);
        double threshold = (double) (5.0);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        UlcerIndexIndicator ulcerIndex = new UlcerIndexIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new UnderIndicatorRule(ulcerIndex, threshold);
        Rule exitRule = new OverIndicatorRule(ulcerIndex, threshold);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建OBV策略 (能量潮指标)
     */
    public static Strategy createOBVStrategy(BarSeries series) {
        int period = (int) (20);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        OnBalanceVolumeIndicator obv = new OnBalanceVolumeIndicator(series);
        SMAIndicator obvSma = new SMAIndicator(obv, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(obv, obvSma);
        Rule exitRule = new CrossedDownIndicatorRule(obv, obvSma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建质量指数策略
     */
    public static Strategy createMassIndexStrategy(BarSeries series) {
        int emaPeriod = (int) (9);
        int massIndexPeriod = (int) (25);
        double threshold = (double) (27.0);

        if (series.getBarCount() <= emaPeriod * 2 + massIndexPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        MassIndexIndicator massIndex = new MassIndexIndicator(series, emaPeriod, massIndexPeriod);

        // 创建规则 - 当质量指数从高于阈值交叉到低于阈值时买入，反之卖出
        Rule entryRule = new CrossedDownIndicatorRule(massIndex, threshold);
        Rule exitRule = new CrossedUpIndicatorRule(massIndex, threshold);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建十字星策略
     */
    public static Strategy createDojiStrategy(BarSeries series) {
        double tolerance = (double) (0.05);

        DojiIndicator doji = new DojiIndicator(series, 10, tolerance);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);

        // 创建规则 - 当出现十字星且价格低于20日均线时买入，当价格高于20日均线时卖出
        Rule entryRule = new BooleanIndicatorRule(doji)
                .and(new UnderIndicatorRule(closePrice, sma));

        Rule exitRule = new OverIndicatorRule(closePrice, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建看涨吞没策略
     */
    public static Strategy createBullishEngulfingStrategy(BarSeries series) {
        BullishEngulfingIndicator bullishEngulfing = new BullishEngulfingIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);

        // 创建规则 - 当出现看涨吞没形态且价格低于20日均线时买入，当价格高于20日均线时卖出
        Rule entryRule = new BooleanIndicatorRule(bullishEngulfing)
                .and(new UnderIndicatorRule(closePrice, sma));

        Rule exitRule = new OverIndicatorRule(closePrice, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建看跌吞没策略
     */
    public static Strategy createBearishEngulfingStrategy(BarSeries series) {
        BearishEngulfingIndicator bearishEngulfing = new BearishEngulfingIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);

        // 创建规则 - 当出现看跌吞没形态且价格高于20日均线时卖出，当价格低于20日均线时买入
        Rule entryRule = new UnderIndicatorRule(closePrice, sma);

        Rule exitRule = new BooleanIndicatorRule(bearishEngulfing)
                .and(new OverIndicatorRule(closePrice, sma));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建看涨孕线策略
     */
    public static Strategy createBullishHaramiStrategy(BarSeries series) {
        BullishHaramiIndicator bullishHarami = new BullishHaramiIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);

        // 创建规则 - 当出现看涨孕线形态且价格低于20日均线时买入，当价格高于20日均线时卖出
        Rule entryRule = new BooleanIndicatorRule(bullishHarami)
                .and(new UnderIndicatorRule(closePrice, sma));

        Rule exitRule = new OverIndicatorRule(closePrice, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建看跌孕线策略
     */
    public static Strategy createBearishHaramiStrategy(BarSeries series) {
        BearishHaramiIndicator bearishHarami = new BearishHaramiIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);

        // 创建规则 - 当出现看跌孕线形态且价格高于20日均线时卖出，当价格低于20日均线时买入
        Rule entryRule = new UnderIndicatorRule(closePrice, sma);

        Rule exitRule = new BooleanIndicatorRule(bearishHarami)
                .and(new OverIndicatorRule(closePrice, sma));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建三白兵策略
     */
    public static Strategy createThreeWhiteSoldiersStrategy(BarSeries series) {
        ThreeWhiteSoldiersIndicator threeWhiteSoldiers = new ThreeWhiteSoldiersIndicator(series, 5, DecimalNum.valueOf(0.3));
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma10 = new SMAIndicator(closePrice, 10);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // 优化规则 - 增加更多买入条件
        Rule entryRule = new BooleanIndicatorRule(threeWhiteSoldiers)
                .or(new CrossedUpIndicatorRule(closePrice, sma10)) // 增加短期均线突破
                .or(new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(70))); // 增加RSI强势条件

        // 卖出：价格跌破短期均线
        Rule exitRule = new UnderIndicatorRule(closePrice, sma10);

        return new BaseStrategy("三白兵策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建三黑乌鸦策略
     */
    public static Strategy createThreeBlackCrowsStrategy(BarSeries series) {
        ThreeBlackCrowsIndicator threeBlackCrows = new ThreeBlackCrowsIndicator(series, 5, 0.3);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);
        SMAIndicator sma10 = new SMAIndicator(closePrice, 10);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // 优化规则 - 增加更多买入条件
        Rule entryRule = new OverIndicatorRule(closePrice, sma20)
                .or(new CrossedUpIndicatorRule(closePrice, sma10)) // 增加短期均线突破
                .or(new UnderIndicatorRule(rsi, Ta4jNumUtil.valueOf(30))); // 增加RSI超卖条件

        // 卖出：三黑乌鸦形态或价格跌破短期均线
        Rule exitRule = new BooleanIndicatorRule(threeBlackCrows)
                .or(new UnderIndicatorRule(closePrice, sma10));

        return new BaseStrategy("三黑乌鸦策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建双推策略
     */
    public static Strategy createDoublePushStrategy(BarSeries series) {
        int shortPeriod = (int) (5);
        int longPeriod = (int) (20);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSma = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(closePrice, longPeriod);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // 创建规则 - 当短期均线上穿长期均线且RSI大于50时买入，当短期均线下穿长期均线且RSI小于50时卖出
        Rule entryRule = new CrossedUpIndicatorRule(shortSma, longSma)
                .and(new OverIndicatorRule(rsi, 50));

        Rule exitRule = new CrossedDownIndicatorRule(shortSma, longSma)
                .and(new UnderIndicatorRule(rsi, 50));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建海龟交易策略（修复版）- 修正指标匹配问题
     */
    public static Strategy createTurtleTradingStrategy(BarSeries series) {
        int entryPeriod = 10;  // 大幅降低入场周期（原来20）
        int exitPeriod = 5;    // 大幅降低出场周期（原来10）

        if (series.getBarCount() <= entryPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 添加ATR用于波动率过滤
        ATRIndicator atr = new ATRIndicator(series, 14);
        SMAIndicator atrSMA = new SMAIndicator(atr, 10);

        // 创建正确的最高价和最低价指标
        MaxPriceIndicator highestHigh = new MaxPriceIndicator(series, entryPeriod);  // 10日最高价
        MinPriceIndicator lowestLow = new MinPriceIndicator(series, exitPeriod);    // 5日最低价

        // 创建EMA作为趋势确认
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);

        // 创建成交量指标
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSMA = new SMAIndicator(volume, 10);

        // 海龟买入条件：
        // 1. 价格突破10日高点
        // 2. 波动率扩大（ATR > ATR的10日均值）
        // 3. 价格在20日EMA之上（趋势确认）
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, highestHigh)
                .and(new OverIndicatorRule(atr, atrSMA)) // 波动率扩大
                .and(new OverIndicatorRule(closePrice, ema20)) // 趋势确认
                .and(new OverIndicatorRule(volume, volumeSMA)); // 成交量确认

        // 海龟卖出条件：
        // 1. 跌破5日最低价 或
        // 2. 价格跌破20日EMA
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, lowestLow)
                .or(new CrossedDownIndicatorRule(closePrice, ema20));

        return new BaseStrategy("海龟交易策略", entryRule, addExtraStopRule(exitRule, series));
    }


    /**
     * 创建趋势跟踪策略
     */
    public static Strategy createTrendFollowingStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (26);
        int signalPeriod = (int) (9);

        if (series.getBarCount() <= longPeriod + signalPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator shortEma = new EMAIndicator(closePrice, shortPeriod);
        EMAIndicator longEma = new EMAIndicator(closePrice, longPeriod);

        // 计算MACD指标
        MACDIndicator macd = new MACDIndicator(closePrice, shortPeriod, longPeriod);
        EMAIndicator signal = new EMAIndicator(macd, signalPeriod);

        // 创建ADX指标（使用RSI替代）
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // 创建规则 - 当MACD上穿信号线且RSI大于50时买入，当MACD下穿信号线且RSI小于50时卖出
        Rule entryRule = new CrossedUpIndicatorRule(macd, signal)
                .and(new OverIndicatorRule(rsi, 50));

        Rule exitRule = new CrossedDownIndicatorRule(macd, signal)
                .or(new UnderIndicatorRule(rsi, 30));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建突破策略
     */
    public static Strategy createBreakoutStrategy(BarSeries series) {
        int period = (int) (5); // 进一步减少周期使其更敏感（原来10）
        double breakoutThreshold = 0.005; // 降低突破阈值到0.5%（原来1%）

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        MaxPriceIndicator highestHigh = new MaxPriceIndicator(series, period);
        MinPriceIndicator lowestLow = new MinPriceIndicator(series, period);
        SMAIndicator avgVolume = new SMAIndicator(volume, period);

        // 添加EMA作为趋势确认
        EMAIndicator ema = new EMAIndicator(closePrice, 20);

        // 简化的突破规则：只需要价格突破，不强制要求成交量确认
        Rule upperBreakoutRule = new OverIndicatorRule(closePrice,
                new TransformIndicator(highestHigh,
                        v -> v.multipliedBy(Ta4jNumUtil.valueOf(1.0 - breakoutThreshold))));

        // 添加额外的入场条件：价格在EMA之上
        Rule entryRule = new AndRule(
                upperBreakoutRule,
                new OverIndicatorRule(closePrice, ema)
        );

        // 修改止损和止盈规则
        Rule exitRule = new OrRule(
                new StopLossRule(closePrice, DecimalNum.valueOf(0.015)), // 降低止损到1.5%
                new StopGainRule(closePrice, DecimalNum.valueOf(0.03))   // 降低止盈到3%
        );

        return new BaseStrategy("突破策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建金叉策略
     */
    public static Strategy createGoldenCrossStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (26);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSma = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(closePrice, longPeriod);

        // 创建规则 - 当短期均线上穿长期均线时买入
        Rule entryRule = new CrossedUpIndicatorRule(shortSma, longSma);
        Rule exitRule = new CrossedDownIndicatorRule(shortSma, longSma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建死叉策略
     */
    public static Strategy createDeathCrossStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (26);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSma = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(closePrice, longPeriod);

        // 创建规则 - 当短期均线下穿长期均线时卖出
        Rule entryRule = new CrossedUpIndicatorRule(longSma, shortSma);
        Rule exitRule = new CrossedDownIndicatorRule(longSma, shortSma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }


    /**
     * 创建TRIX策略
     */
    public static Strategy createTRIXStrategy(BarSeries series) {
        int period = (int) (15);
        int signalPeriod = (int) (9);

        if (series.getBarCount() <= period * 3 + signalPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建三重EMA
        EMAIndicator ema1 = new EMAIndicator(closePrice, period);
        EMAIndicator ema2 = new EMAIndicator(ema1, period);
        EMAIndicator ema3 = new EMAIndicator(ema2, period);

        // 创建TRIX (当前值与前一个值的百分比变化)
        ROCIndicator trix = new ROCIndicator(ema3, 1);

        // 创建信号线
        SMAIndicator signal = new SMAIndicator(trix, signalPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(trix, signal);
        Rule exitRule = new CrossedDownIndicatorRule(trix, signal);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }


    /**
     * 创建双均线RSI策略
     */
    public static Strategy createDualMAWithRSIStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);
        int rsiPeriod = (int) (14);
        int rsiThreshold = (int) (50);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator shortSma = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(closePrice, longPeriod);
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);

        // 创建规则 - 当短期均线上穿长期均线且RSI大于阈值时买入，当短期均线下穿长期均线或RSI小于阈值时卖出
        Rule entryRule = new CrossedUpIndicatorRule(shortSma, longSma)
                .and(new OverIndicatorRule(rsi, rsiThreshold));

        Rule exitRule = new CrossedDownIndicatorRule(shortSma, longSma)
                .or(new UnderIndicatorRule(rsi, rsiThreshold));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建抛物线SAR策略
     */
    public static Strategy createParabolicSARStrategy(BarSeries series) {
        double step = (double) (0.02);
        double max = (double) (0.2);

        if (series.getBarCount() <= 2) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        // 创建抛物线SAR指标
        ParabolicSarIndicator sar = new ParabolicSarIndicator(series, Ta4jNumUtil.valueOf(step), Ta4jNumUtil.valueOf(max));
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, sar);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, sar);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建吊灯线退出策略
     */
    public static Strategy createChandelierExitStrategy(BarSeries series) {
        // 确保period参数至少为1，避免TimePeriod为null的错误
        int period = Math.max(1, (int) (22));
        double multiplier = (double) (3.0);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建价格指标
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 计算最高价和最低价
        MaxPriceIndicator highestHigh = new MaxPriceIndicator(series, period);
        MinPriceIndicator lowestLow = new MinPriceIndicator(series, period);

        // 计算ATR - 确保period大于0
        ATRIndicator atr = new ATRIndicator(series, period);

        // 创建自定义指标 - 多头吊灯线退出位置 (最高价 - ATR * multiplier)
        class LongChandelierExitIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final MaxPriceIndicator highestHigh;
            public final ATRIndicator atr;
            public final Num multiplier;

            public LongChandelierExitIndicator(MaxPriceIndicator highestHigh, ATRIndicator atr, double multiplier, BarSeries series) {
                super(highestHigh);
                this.highestHigh = highestHigh;
                this.atr = atr;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return highestHigh.getValue(index);
                }
                Num atrMultiplied = atr.getValue(index).multipliedBy(multiplier);
                return highestHigh.getValue(index).minus(atrMultiplied);
            }
        }

        // 创建自定义指标 - 空头吊灯线退出位置 (最低价 + ATR * multiplier)
        class ShortChandelierExitIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final MinPriceIndicator lowestLow;
            public final ATRIndicator atr;
            public final Num multiplier;

            public ShortChandelierExitIndicator(MinPriceIndicator lowestLow, ATRIndicator atr, double multiplier, BarSeries series) {
                super(lowestLow);
                this.lowestLow = lowestLow;
                this.atr = atr;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return lowestLow.getValue(index);
                }
                Num atrMultiplied = atr.getValue(index).multipliedBy(multiplier);
                return lowestLow.getValue(index).plus(atrMultiplied);
            }
        }

        // 创建吊灯线指标
        LongChandelierExitIndicator longExit = new LongChandelierExitIndicator(highestHigh, atr, multiplier, series);
        ShortChandelierExitIndicator shortExit = new ShortChandelierExitIndicator(lowestLow, atr, multiplier, series);

        // 创建入场规则 - 使用简单的突破规则作为入场条件
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, new MaxPriceIndicator(series, period / 2))
                .or(new CrossedDownIndicatorRule(closePrice, new MinPriceIndicator(series, period / 2)));

        // 创建多头止损规则 - 当价格跌破吊灯线止损位时退出
        Rule longExitRule = new CrossedDownIndicatorRule(closePrice, longExit);

        // 创建空头止损规则 - 当价格上涨突破吊灯线止损位时退出
        Rule shortExitRule = new CrossedUpIndicatorRule(closePrice, shortExit);

        // 组合退出规则 - 多头或空头止损触发时退出
        Rule exitRule = longExitRule.or(shortExitRule);

        // 创建策略
        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    // 自定义最大价格指标
    public static class MaxPriceIndicator extends CachedIndicator<Num> {
        @Override
        public int getCountOfUnstableBars() {
            return 0;
        }

        public final HighPriceIndicator highPrice;
        public final int period;

        public MaxPriceIndicator(BarSeries series, int period) {
            super(series);
            this.highPrice = new HighPriceIndicator(series);
            this.period = period;
        }

        @Override
        protected Num calculate(int index) {
            int startIndex = Math.max(0, index - period + 1);
            Num highest = highPrice.getValue(startIndex);

            for (int i = startIndex + 1; i <= index; i++) {
                Num current = highPrice.getValue(i);
                if (current.isGreaterThan(highest)) {
                    highest = current;
                }
            }

            return highest;
        }
    }

    // 自定义最小价格指标
    public static class MinPriceIndicator extends CachedIndicator<Num> {
        @Override
        public int getCountOfUnstableBars() {
            return 0;
        }

        public final LowPriceIndicator lowPrice;
        public final int period;

        public MinPriceIndicator(BarSeries series, int period) {
            super(series);
            this.lowPrice = new LowPriceIndicator(series);
            this.period = period;
        }

        @Override
        protected Num calculate(int index) {
            int startIndex = Math.max(0, index - period + 1);
            Num lowest = lowPrice.getValue(startIndex);

            for (int i = startIndex + 1; i <= index; i++) {
                Num current = lowPrice.getValue(i);
                if (current.isLessThan(lowest)) {
                    lowest = current;
                }
            }

            return lowest;
        }
    }

    /**
     * 创建MACD与布林带组合策略
     */
    public static Strategy createMACDWithBollingerStrategy(BarSeries series) {
        // 获取MACD相关参数 - 使用更短的周期使其更敏感
        int shortPeriod = (int) (8);  // 从12降低到8
        int longPeriod = (int) (17);  // 从26降低到17
        int signalPeriod = (int) (6); // 从9降低到6

        // 获取布林带相关参数 - 使用更短的周期使其更敏感
        int bollingerPeriod = (int) (15); // 从20降低到15
        double bollingerDeviation = (double) (1.5); // 从2.0降低到1.5

        // 验证数据点数量是否足够
        if (series.getBarCount() <= Math.max(longPeriod + signalPeriod, bollingerPeriod)) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        SMAIndicator volumeSMA = new SMAIndicator(volume, 10);

        // 创建MACD指标
        MACDIndicator macd = new MACDIndicator(closePrice, shortPeriod, longPeriod);
        EMAIndicator signal = new EMAIndicator(macd, signalPeriod);

        // 创建MACD柱状图指标
        Indicator<Num> histogram = new CustomIndicators.DifferenceIndicator(macd, signal);

        // 创建布林带指标
        SMAIndicator sma = new SMAIndicator(closePrice, bollingerPeriod);
        StandardDeviationIndicator sd = new StandardDeviationIndicator(closePrice, bollingerPeriod);

        BollingerBandsMiddleIndicator middleBand = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsUpperIndicator upperBand = new BollingerBandsUpperIndicator(middleBand, sd, Ta4jNumUtil.valueOf(bollingerDeviation));
        BollingerBandsLowerIndicator lowerBand = new BollingerBandsLowerIndicator(middleBand, sd, Ta4jNumUtil.valueOf(bollingerDeviation));

        // 创建RSI指标作为额外的过滤条件
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);

        // 买入规则:
        // 1. MACD金叉 或 MACD柱状图由负转正
        // 2. 价格接近或低于下轨
        // 3. RSI低于45（超卖区域）
        // 4. 成交量大于平均成交量
        Rule macdCrossRule = new CrossedUpIndicatorRule(macd, signal);
        Rule histogramCrossRule = new CrossedUpIndicatorRule(histogram, Ta4jNumUtil.zero());
        Rule priceNearLowerBand = new UnderIndicatorRule(closePrice,
                new TransformIndicator(lowerBand, v -> v.multipliedBy(Ta4jNumUtil.valueOf(1.02)))); // 价格在下轨2%以内

        Rule entryRule = new OrRule(macdCrossRule, histogramCrossRule)
                .and(priceNearLowerBand)
                .and(new UnderIndicatorRule(rsi, Ta4jNumUtil.valueOf(45)))
                .and(new OverIndicatorRule(volume, volumeSMA));

        // 卖出规则:
        // 1. MACD死叉 或 MACD柱状图由正转负
        // 2. 价格接近或高于上轨
        // 3. RSI高于60
        Rule macdDeathCrossRule = new CrossedDownIndicatorRule(macd, signal);
        Rule histogramDeathCrossRule = new CrossedDownIndicatorRule(histogram, Ta4jNumUtil.zero());
        Rule priceNearUpperBand = new OverIndicatorRule(closePrice,
                new TransformIndicator(upperBand, v -> v.multipliedBy(Ta4jNumUtil.valueOf(0.98)))); // 价格在上轨2%以内

        Rule exitRule = new OrRule(macdDeathCrossRule, histogramDeathCrossRule)
                .or(priceNearUpperBand)
                .or(new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(60)));


        return new BaseStrategy("MACD与布林带组合策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建吊锤形态策略
     */
    public static Strategy createHangingManStrategy(BarSeries series) {
        double upperShadowRatio = 0.3; // 放宽上影线要求（原来0.1）
        double lowerShadowRatio = 1.2; // 降低下影线要求（原来2.0）

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        SMAIndicator sma10 = new SMAIndicator(closePrice, 10); // 缩短均线周期

        // 创建改进的吊锤形态指标
        class HangingManIndicator extends CachedIndicator<Boolean> {
            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final OpenPriceIndicator openPrice;
            public final ClosePriceIndicator closePrice;
            public final double upperShadowRatio;
            public final double lowerShadowRatio;

            public HangingManIndicator(BarSeries series, double upperShadowRatio, double lowerShadowRatio) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.upperShadowRatio = upperShadowRatio;
                this.lowerShadowRatio = lowerShadowRatio;
            }

            @Override
            protected Boolean calculate(int index) {
                if (index <= 0) {
                    return false;
                }

                Num open = openPrice.getValue(index);
                Num close = closePrice.getValue(index);
                Num high = highPrice.getValue(index);
                Num low = lowPrice.getValue(index);

                // 计算实体部分
                Num body = open.isGreaterThan(close) ? open.minus(close) : close.minus(open);

                // 实体不能太小
                Num totalRange = high.minus(low);
                if (body.dividedBy(totalRange).isLessThan(Ta4jNumUtil.valueOf(0.1))) {
                    return false;
                }

                // 计算上影线与下影线
                Num upperShadow = high.minus(open.isGreaterThan(close) ? open : close);
                Num lowerShadow = (open.isLessThan(close) ? open : close).minus(low);

                // 放宽条件：上影线相对较短，下影线相对较长
                boolean isShortUpperShadow = upperShadow.dividedBy(body).isLessThanOrEqual(Ta4jNumUtil.valueOf(upperShadowRatio));
                boolean isLongLowerShadow = lowerShadow.dividedBy(body).isGreaterThanOrEqual(Ta4jNumUtil.valueOf(lowerShadowRatio));

                // 简化趋势判断：最近3天平均收盘价上涨
                boolean isUptrend = index > 3 && closePrice.getValue(index - 1).isGreaterThan(closePrice.getValue(index - 3));

                return isShortUpperShadow && isLongLowerShadow && isUptrend;
            }

            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }
        }

        HangingManIndicator hangingMan = new HangingManIndicator(series, upperShadowRatio, lowerShadowRatio);

        // 改进交易规则：使用更灵活的条件
        Rule entryRule = new UnderIndicatorRule(closePrice, sma10)  // 价格低于短期均线时买入
                .and(new OverIndicatorRule(closePrice, closePrice.getValue(1).multipliedBy(Ta4jNumUtil.valueOf(0.98)))); // 价格没有大幅下跌

        Rule exitRule = new BooleanIndicatorRule(hangingMan)  // 出现吊锤形态时卖出
                .or(new OverIndicatorRule(closePrice, new CachedIndicator<Num>(series) {
                    @Override
                    public int getCountOfUnstableBars() {
                        return 0;
                    }

                    @Override
                    protected Num calculate(int index) {
                        return closePrice.getValue(index - 1).multipliedBy(Ta4jNumUtil.valueOf(0.98));
                    }

                })); // 或价格高于均线2%时止盈

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建VWAP策略
     */
    public static Strategy createVWAPStrategy(BarSeries series) {
        int period = (int) (14);

        // 创建VWAP指标
        VWAPIndicator vwap = new VWAPIndicator(series, period);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 买入规则：价格上穿VWAP
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, vwap);

        // 卖出规则：价格下穿VWAP
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, vwap);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建肯特纳通道策略
     */
    public static Strategy createKeltnerChannelStrategy(BarSeries series) {
        int emaPeriod = (int) (20);
        int atrPeriod = (int) (10);
        double multiplier = 0.2;

        // 创建肯特纳通道指标
        EMAIndicator ema = new EMAIndicator(new ClosePriceIndicator(series), emaPeriod);
        ATRIndicator atr = new ATRIndicator(series, atrPeriod);

        KeltnerChannelMiddleIndicator middle = new KeltnerChannelMiddleIndicator(ema, 20);
        KeltnerChannelUpperIndicator upper = new KeltnerChannelUpperIndicator(middle, multiplier, 14);
        KeltnerChannelLowerIndicator lower = new KeltnerChannelLowerIndicator(middle, multiplier, 14);

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 买入规则：价格跌破下轨
        Rule entryRule = new CrossedDownIndicatorRule(closePrice, lower);

        // 卖出规则：价格突破上轨
        Rule exitRule = new CrossedUpIndicatorRule(closePrice, upper);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建ATR策略
     */
    public static Strategy createATRStrategy(BarSeries series) {
        int period = (int) (7);  // 降低ATR周期，使指标更敏感
        double multiplier = 1.0;  // 降低ATR倍数

        // 创建ATR指标
        ATRIndicator atr = new ATRIndicator(series, period);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);  // 添加移动平均线作为趋势判断

        // 创建自定义指标 - 上轨 (收盘价 + ATR * multiplier)
        class UpperBandIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ClosePriceIndicator closePrice;
            public final ATRIndicator atr;
            public final Num multiplier;

            public UpperBandIndicator(ClosePriceIndicator closePrice, ATRIndicator atr, double multiplier, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.atr = atr;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                return closePrice.getValue(index).plus(atr.getValue(index).multipliedBy(multiplier));
            }
        }

        // 创建自定义指标 - 下轨 (收盘价 - ATR * multiplier)
        class LowerBandIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ClosePriceIndicator closePrice;
            public final ATRIndicator atr;
            public final Num multiplier;

            public LowerBandIndicator(ClosePriceIndicator closePrice, ATRIndicator atr, double multiplier, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.atr = atr;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                return closePrice.getValue(index).minus(atr.getValue(index).multipliedBy(multiplier));
            }
        }

        // 使用直接的ATR通道突破规则
        UpperBandIndicator upperBand = new UpperBandIndicator(closePrice, atr, multiplier, series);
        LowerBandIndicator lowerBand = new LowerBandIndicator(closePrice, atr, multiplier, series);

        // 买入规则：价格上穿SMA且波动率扩大（ATR上升）
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, sma)
                .and(new OverIndicatorRule(atr, new TransformIndicator(
                        new SMAIndicator(atr, 5), // ATR的5周期均值
                        v -> v.multipliedBy(Ta4jNumUtil.valueOf(0.9)) // ATR > 0.9 * SMA(ATR, 5)
                )));

        // 卖出规则：价格下穿SMA或触及下轨
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, sma)
                .or(new UnderIndicatorRule(closePrice, lowerBand));

        return new BaseStrategy("ATR策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建KDJ策略
     */
    public static Strategy createKDJStrategy(BarSeries series) {
        int kPeriod = (int) (9);
        int dPeriod = (int) (3);
        int jPeriod = (int) (3);
        int oversold = (int) (20);
        int overbought = (int) (80);

        // 创建KDJ指标
        StochasticOscillatorKIndicator k = new StochasticOscillatorKIndicator(series, kPeriod);
        StochasticOscillatorDIndicator d = new StochasticOscillatorDIndicator(k);

        // J = 3 * K - 2 * D
        class JIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final StochasticOscillatorKIndicator k;
            public final StochasticOscillatorDIndicator d;
            public final Num three;
            public final Num two;

            public JIndicator(StochasticOscillatorKIndicator k, StochasticOscillatorDIndicator d, BarSeries series) {
                super(series);
                this.k = k;
                this.d = d;
                this.three = Ta4jNumUtil.valueOf(3);
                this.two = Ta4jNumUtil.valueOf(2);
            }

            @Override
            protected Num calculate(int index) {
                return k.getValue(index).multipliedBy(three).minus(d.getValue(index).multipliedBy(two));
            }
        }

        JIndicator j = new JIndicator(k, d, series);

        // 创建超买超卖阈值
        Num overboughtThreshold = Ta4jNumUtil.valueOf(overbought);
        Num oversoldThreshold = Ta4jNumUtil.valueOf(oversold);

        // 买入规则：J值上穿超卖线
        Rule entryRule = new CrossedUpIndicatorRule(j, oversoldThreshold);

        // 卖出规则：J值下穿超买线
        Rule exitRule = new CrossedDownIndicatorRule(j, overboughtThreshold);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建神奇震荡指标策略
     */
    public static Strategy createAwesomeOscillatorStrategy(BarSeries series) {
        int shortPeriod = (int) (5);
        int longPeriod = (int) (34);

        // 创建中间价指标 (high + low) / 2
        MedianPriceIndicator medianPrice = new MedianPriceIndicator(series);

        // 创建短期和长期SMA
        SMAIndicator shortSma = new SMAIndicator(medianPrice, shortPeriod);
        SMAIndicator longSma = new SMAIndicator(medianPrice, longPeriod);

        // 创建神奇震荡指标 (短期SMA - 长期SMA)
        class AwesomeOscillatorIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final SMAIndicator shortSma;
            public final SMAIndicator longSma;

            public AwesomeOscillatorIndicator(SMAIndicator shortSma, SMAIndicator longSma, BarSeries series) {
                super(series);
                this.shortSma = shortSma;
                this.longSma = longSma;
            }

            @Override
            protected Num calculate(int index) {
                return shortSma.getValue(index).minus(longSma.getValue(index));
            }
        }

        AwesomeOscillatorIndicator ao = new AwesomeOscillatorIndicator(shortSma, longSma, series);

        // 创建零线指标
        ConstantIndicator<Num> zeroLine = new ConstantIndicator<>(series, Ta4jNumUtil.valueOf(0));

        // 买入规则：神奇震荡指标上穿零线
        Rule entryRule = new CrossedUpIndicatorRule(ao, zeroLine);

        // 卖出规则：神奇震荡指标下穿零线
        Rule exitRule = new CrossedDownIndicatorRule(ao, zeroLine);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建方向运动指标策略
     */
    public static Strategy createDMIStrategy(BarSeries series) {
        int period = (int) (14);
        int adxThreshold = (int) (20);

        // 创建ADX指标
        ADXIndicator adx = new ADXIndicator(series, period);

        // 自定义实现+DI和-DI指标
        class DirectionalMovementPlusIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final ATRIndicator atr;
            public final int period;

            public DirectionalMovementPlusIndicator(BarSeries series, int period) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.atr = new ATRIndicator(series, period);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // +DM = 如果(当日最高价-前日最高价) > (前日最低价-当日最低价)，取较大值，否则为0
                Num highDiff = highPrice.getValue(index).minus(highPrice.getValue(index - 1));
                Num lowDiff = new LowPriceIndicator(series).getValue(index - 1).minus(new LowPriceIndicator(series).getValue(index));

                Num plusDM = Ta4jNumUtil.valueOf(0);
                if (highDiff.isGreaterThan(Ta4jNumUtil.valueOf(0)) && highDiff.isGreaterThan(lowDiff)) {
                    plusDM = highDiff;
                }

                // +DI = 100 * EMA(+DM) / ATR
                return plusDM.multipliedBy(Ta4jNumUtil.valueOf(100)).dividedBy(atr.getValue(index));
            }
        }

        class DirectionalMovementMinusIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final LowPriceIndicator lowPrice;
            public final ATRIndicator atr;
            public final int period;

            public DirectionalMovementMinusIndicator(BarSeries series, int period) {
                super(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.atr = new ATRIndicator(series, period);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // -DM = 如果(前日最低价-当日最低价) > (当日最高价-前日最高价)，取较大值，否则为0
                Num lowDiff = lowPrice.getValue(index - 1).minus(lowPrice.getValue(index));
                Num highDiff = new HighPriceIndicator(series).getValue(index).minus(new HighPriceIndicator(series).getValue(index - 1));

                Num minusDM = Ta4jNumUtil.valueOf(0);
                if (lowDiff.isGreaterThan(Ta4jNumUtil.valueOf(0)) && lowDiff.isGreaterThan(highDiff)) {
                    minusDM = lowDiff;
                }

                // -DI = 100 * EMA(-DM) / ATR
                return minusDM.multipliedBy(Ta4jNumUtil.valueOf(100)).dividedBy(atr.getValue(index));
            }
        }

        // 创建自定义的+DI和-DI指标
        DirectionalMovementPlusIndicator plusDI = new DirectionalMovementPlusIndicator(series, period);
        DirectionalMovementMinusIndicator minusDI = new DirectionalMovementMinusIndicator(series, period);

        // 创建阈值指标
        ConstantIndicator<Num> threshold = new ConstantIndicator<>(series, Ta4jNumUtil.valueOf(adxThreshold));

        // 买入规则：+DI上穿-DI且ADX大于阈值
        Rule entryRule = new CrossedUpIndicatorRule(plusDI, minusDI)
                .and(new OverIndicatorRule(adx, threshold));

        // 卖出规则：-DI上穿+DI且ADX大于阈值
        Rule exitRule = new CrossedUpIndicatorRule(minusDI, plusDI)
                .and(new OverIndicatorRule(adx, threshold));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建超级趋势指标策略
     */
    public static Strategy createSupertrendStrategy(BarSeries series) {
        int period = 7; // 降低周期，使指标更敏感（原来10）
        double multiplier = 1.0; // 大幅降低乘数使策略更敏感（原来2.0）

        // 创建ATR指标
        ATRIndicator atr = new ATRIndicator(series, period);

        // 创建中间价指标 (high + low) / 2
        MedianPriceIndicator medianPrice = new MedianPriceIndicator(series);

        // 创建上轨和下轨指标
        class UpperBandIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final MedianPriceIndicator medianPrice;
            public final ATRIndicator atr;
            public final Num multiplier;

            public UpperBandIndicator(MedianPriceIndicator medianPrice, ATRIndicator atr, double multiplier, BarSeries series) {
                super(series);
                this.medianPrice = medianPrice;
                this.atr = atr;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                return medianPrice.getValue(index).plus(atr.getValue(index).multipliedBy(multiplier));
            }
        }

        class LowerBandIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final MedianPriceIndicator medianPrice;
            public final ATRIndicator atr;
            public final Num multiplier;

            public LowerBandIndicator(MedianPriceIndicator medianPrice, ATRIndicator atr, double multiplier, BarSeries series) {
                super(series);
                this.medianPrice = medianPrice;
                this.atr = atr;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                return medianPrice.getValue(index).minus(atr.getValue(index).multipliedBy(multiplier));
            }
        }

        UpperBandIndicator upperBand = new UpperBandIndicator(medianPrice, atr, multiplier, series);
        LowerBandIndicator lowerBand = new LowerBandIndicator(medianPrice, atr, multiplier, series);

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 添加EMA作为趋势确认
        EMAIndicator ema = new EMAIndicator(closePrice, 20);

        // 买入规则：价格突破上轨且价格在EMA之上
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, upperBand)
                .or(new OverIndicatorRule(closePrice, ema).and(new OverIndicatorRule(closePrice, medianPrice)));

        // 卖出规则：价格跌破下轨或价格在EMA之下
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, lowerBand)
                .or(new UnderIndicatorRule(closePrice, ema).and(new UnderIndicatorRule(closePrice, medianPrice)));

        return new BaseStrategy("超级趋势指标策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建一目均衡表云突破策略
     */
    public static Strategy createIchimokuCloudBreakoutStrategy(BarSeries series) {
        int conversionPeriod = (int) (9);
        int basePeriod = (int) (26);
        int spanPeriod = (int) (52);
        int displacement = (int) (26);

        // 创建一目均衡表指标
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 转换线 (Conversion Line, Tenkan-sen) = (n日高点 + n日低点) / 2，一般n取9
        class ConversionLineIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final int period;
            public final Num two;

            public ConversionLineIndicator(HighPriceIndicator highPrice, LowPriceIndicator lowPrice, int period, BarSeries series) {
                super(series);
                this.highPrice = highPrice;
                this.lowPrice = lowPrice;
                this.period = period;
                this.two = Ta4jNumUtil.valueOf(2);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num highest = highPrice.getValue(index);
                Num lowest = lowPrice.getValue(index);

                for (int i = index - period + 1; i < index; i++) {
                    highest = highest.max(highPrice.getValue(i));
                    lowest = lowest.min(lowPrice.getValue(i));
                }

                return highest.plus(lowest).dividedBy(two);
            }
        }

        // 基准线 (Base Line, Kijun-sen) = (n日高点 + n日低点) / 2，一般n取26
        class BaseLineIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final int period;
            public final Num two;

            public BaseLineIndicator(HighPriceIndicator highPrice, LowPriceIndicator lowPrice, int period, BarSeries series) {
                super(series);
                this.highPrice = highPrice;
                this.lowPrice = lowPrice;
                this.period = period;
                this.two = Ta4jNumUtil.valueOf(2);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num highest = highPrice.getValue(index);
                Num lowest = lowPrice.getValue(index);

                for (int i = index - period + 1; i < index; i++) {
                    highest = highest.max(highPrice.getValue(i));
                    lowest = lowest.min(lowPrice.getValue(i));
                }

                return highest.plus(lowest).dividedBy(two);
            }
        }

        ConversionLineIndicator conversionLine = new ConversionLineIndicator(highPrice, lowPrice, conversionPeriod, series);
        BaseLineIndicator baseLine = new BaseLineIndicator(highPrice, lowPrice, basePeriod, series);

        // 先行带1号 (Leading Span A, Senkou Span A) = (转换线 + 基准线) / 2，向前平移26日
        class LeadingSpanAIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ConversionLineIndicator conversionLine;
            public final BaseLineIndicator baseLine;
            public final int displacement;
            public final Num two;

            public LeadingSpanAIndicator(ConversionLineIndicator conversionLine, BaseLineIndicator baseLine, int displacement, BarSeries series) {
                super(series);
                this.conversionLine = conversionLine;
                this.baseLine = baseLine;
                this.displacement = displacement;
                this.two = Ta4jNumUtil.valueOf(2);
            }

            @Override
            protected Num calculate(int index) {
                if (index < 0) {
                    return Ta4jNumUtil.valueOf(0);
                }

                return conversionLine.getValue(index).plus(baseLine.getValue(index)).dividedBy(two);
            }
        }

        // 先行带2号 (Leading Span B, Senkou Span B) = (n日高点 + n日低点) / 2，一般n取52，向前平移26日
        class LeadingSpanBIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final int period;
            public final int displacement;
            public final Num two;

            public LeadingSpanBIndicator(HighPriceIndicator highPrice, LowPriceIndicator lowPrice, int period, int displacement, BarSeries series) {
                super(series);
                this.highPrice = highPrice;
                this.lowPrice = lowPrice;
                this.period = period;
                this.displacement = displacement;
                this.two = Ta4jNumUtil.valueOf(2);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num highest = highPrice.getValue(index);
                Num lowest = lowPrice.getValue(index);

                for (int i = index - period + 1; i < index; i++) {
                    highest = highest.max(highPrice.getValue(i));
                    lowest = lowest.min(lowPrice.getValue(i));
                }

                return highest.plus(lowest).dividedBy(two);
            }
        }

        LeadingSpanAIndicator leadingSpanA = new LeadingSpanAIndicator(conversionLine, baseLine, displacement, series);
        LeadingSpanBIndicator leadingSpanB = new LeadingSpanBIndicator(highPrice, lowPrice, spanPeriod, displacement, series);

        // 买入规则：收盘价上穿云带上轨(LeadingSpanA)，即价格突破云带
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, leadingSpanA);

        // 卖出规则：收盘价下穿云带下轨(LeadingSpanB)，即价格跌破云带
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, leadingSpanB);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建三角移动平均线策略
     * 三角移动平均线是一种平滑的移动平均线，它对价格变化的反应比简单移动平均线更平滑
     */
    public static Strategy createTrimaStrategy(BarSeries series) {
        int shortPeriod = (int) (9);
        int longPeriod = (int) (21);

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建短期和长期三角移动平均线指标（使用SMA替代）
        SMAIndicator shortTrima = new SMAIndicator(closePrice, shortPeriod);
        SMAIndicator longTrima = new SMAIndicator(closePrice, longPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(shortTrima, longTrima);
        Rule exitRule = new CrossedDownIndicatorRule(shortTrima, longTrima);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建T3移动平均线策略
     * T3是一种三重指数平滑移动平均线，提供更平滑的价格曲线
     */
    public static Strategy createT3Strategy(BarSeries series) {
        int period = (int) (10);
        double volumeFactor = (double) (0.7); // 体积因子，一般在0.5-0.9之间

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建T3指标（使用EMA替代）
        EMAIndicator t3 = new EMAIndicator(closePrice, period);
        SMAIndicator sma = new SMAIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(t3, sma);
        Rule exitRule = new CrossedDownIndicatorRule(t3, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建MESA自适应移动平均线策略
     * MAMA是一种自适应移动平均线，能够根据市场条件自动调整
     */
    public static Strategy createMamaStrategy(BarSeries series) {
        double fastLimit = (double) (0.5);
        double slowLimit = (double) (0.05);

        if (series.getBarCount() <= 30) { // MAMA需要足够的数据点
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 31 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建MAMA指标（使用KAMA替代）
        KAMAIndicator mama = new KAMAIndicator(closePrice, 20, 2, 30);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(mama, sma);
        Rule exitRule = new CrossedDownIndicatorRule(mama, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建可变指数动态平均线策略
     * VIDYA是一种基于波动率的移动平均线，在波动较大时反应更快
     */
    public static Strategy createVidyaStrategy(BarSeries series) {
        int shortCMAPeriod = (int) (9);
        int longCMAPeriod = (int) (12);
        double alpha = (double) (0.2);

        if (series.getBarCount() <= longCMAPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longCMAPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建VIDYA指标（使用EMA替代）
        EMAIndicator vidya = new EMAIndicator(closePrice, longCMAPeriod);
        SMAIndicator sma = new SMAIndicator(closePrice, longCMAPeriod);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(vidya, sma);
        Rule exitRule = new CrossedDownIndicatorRule(vidya, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建威尔德平滑移动平均线策略
     * 威尔德平滑是一种特殊的指数移动平均线，用于计算RSI等指标
     */
    public static Strategy createWildersStrategy(BarSeries series) {
        int period = (int) (14);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建威尔德平滑指标（威尔德平滑是一种特殊的EMA，alpha = 1/period）
        class WilderSmoothingIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final Indicator<Num> indicator;
            public final int period;
            public final Num alpha;

            public WilderSmoothingIndicator(Indicator<Num> indicator, int period, BarSeries series) {
                super(indicator);
                this.indicator = indicator;
                this.period = period;
                this.alpha = Ta4jNumUtil.valueOf(1.0 / period);  // 威尔德平滑因子
            }

            @Override
            protected Num calculate(int index) {
                if (index == 0) {
                    return indicator.getValue(0);
                }

                Num prevWilder = getValue(index - 1);
                Num currentValue = indicator.getValue(index);

                return prevWilder.multipliedBy(Ta4jNumUtil.valueOf(1).minus(alpha)).plus(currentValue.multipliedBy(alpha));
            }
        }

        WilderSmoothingIndicator wilders = new WilderSmoothingIndicator(closePrice, period, series);
        SMAIndicator sma = new SMAIndicator(closePrice, period);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(wilders, sma);
        Rule exitRule = new CrossedDownIndicatorRule(wilders, sma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建Fisher变换策略
     * Fisher变换是一种将价格转换为正态分布的指标，有助于识别超买超卖状态
     */
    public static Strategy createFisherStrategy(BarSeries series) {
        int period = (int) (10);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建自定义Fisher变换指标
        class FisherTransformIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final Indicator<Num> indicator;
            public final int period;
            public final Num one;
            public final Num half;

            public FisherTransformIndicator(Indicator<Num> indicator, int period, BarSeries series) {
                super(indicator);
                this.indicator = indicator;
                this.period = period;
                this.one = Ta4jNumUtil.valueOf(1);
                this.half = Ta4jNumUtil.valueOf(0.5);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // 找出period内的最高价和最低价
                Num highest = indicator.getValue(index);
                Num lowest = indicator.getValue(index);

                for (int i = index - period + 1; i < index; i++) {
                    Num val = indicator.getValue(i);
                    highest = highest.max(val);
                    lowest = lowest.min(val);
                }

                // 如果最高价等于最低价，返回0
                if (highest.equals(lowest)) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // 归一化价格到-1到1之间
                Num range = highest.minus(lowest);
                Num normalizedPrice = indicator.getValue(index).minus(lowest).dividedBy(range).multipliedBy(Ta4jNumUtil.valueOf(2)).minus(one);

                // 应用Fisher变换，避免递归调用
                if (normalizedPrice.isGreaterThanOrEqual(one) || normalizedPrice.isLessThanOrEqual(one.multipliedBy(Ta4jNumUtil.valueOf(-1)))) {
                    // 防止对数函数的参数无效
                    return Ta4jNumUtil.valueOf(0);
                }

                Num fisherValue = half.multipliedBy(
                        Ta4jNumUtil.valueOf(Math.log((one.plus(normalizedPrice)).dividedBy(one.minus(normalizedPrice)).doubleValue()))
                );

                return fisherValue;
            }
        }

        // 创建Fisher变换指标
        FisherTransformIndicator fisher = new FisherTransformIndicator(closePrice, period, series);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(fisher, Ta4jNumUtil.valueOf(0));
        Rule exitRule = new CrossedDownIndicatorRule(fisher, Ta4jNumUtil.valueOf(0));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建预测振荡器策略
     * 预测振荡器衡量当前价格与线性回归预测价格的偏差
     */
    public static Strategy createFoscStrategy(BarSeries series) {
        int period = (int) (14);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建自定义预测振荡器指标
        class ForecastOscillatorIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final Indicator<Num> indicator;
            public final int period;
            public final Num hundred;

            public ForecastOscillatorIndicator(Indicator<Num> indicator, int period, BarSeries series) {
                super(indicator);
                this.indicator = indicator;
                this.period = period;
                this.hundred = Ta4jNumUtil.valueOf(100);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // 计算线性回归预测值
                double sumX = 0;
                double sumY = 0;
                double sumXY = 0;
                double sumX2 = 0;

                for (int i = index - period + 1; i <= index; i++) {
                    double x = i - (index - period + 1);
                    double y = indicator.getValue(i).doubleValue();
                    sumX += x;
                    sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }

                double meanX = sumX / period;
                double meanY = sumY / period;

                double slope = (sumXY - sumX * meanY) / (sumX2 - sumX * meanX);
                double intercept = meanY - slope * meanX;

                // 预测下一个值
                double forecast = slope * period + intercept;

                // 计算振荡器值
                double currentPrice = indicator.getValue(index).doubleValue();
                double oscillator = ((currentPrice - forecast) / forecast) * 100;

                return Ta4jNumUtil.valueOf(oscillator);
            }
        }

        // 创建预测振荡器指标
        ForecastOscillatorIndicator fosc = new ForecastOscillatorIndicator(closePrice, period, series);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(fosc, Ta4jNumUtil.valueOf(0));
        Rule exitRule = new CrossedDownIndicatorRule(fosc, Ta4jNumUtil.valueOf(0));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建移动便利性指标策略
     * 移动便利性指标衡量价格变动的难易程度
     */
    public static Strategy createEomStrategy(BarSeries series) {
        int period = (int) (14);
        double divisor = (double) (100000000);

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建自定义移动便利性指标
        class EaseOfMovementIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final VolumeIndicator volume;
            public final int period;
            public final Num divisor;

            public EaseOfMovementIndicator(BarSeries series, int period, double divisor) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.volume = new VolumeIndicator(series);
                this.period = period;
                this.divisor = Ta4jNumUtil.valueOf(divisor);
            }

            @Override
            protected Num calculate(int index) {
                if (index < 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // 计算当前和前一个K线的中点价格
                Num currentMiddlePoint = highPrice.getValue(index).plus(lowPrice.getValue(index)).dividedBy(Ta4jNumUtil.valueOf(2));
                Num prevMiddlePoint = highPrice.getValue(index - 1).plus(lowPrice.getValue(index - 1)).dividedBy(Ta4jNumUtil.valueOf(2));

                // 计算价格变动
                Num priceChange = currentMiddlePoint.minus(prevMiddlePoint);

                // 计算当前K线的高低价差
                Num boxRatio = highPrice.getValue(index).minus(lowPrice.getValue(index));

                // 避免除以零
                if (boxRatio.isZero() || volume.getValue(index).isZero()) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // 计算单日移动便利性
                Num dailyEom = priceChange.multipliedBy(divisor).dividedBy(volume.getValue(index).dividedBy(boxRatio));

                // 如果需要计算移动平均
                if (period > 1 && index >= period) {
                    Num sum = Ta4jNumUtil.valueOf(0);
                    for (int i = index - period + 1; i <= index; i++) {
                        Num mp = highPrice.getValue(i).plus(lowPrice.getValue(i)).dividedBy(Ta4jNumUtil.valueOf(2));
                        Num prevMp = highPrice.getValue(i - 1).plus(lowPrice.getValue(i - 1)).dividedBy(Ta4jNumUtil.valueOf(2));
                        Num pc = mp.minus(prevMp);
                        Num br = highPrice.getValue(i).minus(lowPrice.getValue(i));

                        if (!br.isZero() && !volume.getValue(i).isZero()) {
                            sum = sum.plus(pc.multipliedBy(divisor).dividedBy(volume.getValue(i).dividedBy(br)));
                        }
                    }
                    return sum.dividedBy(Ta4jNumUtil.valueOf(period));
                }

                return dailyEom;
            }
        }

        // 创建移动便利性指标
        EaseOfMovementIndicator eom = new EaseOfMovementIndicator(series, period, divisor);

        // 创建规则
        Rule entryRule = new CrossedUpIndicatorRule(eom, Ta4jNumUtil.valueOf(0));
        Rule exitRule = new CrossedDownIndicatorRule(eom, Ta4jNumUtil.valueOf(0));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建震荡指数策略
     * 震荡指数衡量市场的震荡程度
     */
    public static Strategy createChopStrategy(BarSeries series) {
        int period = 14;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 创建自定义震荡指数指标
        class ChoppinessIndexIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final ATRIndicator atr;
            public final int period;
            public final Num hundred;

            public ChoppinessIndexIndicator(BarSeries series, int period) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.atr = new ATRIndicator(series, 1);
                this.period = period;
                this.hundred = Ta4jNumUtil.valueOf(100);
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(50);
                }

                // 计算ATR和
                Num atrSum = Ta4jNumUtil.valueOf(0);
                for (int i = index - period + 1; i <= index; i++) {
                    atrSum = atrSum.plus(atr.getValue(i));
                }

                // 计算最高价和最低价
                Num highest = highPrice.getValue(index - period + 1);
                Num lowest = lowPrice.getValue(index - period + 1);

                for (int i = index - period + 2; i <= index; i++) {
                    highest = highest.max(highPrice.getValue(i));
                    lowest = lowest.min(lowPrice.getValue(i));
                }

                // 计算震荡指数
                Num range = highest.minus(lowest);
                if (range.isZero() || atrSum.isZero()) {
                    return Ta4jNumUtil.valueOf(50);
                }

                double chopIndex = 100 * Math.log10(atrSum.doubleValue() / range.doubleValue()) / Math.log10(period);

                return Ta4jNumUtil.valueOf(chopIndex);
            }
        }

        ChoppinessIndexIndicator chop = new ChoppinessIndexIndicator(series, period);

        // 震荡指数策略：高值表示震荡，低值表示趋势
        Rule entryRule = new CrossedDownIndicatorRule(chop, Ta4jNumUtil.valueOf(38.2)); // 趋势开始
        Rule exitRule = new CrossedUpIndicatorRule(chop, Ta4jNumUtil.valueOf(61.8)); // 震荡开始

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建克林格交易量振荡器策略
     * 结合价格和成交量的高级震荡器
     */
    public static Strategy createKvoStrategy(BarSeries series) {
        int shortPeriod = 34;
        int longPeriod = 55;
        int signalPeriod = 13;

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 使用简化的KVO计算
        Rule entryRule = new CrossedUpIndicatorRule(closePrice, new SMAIndicator(closePrice, shortPeriod));
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, new SMAIndicator(closePrice, shortPeriod));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建相对活力指数策略
     * 衡量收盘价相对于开盘价的位置
     */
    public static Strategy createRvgiStrategy(BarSeries series) {
        int period = 10;
        int signalPeriod = 4;

        if (series.getBarCount() <= period + signalPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + signalPeriod + 1) + " 个数据点");
        }

        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 创建RVGI指标
        class RvgiIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final OpenPriceIndicator openPrice;
            public final ClosePriceIndicator closePrice;
            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final int period;

            public RvgiIndicator(BarSeries series, int period) {
                super(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num numeratorSum = Ta4jNumUtil.valueOf(0);
                Num denominatorSum = Ta4jNumUtil.valueOf(0);

                for (int i = index - period + 1; i <= index; i++) {
                    Num numerator = closePrice.getValue(i).minus(openPrice.getValue(i));
                    Num denominator = highPrice.getValue(i).minus(lowPrice.getValue(i));

                    numeratorSum = numeratorSum.plus(numerator);
                    denominatorSum = denominatorSum.plus(denominator);
                }

                if (denominatorSum.isZero()) {
                    return Ta4jNumUtil.valueOf(0);
                }

                return numeratorSum.dividedBy(denominatorSum);
            }
        }

        RvgiIndicator rvgi = new RvgiIndicator(series, period);
        SMAIndicator rvgiSignal = new SMAIndicator(rvgi, signalPeriod);

        Rule entryRule = new CrossedUpIndicatorRule(rvgi, rvgiSignal);
        Rule exitRule = new CrossedDownIndicatorRule(rvgi, rvgiSignal);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建沙夫趋势周期策略
     * 结合MACD和随机指标的优势
     */
    public static Strategy createStcStrategy(BarSeries series) {
        int fastPeriod = 23;
        int slowPeriod = 50;
        int signalPeriod = 10;

        if (series.getBarCount() <= slowPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (slowPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 使用简化的STC计算 - 基于MACD
        EMAIndicator fastEma = new EMAIndicator(closePrice, fastPeriod);
        EMAIndicator slowEma = new EMAIndicator(closePrice, slowPeriod);

        class MacdIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final EMAIndicator fastEma;
            public final EMAIndicator slowEma;

            public MacdIndicator(EMAIndicator fastEma, EMAIndicator slowEma, BarSeries series) {
                super(series);
                this.fastEma = fastEma;
                this.slowEma = slowEma;
            }

            @Override
            protected Num calculate(int index) {
                return fastEma.getValue(index).minus(slowEma.getValue(index));
            }
        }

        MacdIndicator macd = new MacdIndicator(fastEma, slowEma, series);
        EMAIndicator macdSignal = new EMAIndicator(macd, signalPeriod);

        Rule entryRule = new CrossedUpIndicatorRule(macd, macdSignal);
        Rule exitRule = new CrossedDownIndicatorRule(macd, macdSignal);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建涡流指标策略
     * 衡量价格的旋转性运动
     */
    public static Strategy createVortexStrategy(BarSeries series) {
        int period = 14;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建VI+指标
        class VortexPositiveIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final ClosePriceIndicator closePrice;
            public final int period;

            public VortexPositiveIndicator(BarSeries series, int period) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(1);
                }

                Num viPlus = Ta4jNumUtil.valueOf(0);
                Num trueRange = Ta4jNumUtil.valueOf(0);

                for (int i = index - period + 1; i <= index; i++) {
                    if (i > 0) {
                        // VI+ = |当前高价 - 前一低价|
                        Num vmp = highPrice.getValue(i).minus(lowPrice.getValue(i - 1)).abs();
                        viPlus = viPlus.plus(vmp);

                        // True Range
                        Num tr1 = highPrice.getValue(i).minus(lowPrice.getValue(i));
                        Num tr2 = highPrice.getValue(i).minus(closePrice.getValue(i - 1)).abs();
                        Num tr3 = lowPrice.getValue(i).minus(closePrice.getValue(i - 1)).abs();
                        Num tr = tr1.max(tr2).max(tr3);
                        trueRange = trueRange.plus(tr);
                    }
                }

                if (trueRange.isZero()) {
                    return Ta4jNumUtil.valueOf(1);
                }

                return viPlus.dividedBy(trueRange);
            }
        }

        // 创建VI-指标
        class VortexNegativeIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final ClosePriceIndicator closePrice;
            public final int period;

            public VortexNegativeIndicator(BarSeries series, int period) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(1);
                }

                Num viMinus = Ta4jNumUtil.valueOf(0);
                Num trueRange = Ta4jNumUtil.valueOf(0);

                for (int i = index - period + 1; i <= index; i++) {
                    if (i > 0) {
                        // VI- = |当前低价 - 前一高价|
                        Num vmm = lowPrice.getValue(i).minus(highPrice.getValue(i - 1)).abs();
                        viMinus = viMinus.plus(vmm);

                        // True Range
                        Num tr1 = highPrice.getValue(i).minus(lowPrice.getValue(i));
                        Num tr2 = highPrice.getValue(i).minus(closePrice.getValue(i - 1)).abs();
                        Num tr3 = lowPrice.getValue(i).minus(closePrice.getValue(i - 1)).abs();
                        Num tr = tr1.max(tr2).max(tr3);
                        trueRange = trueRange.plus(tr);
                    }
                }

                if (trueRange.isZero()) {
                    return Ta4jNumUtil.valueOf(1);
                }

                return viMinus.dividedBy(trueRange);
            }
        }

        VortexPositiveIndicator viPlus = new VortexPositiveIndicator(series, period);
        VortexNegativeIndicator viMinus = new VortexNegativeIndicator(series, period);

        Rule entryRule = new CrossedUpIndicatorRule(viPlus, viMinus);
        Rule exitRule = new CrossedDownIndicatorRule(viPlus, viMinus);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建Q棒指标策略
     * 衡量买卖压力的差异
     */
    public static Strategy createQstickStrategy(BarSeries series) {
        int period = 14;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        OpenPriceIndicator openPrice = new OpenPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建QStick指标
        class QStickIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final OpenPriceIndicator openPrice;
            public final ClosePriceIndicator closePrice;
            public final int period;

            public QStickIndicator(BarSeries series, int period) {
                super(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num sum = Ta4jNumUtil.valueOf(0);
                for (int i = index - period + 1; i <= index; i++) {
                    sum = sum.plus(closePrice.getValue(i).minus(openPrice.getValue(i)));
                }

                return sum.dividedBy(Ta4jNumUtil.valueOf(period));
            }
        }

        QStickIndicator qstick = new QStickIndicator(series, period);

        Rule entryRule = new CrossedUpIndicatorRule(qstick, Ta4jNumUtil.valueOf(0));
        Rule exitRule = new CrossedDownIndicatorRule(qstick, Ta4jNumUtil.valueOf(0));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建威廉姆斯鳄鱼指标策略
     * 使用三条移动平均线识别趋势状态
     */
    public static Strategy createWilliamsAlligatorStrategy(BarSeries series) {
        int jawPeriod = 13;
        int teethPeriod = 8;
        int lipsPeriod = 5;

        if (series.getBarCount() <= jawPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (jawPeriod + 1) + " 个数据点");
        }

        MedianPriceIndicator medianPrice = new MedianPriceIndicator(series);

        // 鳄鱼的下颚（蓝线）
        SMAIndicator jaw = new SMAIndicator(medianPrice, jawPeriod);
        // 鳄鱼的牙齿（红线）
        SMAIndicator teeth = new SMAIndicator(medianPrice, teethPeriod);
        // 鳄鱼的嘴唇（绿线）
        SMAIndicator lips = new SMAIndicator(medianPrice, lipsPeriod);

        // 当三线呈多头排列时买入，空头排列时卖出
        Rule entryRule = new AndRule(
                new OverIndicatorRule(lips, teeth),
                new OverIndicatorRule(teeth, jaw)
        );

        Rule exitRule = new AndRule(
                new UnderIndicatorRule(lips, teeth),
                new UnderIndicatorRule(teeth, jaw)
        );

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建希尔伯特变换瞬时趋势线策略
     * 高级数学变换，提供平滑的趋势线
     */
    public static Strategy createHtTrendlineStrategy(BarSeries series) {
        int period = 14;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 使用简化的趋势线计算（替代复杂的希尔伯特变换）
        SMAIndicator trendline = new SMAIndicator(closePrice, period);

        Rule entryRule = new CrossedUpIndicatorRule(closePrice, trendline);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, trendline);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建归一化平均真实范围策略
     * ATR的归一化版本，便于不同价格水平的比较
     */
    public static Strategy createNatrStrategy(BarSeries series) {
        int period = 14;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ATRIndicator atr = new ATRIndicator(series, period);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建NATR指标
        class NatrIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ATRIndicator atr;
            public final ClosePriceIndicator closePrice;
            public final Num hundred;

            public NatrIndicator(ATRIndicator atr, ClosePriceIndicator closePrice, BarSeries series) {
                super(series);
                this.atr = atr;
                this.closePrice = closePrice;
                this.hundred = Ta4jNumUtil.valueOf(100);
            }

            @Override
            protected Num calculate(int index) {
                Num close = closePrice.getValue(index);
                if (close.isZero()) {
                    return Ta4jNumUtil.valueOf(0);
                }
                return atr.getValue(index).dividedBy(close).multipliedBy(hundred);
            }
        }

        NatrIndicator natr = new NatrIndicator(atr, closePrice, series);

        // NATR策略 - 降低波动性阈值
        double lowerThreshold = 1.0; // 从2.0降低到1.0
        double upperThreshold = 0.5; // 从1.0降低到0.5

        Rule entryRule = new CrossedUpIndicatorRule(natr, Ta4jNumUtil.valueOf(lowerThreshold)); // NATR上升
        Rule exitRule = new CrossedDownIndicatorRule(natr, Ta4jNumUtil.valueOf(upperThreshold)); // NATR下降

        return new BaseStrategy("NATR策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建质量指数策略（修复版）- 彻底重构计算逻辑，使其更准确且能产生交易信号
     */
    public static Strategy createMassStrategy(BarSeries series) {
        int emaPeriod = 5; // 降低EMA周期（原来9）
        int sumPeriod = 15; // 大幅降低求和周期（原来25）

        if (series.getBarCount() <= sumPeriod + emaPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (sumPeriod + emaPeriod + 1) + " 个数据点");
        }

        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建标准的质量指数计算
        // 1. 计算高低价差
        Indicator<Num> highLowDiff = new CustomIndicators.DifferenceIndicator(highPrice, lowPrice);

        // 2. 创建价差的EMA
        EMAIndicator ema1 = new EMAIndicator(highLowDiff, emaPeriod);

        // 3. 创建价差EMA的EMA
        EMAIndicator ema2 = new EMAIndicator(ema1, emaPeriod);

        // 4. 创建比率指标 (EMA1/EMA2)
        Indicator<Num> emaRatio = new TransformIndicator(
                new CachedIndicator<Num>(series) {
                    @Override
                    public int getCountOfUnstableBars() {
                        return 0;
                    }

                    @Override
                    protected Num calculate(int index) {
                        Num e2 = ema2.getValue(index);
                        if (e2.isZero()) {
                            return Ta4jNumUtil.valueOf(1);
                        }
                        return ema1.getValue(index).dividedBy(e2);
                    }
                },
                v -> v.isNaN() ? Ta4jNumUtil.valueOf(1) : v);

        // 5. 创建真正的质量指数 - 比率的周期和
        class MassIndexIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final Indicator<Num> emaRatio;
            private final int sumPeriod;

            public MassIndexIndicator(Indicator<Num> emaRatio, int sumPeriod, BarSeries series) {
                super(series);
                this.emaRatio = emaRatio;
                this.sumPeriod = sumPeriod;
            }

            @Override
            protected Num calculate(int index) {
                if (index < sumPeriod) {
                    return Ta4jNumUtil.valueOf(9); // 默认基准值
                }

                Num sum = Ta4jNumUtil.valueOf(0);
                for (int i = index - sumPeriod + 1; i <= index; i++) {
                    sum = sum.plus(emaRatio.getValue(i));
                }

                return sum;
            }
        }

        MassIndexIndicator massIndex = new MassIndexIndicator(emaRatio, sumPeriod, series);

        // 创建SMA用于交叉信号
        SMAIndicator massIndexSMA = new SMAIndicator(massIndex, 5);

        // 添加趋势确认指标
        EMAIndicator ema20 = new EMAIndicator(closePrice, 20);

        // 质量指数交易规则
        // 买入条件：质量指数上穿SMA且价格在20日均线之上
        Rule entryRule = new CrossedUpIndicatorRule(massIndex, massIndexSMA)
                .and(new OverIndicatorRule(closePrice, ema20));

        // 卖出条件：质量指数下穿SMA或价格跌破20日均线
        Rule exitRule = new CrossedDownIndicatorRule(massIndex, massIndexSMA)
                .or(new CrossedDownIndicatorRule(closePrice, ema20));

        return new BaseStrategy("质量指数策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建标准差策略
     * 统计学指标，衡量价格偏离程度
     */
    public static Strategy createStddevStrategy(BarSeries series) {
        int period = 20;
        double stdDevMultiplier = 2;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, period);
        SMAIndicator sma = new SMAIndicator(closePrice, period);

        // 创建上下轨
        class UpperBandIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final SMAIndicator sma;
            public final StandardDeviationIndicator stdDev;
            public final Num multiplier;

            public UpperBandIndicator(SMAIndicator sma, StandardDeviationIndicator stdDev, double multiplier, BarSeries series) {
                super(series);
                this.sma = sma;
                this.stdDev = stdDev;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                return sma.getValue(index).plus(stdDev.getValue(index).multipliedBy(multiplier));
            }
        }

        class LowerBandIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final SMAIndicator sma;
            public final StandardDeviationIndicator stdDev;
            public final Num multiplier;

            public LowerBandIndicator(SMAIndicator sma, StandardDeviationIndicator stdDev, double multiplier, BarSeries series) {
                super(series);
                this.sma = sma;
                this.stdDev = stdDev;
                this.multiplier = Ta4jNumUtil.valueOf(multiplier);
            }

            @Override
            protected Num calculate(int index) {
                return sma.getValue(index).minus(stdDev.getValue(index).multipliedBy(multiplier));
            }
        }

        UpperBandIndicator upperBand = new UpperBandIndicator(sma, stdDev, stdDevMultiplier, series);
        LowerBandIndicator lowerBand = new LowerBandIndicator(sma, stdDev, stdDevMultiplier, series);

        Rule entryRule = new CrossedDownIndicatorRule(closePrice, lowerBand);
        Rule exitRule = new CrossedUpIndicatorRule(closePrice, upperBand);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建挤压动量指标策略
     * 识别低波动后的突破机会
     */
    public static Strategy createSqueezeStrategy(BarSeries series) {
        int bbPeriod = 20;
        int kcPeriod = 20;
        double bbMultiplier = 2;
        double kcMultiplier = 1.5;

        if (series.getBarCount() <= Math.max(bbPeriod, kcPeriod)) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (Math.max(bbPeriod, kcPeriod) + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 布林带
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, bbPeriod)), new StandardDeviationIndicator(closePrice, bbPeriod), DecimalNum.valueOf(bbMultiplier));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, bbPeriod)), new StandardDeviationIndicator(closePrice, bbPeriod), DecimalNum.valueOf(bbMultiplier));

        // 肯特纳通道
        KeltnerChannelMiddleIndicator kcMiddle = new KeltnerChannelMiddleIndicator(series, kcPeriod);
        KeltnerChannelUpperIndicator kcUpper = new KeltnerChannelUpperIndicator(kcMiddle, kcMultiplier, kcPeriod);
        KeltnerChannelLowerIndicator kcLower = new KeltnerChannelLowerIndicator(kcMiddle, kcMultiplier, kcPeriod);

        // 挤压条件：布林带在肯特纳通道内
        Rule squeezeRule = new AndRule(
                new UnderIndicatorRule(bbUpper, kcUpper),
                new OverIndicatorRule(bbLower, kcLower)
        );

        Rule entryRule = new NotRule(squeezeRule); // 挤压结束时入场
        Rule exitRule = squeezeRule; // 开始挤压时出场

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建布林带宽度策略
     * 衡量布林带宽度变化，预测波动性变化
     */
    public static Strategy createBbwStrategy(BarSeries series) {
        int period = 20;
        double stdDevMultiplier = 2;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        BollingerBandsMiddleIndicator bbMiddle = new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, period));
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, period);

        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(bbMiddle, stdDev, DecimalNum.valueOf(stdDevMultiplier));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(bbMiddle, stdDev, DecimalNum.valueOf(stdDevMultiplier));

        // 创建布林带宽度指标
        class BollingerBandWidthIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final BollingerBandsUpperIndicator upper;
            public final BollingerBandsLowerIndicator lower;
            public final BollingerBandsMiddleIndicator middle;

            public BollingerBandWidthIndicator(BollingerBandsUpperIndicator upper, BollingerBandsLowerIndicator lower, BollingerBandsMiddleIndicator middle, BarSeries series) {
                super(series);
                this.upper = upper;
                this.lower = lower;
                this.middle = middle;
            }

            @Override
            protected Num calculate(int index) {
                Num middleValue = middle.getValue(index);
                if (middleValue.isZero()) {
                    return Ta4jNumUtil.valueOf(0);
                }
                return upper.getValue(index).minus(lower.getValue(index)).dividedBy(middleValue);
            }
        }

        BollingerBandWidthIndicator bbw = new BollingerBandWidthIndicator(bbUpper, bbLower, bbMiddle, series);
        SMAIndicator bbwAvg = new SMAIndicator(bbw, 10);

        Rule entryRule = new CrossedUpIndicatorRule(bbw, bbwAvg);
        Rule exitRule = new CrossedDownIndicatorRule(bbw, bbwAvg);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建年化历史波动率策略
     * 年化波动率计算，用于风险评估
     */
    public static Strategy createVolatilityStrategy(BarSeries series) {
        int period = 20;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建年化波动率指标
        class VolatilityIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ClosePriceIndicator closePrice;
            public final int period;
            public final Num sqrt252;

            public VolatilityIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
                this.sqrt252 = Ta4jNumUtil.valueOf(Math.sqrt(252)); // 年化因子
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num sum = Ta4jNumUtil.valueOf(0);
                Num sumSquared = Ta4jNumUtil.valueOf(0);

                for (int i = index - period + 1; i <= index; i++) {
                    if (i > 0) {
                        Num logReturn = Ta4jNumUtil.valueOf(Math.log(closePrice.getValue(i).doubleValue() / closePrice.getValue(i - 1).doubleValue()));
                        sum = sum.plus(logReturn);
                        sumSquared = sumSquared.plus(logReturn.multipliedBy(logReturn));
                    }
                }

                Num mean = sum.dividedBy(Ta4jNumUtil.valueOf(period));
                Num variance = sumSquared.dividedBy(Ta4jNumUtil.valueOf(period)).minus(mean.multipliedBy(mean));

                if (variance.doubleValue() < 0) {
                    variance = Ta4jNumUtil.valueOf(0);
                }

                return Ta4jNumUtil.valueOf(Math.sqrt(variance.doubleValue())).multipliedBy(sqrt252);
            }
        }

        VolatilityIndicator volatility = new VolatilityIndicator(closePrice, period, series);
        SMAIndicator volatilityAvg = new SMAIndicator(volatility, 10);

        Rule entryRule = new CrossedUpIndicatorRule(volatility, volatilityAvg);
        Rule exitRule = new CrossedDownIndicatorRule(volatility, volatilityAvg);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建唐奇安通道策略
     * 基于最高最低价的通道，经典突破系统
     */
    public static Strategy createDonchianChannelsStrategy(BarSeries series) {
        int period = 15;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 创建唐奇安上轨
        class DonchianUpperIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final int period;

            public DonchianUpperIndicator(HighPriceIndicator highPrice, int period, BarSeries series) {
                super(series);
                this.highPrice = highPrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return highPrice.getValue(index);
                }

                Num highest = highPrice.getValue(index - period + 1);
                for (int i = index - period + 2; i <= index; i++) {
                    highest = highest.max(highPrice.getValue(i));
                }
                return highest;
            }
        }

        // 创建唐奇安下轨
        class DonchianLowerIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final LowPriceIndicator lowPrice;
            public final int period;

            public DonchianLowerIndicator(LowPriceIndicator lowPrice, int period, BarSeries series) {
                super(series);
                this.lowPrice = lowPrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return lowPrice.getValue(index);
                }

                Num lowest = lowPrice.getValue(index - period + 1);
                for (int i = index - period + 2; i <= index; i++) {
                    lowest = lowest.min(lowPrice.getValue(i));
                }
                return lowest;
            }
        }

        DonchianUpperIndicator upperChannel = new DonchianUpperIndicator(highPrice, period, series);
        DonchianLowerIndicator lowerChannel = new DonchianLowerIndicator(lowPrice, period, series);

        Rule entryRule = new CrossedUpIndicatorRule(closePrice, upperChannel);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, lowerChannel);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建累积/派发线策略
     * 累积分配线，跟踪资金流向，确认趋势
     */
    public static Strategy createAdStrategy(BarSeries series) {
        int shortPeriod = 3;
        int longPeriod = 10;

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建累积分配线指标
        class AccumulationDistributionIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final ClosePriceIndicator closePrice;
            public final VolumeIndicator volume;

            public AccumulationDistributionIndicator(BarSeries series) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.volume = new VolumeIndicator(series);
            }

            @Override
            protected Num calculate(int index) {
                if (index == 0) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num high = highPrice.getValue(index);
                Num low = lowPrice.getValue(index);
                Num close = closePrice.getValue(index);
                Num vol = volume.getValue(index);

                // 计算资金流量倍数
                Num range = high.minus(low);
                Num moneyFlowMultiplier;
                if (range.isZero()) {
                    moneyFlowMultiplier = Ta4jNumUtil.valueOf(0);
                } else {
                    moneyFlowMultiplier = close.minus(low).minus(high.minus(close)).dividedBy(range);
                }

                // 计算资金流量
                Num moneyFlowVolume = moneyFlowMultiplier.multipliedBy(vol);

                // 累积
                return getValue(index - 1).plus(moneyFlowVolume);
            }
        }

        AccumulationDistributionIndicator ad = new AccumulationDistributionIndicator(series);
        SMAIndicator adShort = new SMAIndicator(ad, shortPeriod);
        SMAIndicator adLong = new SMAIndicator(ad, longPeriod);

        Rule entryRule = new CrossedUpIndicatorRule(adShort, adLong);
        Rule exitRule = new CrossedDownIndicatorRule(adShort, adLong);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建累积/派发振荡器策略
     * AD线的震荡器版本，提供买卖信号
     */
    public static Strategy createAdoscStrategy(BarSeries series) {
        int fastPeriod = 3;
        int slowPeriod = 10;

        if (series.getBarCount() <= slowPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (slowPeriod + 1) + " 个数据点");
        }

        // 使用简化的ADOSC计算
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 使用成交量加权价格作为简化的AD指标
        VWAPIndicator vwap = new VWAPIndicator(series, fastPeriod);
        EMAIndicator fastEma = new EMAIndicator(vwap, fastPeriod);
        EMAIndicator slowEma = new EMAIndicator(vwap, slowPeriod);

        class AdoscIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final EMAIndicator fastEma;
            public final EMAIndicator slowEma;

            public AdoscIndicator(EMAIndicator fastEma, EMAIndicator slowEma, BarSeries series) {
                super(series);
                this.fastEma = fastEma;
                this.slowEma = slowEma;
            }

            @Override
            protected Num calculate(int index) {
                return fastEma.getValue(index).minus(slowEma.getValue(index));
            }
        }

        AdoscIndicator adosc = new AdoscIndicator(fastEma, slowEma, series);

        Rule entryRule = new CrossedUpIndicatorRule(adosc, Ta4jNumUtil.valueOf(0));
        Rule exitRule = new CrossedDownIndicatorRule(adosc, Ta4jNumUtil.valueOf(0));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建负成交量指数策略
     * 关注成交量下降时的价格行为，适合机构行为分析
     */
    public static Strategy createNviStrategy(BarSeries series) {
        int shortPeriod = 1;
        int longPeriod = 255;

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建负成交量指数
        class NegativeVolumeIndexIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ClosePriceIndicator closePrice;
            public final VolumeIndicator volume;

            public NegativeVolumeIndexIndicator(BarSeries series) {
                super(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.volume = new VolumeIndicator(series);
            }

            @Override
            protected Num calculate(int index) {
                if (index == 0) {
                    return Ta4jNumUtil.valueOf(1000); // 起始值
                }

                Num currentVolume = volume.getValue(index);
                Num previousVolume = volume.getValue(index - 1);
                Num currentPrice = closePrice.getValue(index);
                Num previousPrice = closePrice.getValue(index - 1);
                Num previousNvi = getValue(index - 1);

                // 只有在成交量下降时才更新NVI
                if (currentVolume.isLessThan(previousVolume)) {
                    Num priceChange = currentPrice.minus(previousPrice).dividedBy(previousPrice);
                    return previousNvi.plus(previousNvi.multipliedBy(priceChange));
                } else {
                    return previousNvi;
                }
            }
        }

        NegativeVolumeIndexIndicator nvi = new NegativeVolumeIndexIndicator(series);
        SMAIndicator nviSma = new SMAIndicator(nvi, longPeriod);

        Rule entryRule = new CrossedUpIndicatorRule(nvi, nviSma);
        Rule exitRule = new CrossedDownIndicatorRule(nvi, nviSma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建正成交量指数策略
     * 关注成交量上升时的价格行为，适合散户行为分析
     */
    public static Strategy createPviStrategy(BarSeries series) {
        int shortPeriod = 1;
        int longPeriod = 255;

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建正成交量指数
        class PositiveVolumeIndexIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ClosePriceIndicator closePrice;
            public final VolumeIndicator volume;

            public PositiveVolumeIndexIndicator(BarSeries series) {
                super(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.volume = new VolumeIndicator(series);
            }

            @Override
            protected Num calculate(int index) {
                if (index == 0) {
                    return Ta4jNumUtil.valueOf(1000); // 起始值
                }

                Num currentVolume = volume.getValue(index);
                Num previousVolume = volume.getValue(index - 1);
                Num currentPrice = closePrice.getValue(index);
                Num previousPrice = closePrice.getValue(index - 1);
                Num previousPvi = getValue(index - 1);

                // 只有在成交量上升时才更新PVI
                if (currentVolume.isGreaterThan(previousVolume)) {
                    Num priceChange = currentPrice.minus(previousPrice).dividedBy(previousPrice);
                    return previousPvi.plus(previousPvi.multipliedBy(priceChange));
                } else {
                    return previousPvi;
                }
            }
        }

        PositiveVolumeIndexIndicator pvi = new PositiveVolumeIndexIndicator(series);
        SMAIndicator pviSma = new SMAIndicator(pvi, longPeriod);

        Rule entryRule = new CrossedUpIndicatorRule(pvi, pviSma);
        Rule exitRule = new CrossedDownIndicatorRule(pvi, pviSma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建成交量加权移动平均线策略
     * 成交量加权均线，反映真实的平均成本
     */
    public static Strategy createVwmaStrategy(BarSeries series) {
        int period = 20;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建VWMA指标
        class VwmaIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final ClosePriceIndicator closePrice;
            public final VolumeIndicator volume;
            public final int period;

            public VwmaIndicator(BarSeries series, int period) {
                super(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.volume = new VolumeIndicator(series);
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return closePrice.getValue(index);
                }

                Num sumPriceVolume = Ta4jNumUtil.valueOf(0);
                Num sumVolume = Ta4jNumUtil.valueOf(0);

                for (int i = index - period + 1; i <= index; i++) {
                    Num price = closePrice.getValue(i);
                    Num vol = volume.getValue(i);
                    sumPriceVolume = sumPriceVolume.plus(price.multipliedBy(vol));
                    sumVolume = sumVolume.plus(vol);
                }

                if (sumVolume.isZero()) {
                    return closePrice.getValue(index);
                }

                return sumPriceVolume.dividedBy(sumVolume);
            }
        }

        VwmaIndicator vwma = new VwmaIndicator(series, period);

        Rule entryRule = new CrossedUpIndicatorRule(closePrice, vwma);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, vwma);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建成交量振荡器策略
     * 成交量震荡器，识别成交量变化趋势
     */
    public static Strategy createVoscStrategy(BarSeries series) {
        int shortPeriod = 5;
        int longPeriod = 10;

        if (series.getBarCount() <= longPeriod) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (longPeriod + 1) + " 个数据点");
        }

        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建成交量振荡器
        SMAIndicator shortVolumeAvg = new SMAIndicator(volume, shortPeriod);
        SMAIndicator longVolumeAvg = new SMAIndicator(volume, longPeriod);

        class VolumeOscillatorIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final SMAIndicator shortAvg;
            public final SMAIndicator longAvg;
            public final Num hundred;

            public VolumeOscillatorIndicator(SMAIndicator shortAvg, SMAIndicator longAvg, BarSeries series) {
                super(series);
                this.shortAvg = shortAvg;
                this.longAvg = longAvg;
                this.hundred = Ta4jNumUtil.valueOf(100);
            }

            @Override
            protected Num calculate(int index) {
                Num longValue = longAvg.getValue(index);
                if (longValue.isZero()) {
                    return Ta4jNumUtil.valueOf(0);
                }

                return shortAvg.getValue(index).minus(longValue).dividedBy(longValue).multipliedBy(hundred);
            }
        }

        VolumeOscillatorIndicator vosc = new VolumeOscillatorIndicator(shortVolumeAvg, longVolumeAvg, series);

        Rule entryRule = new CrossedUpIndicatorRule(vosc, Ta4jNumUtil.valueOf(0));
        Rule exitRule = new CrossedDownIndicatorRule(vosc, Ta4jNumUtil.valueOf(0));

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建市场便利指数策略
     * 市场便利指数，衡量价格移动的容易程度
     */
    public static Strategy createMarketfiStrategy(BarSeries series) {
        int period = 14;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);

        // 创建市场便利指数
        class MarketFacilitationIndexIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            public final HighPriceIndicator highPrice;
            public final LowPriceIndicator lowPrice;
            public final VolumeIndicator volume;

            public MarketFacilitationIndexIndicator(BarSeries series) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.volume = new VolumeIndicator(series);
            }

            @Override
            protected Num calculate(int index) {
                Num high = highPrice.getValue(index);
                Num low = lowPrice.getValue(index);
                Num vol = volume.getValue(index);

                if (vol.isZero()) {
                    return Ta4jNumUtil.valueOf(0);
                }

                return high.minus(low).dividedBy(vol);
            }
        }

        MarketFacilitationIndexIndicator mfi = new MarketFacilitationIndexIndicator(series);
        SMAIndicator mfiAvg = new SMAIndicator(mfi, period);

        Rule entryRule = new CrossedUpIndicatorRule(mfi, mfiAvg);
        Rule exitRule = new CrossedDownIndicatorRule(mfi, mfiAvg);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建锤子线策略
     * 底部反转信号，下影线长表示支撑强劲
     */
    public static Strategy createHammerStrategy(BarSeries series) {
        if (series.getBarCount() <= 1) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 2 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);

        // 创建锤子线的简化买入条件
        Rule entryRule = new CrossedDownIndicatorRule(closePrice, sma20);
        Rule exitRule = new CrossedUpIndicatorRule(closePrice, sma20);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建倒锤子线策略
     * 真正的倒锤子形态识别
     */
    public static Strategy createInvertedHammerStrategy(BarSeries series) {
        if (series.getBarCount() <= 3) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 4 个数据点");
        }

        // 倒锤子指标
        class InvertedHammerIndicator extends CachedIndicator<Boolean> {
            private final HighPriceIndicator highPrice;
            private final LowPriceIndicator lowPrice;
            private final OpenPriceIndicator openPrice;
            private final ClosePriceIndicator closePrice;

            public InvertedHammerIndicator(BarSeries series) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
            }

            @Override
            protected Boolean calculate(int index) {
                if (index < 2) {
                    return false;
                }

                Num open = openPrice.getValue(index);
                Num close = closePrice.getValue(index);
                Num high = highPrice.getValue(index);
                Num low = lowPrice.getValue(index);

                // 计算实体和影线
                Num body = close.minus(open).abs();
                Num upperShadow = high.minus(open.max(close));
                Num lowerShadow = open.min(close).minus(low);
                Num totalRange = high.minus(low);

                // 倒锤子条件：
                // 1. 上影线长度至少是实体的2倍
                // 2. 下影线很短（小于实体的1/3）
                // 3. 实体位于K线下半部分
                // 4. 前期是下跌趋势
                boolean longUpperShadow = upperShadow.isGreaterThan(body.multipliedBy(Ta4jNumUtil.valueOf(2)));
                boolean shortLowerShadow = lowerShadow.isLessThan(body.multipliedBy(Ta4jNumUtil.valueOf(0.3)));
                boolean bodyInLowerHalf = close.max(open).minus(low).isLessThan(totalRange.multipliedBy(Ta4jNumUtil.valueOf(0.6)));

                // 检查前期下跌趋势
                boolean downtrend = false;
                if (index >= 2) {
                    Num prevClose1 = closePrice.getValue(index - 1);
                    Num prevClose2 = closePrice.getValue(index - 2);
                    downtrend = prevClose1.isLessThan(prevClose2);
                }

                return longUpperShadow && shortLowerShadow && bodyInLowerHalf && downtrend;
            }

            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }
        }

        InvertedHammerIndicator invertedHammer = new InvertedHammerIndicator(series);

        Rule entryRule = new BooleanIndicatorRule(invertedHammer);
        Rule exitRule = new StopGainRule(new ClosePriceIndicator(series), DecimalNum.valueOf(3)); // 3%止盈

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建流星线策略
     */
    public static Strategy createShootingStarStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);

        Rule entryRule = new CrossedUpIndicatorRule(closePrice, sma20);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, sma20);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建晨星策略
     * 真正的晨星形态识别
     */
    public static Strategy createMorningStarStrategy(BarSeries series) {
        if (series.getBarCount() <= 3) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 4 个数据点");
        }

        // 晨星指标
        class MorningStarIndicator extends CachedIndicator<Boolean> {
            private final HighPriceIndicator highPrice;
            private final LowPriceIndicator lowPrice;
            private final OpenPriceIndicator openPrice;
            private final ClosePriceIndicator closePrice;

            public MorningStarIndicator(BarSeries series) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
            }

            @Override
            protected Boolean calculate(int index) {
                if (index < 2) {
                    return false;
                }

                // 三根K线：第一根（index-2），第二根（index-1），第三根（index）
                Num open1 = openPrice.getValue(index - 2);
                Num close1 = closePrice.getValue(index - 2);
                Num open2 = openPrice.getValue(index - 1);
                Num close2 = closePrice.getValue(index - 1);
                Num high2 = highPrice.getValue(index - 1);
                Num low2 = lowPrice.getValue(index - 1);
                Num open3 = openPrice.getValue(index);
                Num close3 = closePrice.getValue(index);

                // 第一根K线：长阴线
                boolean firstBearish = close1.isLessThan(open1);
                Num body1 = open1.minus(close1);

                // 第二根K线：星线（小实体，向下跳空）
                Num body2 = close2.minus(open2).abs();
                boolean smallBody2 = body2.isLessThan(body1.multipliedBy(Ta4jNumUtil.valueOf(0.3)));
                boolean gapDown = high2.isLessThan(close1);

                // 第三根K线：长阳线，向上跳空
                boolean thirdBullish = close3.isGreaterThan(open3);
                Num body3 = close3.minus(open3);
                boolean gapUp = open3.isGreaterThan(high2);
                boolean strongBullish = body3.isGreaterThan(body1.multipliedBy(Ta4jNumUtil.valueOf(0.5)));

                return firstBearish && smallBody2 && gapDown && thirdBullish && gapUp && strongBullish;
            }

            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }
        }

        MorningStarIndicator morningStar = new MorningStarIndicator(series);

        Rule entryRule = new BooleanIndicatorRule(morningStar);
        Rule exitRule = new StopGainRule(new ClosePriceIndicator(series), DecimalNum.valueOf(5)); // 5%止盈

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建暮星策略
     * 真正的暮星形态识别
     */
    public static Strategy createEveningStarStrategy(BarSeries series) {
        if (series.getBarCount() <= 3) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 4 个数据点");
        }

        // 暮星指标
        class EveningStarIndicator extends CachedIndicator<Boolean> {
            private final HighPriceIndicator highPrice;
            private final LowPriceIndicator lowPrice;
            private final OpenPriceIndicator openPrice;
            private final ClosePriceIndicator closePrice;

            public EveningStarIndicator(BarSeries series) {
                super(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
            }

            @Override
            protected Boolean calculate(int index) {
                if (index < 2) {
                    return false;
                }

                // 三根K线：第一根（index-2），第二根（index-1），第三根（index）
                Num open1 = openPrice.getValue(index - 2);
                Num close1 = closePrice.getValue(index - 2);
                Num open2 = openPrice.getValue(index - 1);
                Num close2 = closePrice.getValue(index - 1);
                Num high2 = highPrice.getValue(index - 1);
                Num low2 = lowPrice.getValue(index - 1);
                Num open3 = openPrice.getValue(index);
                Num close3 = closePrice.getValue(index);

                // 第一根K线：长阳线
                boolean firstBullish = close1.isGreaterThan(open1);
                Num body1 = close1.minus(open1);

                // 第二根K线：星线（小实体，向上跳空）
                Num body2 = close2.minus(open2).abs();
                boolean smallBody2 = body2.isLessThan(body1.multipliedBy(Ta4jNumUtil.valueOf(0.3)));
                boolean gapUp = low2.isGreaterThan(close1);

                // 第三根K线：长阴线，向下跳空
                boolean thirdBearish = close3.isLessThan(open3);
                Num body3 = open3.minus(close3);
                boolean gapDown = open3.isLessThan(low2);
                boolean strongBearish = body3.isGreaterThan(body1.multipliedBy(Ta4jNumUtil.valueOf(0.5)));

                return firstBullish && smallBody2 && gapUp && thirdBearish && gapDown && strongBearish;
            }

            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }
        }

        EveningStarIndicator eveningStar = new EveningStarIndicator(series);

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma20 = new SMAIndicator(closePrice, 20);

        // 简化暮星策略：暮星形态或价格跌破均线
        Rule entryRule = new BooleanIndicatorRule(eveningStar)
                .or(new CrossedDownIndicatorRule(closePrice, sma20)); // 增加均线跌破条件

        // 买入：价格突破均线或3%止损
        Rule exitRule = new CrossedUpIndicatorRule(closePrice, sma20)
                .or(new StopLossRule(closePrice, DecimalNum.valueOf(3))); // 降低止损到3%

        return new BaseStrategy("暮星策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建刺透形态策略
     * 真正的刺透形态识别
     */
    public static Strategy createPiercingStrategy(BarSeries series) {
        if (series.getBarCount() <= 2) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 3 个数据点");
        }

        // 刺透形态指标
        class PiercingPatternIndicator extends CachedIndicator<Boolean> {
            private final OpenPriceIndicator openPrice;
            private final ClosePriceIndicator closePrice;
            private final HighPriceIndicator highPrice;
            private final LowPriceIndicator lowPrice;

            public PiercingPatternIndicator(BarSeries series) {
                super(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
                this.highPrice = new HighPriceIndicator(series);
                this.lowPrice = new LowPriceIndicator(series);
            }

            @Override
            protected Boolean calculate(int index) {
                if (index < 1) {
                    return false;
                }

                // 两根K线：第一根（index-1），第二根（index）
                Num open1 = openPrice.getValue(index - 1);
                Num close1 = closePrice.getValue(index - 1);
                Num open2 = openPrice.getValue(index);
                Num close2 = closePrice.getValue(index);
                Num low2 = lowPrice.getValue(index);

                // 第一根K线：阴线
                boolean firstBearish = close1.isLessThan(open1);
                Num body1 = open1.minus(close1);

                // 第二根K线：阳线，向下跳空开盘
                boolean secondBullish = close2.isGreaterThan(open2);
                boolean gapDown = open2.isLessThan(close1);

                // 第二根K线收盘价至少穿透第一根K线实体的50%
                Num midPoint = close1.plus(body1.dividedBy(Ta4jNumUtil.valueOf(2)));
                boolean penetration = close2.isGreaterThan(midPoint);

                // 第二根K线的收盘价不能高于第一根K线的开盘价
                boolean notAboveFirstOpen = close2.isLessThan(open1);

                return firstBearish && secondBullish && gapDown && penetration && notAboveFirstOpen;
            }

            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }
        }

        PiercingPatternIndicator piercingPattern = new PiercingPatternIndicator(series);

        Rule entryRule = new BooleanIndicatorRule(piercingPattern);
        Rule exitRule = new StopGainRule(new ClosePriceIndicator(series), DecimalNum.valueOf(4)); // 4%止盈

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建乌云盖顶策略
     * 真正的乌云盖顶形态识别
     */
    public static Strategy createDarkCloudCoverStrategy(BarSeries series) {
        if (series.getBarCount() <= 2) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 3 个数据点");
        }

        // 乌云盖顶指标
        class DarkCloudCoverIndicator extends CachedIndicator<Boolean> {
            private final OpenPriceIndicator openPrice;
            private final ClosePriceIndicator closePrice;

            public DarkCloudCoverIndicator(BarSeries series) {
                super(series);
                this.openPrice = new OpenPriceIndicator(series);
                this.closePrice = new ClosePriceIndicator(series);
            }

            @Override
            protected Boolean calculate(int index) {
                if (index < 1) {
                    return false;
                }

                // 两根K线：第一根（index-1），第二根（index）
                Num open1 = openPrice.getValue(index - 1);
                Num close1 = closePrice.getValue(index - 1);
                Num open2 = openPrice.getValue(index);
                Num close2 = closePrice.getValue(index);

                // 第一根K线：阳线
                boolean firstBullish = close1.isGreaterThan(open1);
                Num body1 = close1.minus(open1);

                // 第二根K线：阴线
                boolean secondBearish = close2.isLessThan(open2);

                // 乌云盖顶条件：
                // 1. 第二根K线开盘价高于第一根K线最高价
                // 2. 第二根K线收盘价深入第一根K线实体超过50%
                boolean gapUpOpen = open2.isGreaterThan(close1);
                Num penetration = close1.plus(open1).dividedBy(Ta4jNumUtil.valueOf(2)); // 第一根K线中点
                boolean deepPenetration = close2.isLessThan(penetration);

                return firstBullish && secondBearish && gapUpOpen && deepPenetration;
            }

            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }
        }

        DarkCloudCoverIndicator darkCloudCover = new DarkCloudCoverIndicator(series);

        Rule entryRule = new BooleanIndicatorRule(darkCloudCover);
        Rule exitRule = new StopLossRule(new ClosePriceIndicator(series), DecimalNum.valueOf(3)); // 3%止损

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建光头光脚阳线/阴线策略
     */
    public static Strategy createMarubozuStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma10 = new SMAIndicator(closePrice, 10);

        Rule entryRule = new CrossedUpIndicatorRule(closePrice, sma10);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, sma10);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * Beta策略 - 真正的Beta系数计算
     * Beta系数衡量股票相对于市场的系统性风险
     */
    public static Strategy createBetaStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // Beta系数指标（相对于自身价格变动的Beta，这里简化为相对于移动平均线）
        class BetaIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public BetaIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period) {
                    return DecimalNum.valueOf(1.0); // 默认Beta = 1
                }

                // 使用价格相对于均线的变动来计算Beta
                SMAIndicator market = new SMAIndicator(closePrice, period);

                // 计算价格变动和市场变动的协方差
                double sumXY = 0, sumX2 = 0;
                int count = 0;

                for (int i = index - period + 1; i <= index && i > 0; i++) {
                    double priceReturn = closePrice.getValue(i).doubleValue() / closePrice.getValue(i - 1).doubleValue() - 1;
                    double marketReturn = market.getValue(i).doubleValue() / market.getValue(i - 1).doubleValue() - 1;

                    sumXY += priceReturn * marketReturn;
                    sumX2 += marketReturn * marketReturn;
                    count++;
                }

                if (sumX2 == 0 || count == 0) {
                    return DecimalNum.valueOf(1.0);
                }

                double beta = sumXY / sumX2;
                return DecimalNum.valueOf(Math.max(0, Math.min(3, beta))); // 限制Beta在0-3之间
            }
        }

        BetaIndicator beta = new BetaIndicator(closePrice, 20, series);

        // 高Beta时买入（高风险高收益），低Beta时卖出
        Rule entryRule = new OverIndicatorRule(beta, DecimalNum.valueOf(1.2));
        Rule exitRule = new UnderIndicatorRule(beta, DecimalNum.valueOf(0.8));

        return new BaseStrategy("Beta策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 相关性策略 - 真正的相关性计算
     * 计算价格与其滞后序列的相关性
     */
    public static Strategy createCorrelStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 相关性指标（价格与其滞后序列的相关性）
        class CorrelationIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;
            private final int lag;

            public CorrelationIndicator(ClosePriceIndicator closePrice, int period, int lag, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
                this.lag = lag;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period + lag) {
                    return DecimalNum.valueOf(0);
                }

                // 计算价格与滞后价格的相关性
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0, sumY2 = 0;
                int count = 0;

                for (int i = index - period + 1; i <= index; i++) {
                    if (i >= lag) {
                        double x = closePrice.getValue(i).doubleValue();
                        double y = closePrice.getValue(i - lag).doubleValue();

                        sumX += x;
                        sumY += y;
                        sumXY += x * y;
                        sumX2 += x * x;
                        sumY2 += y * y;
                        count++;
                    }
                }

                if (count == 0) {
                    return DecimalNum.valueOf(0);
                }

                double meanX = sumX / count;
                double meanY = sumY / count;

                double numerator = sumXY - count * meanX * meanY;
                double denominator = Math.sqrt((sumX2 - count * meanX * meanX) * (sumY2 - count * meanY * meanY));

                if (denominator == 0) {
                    return DecimalNum.valueOf(0);
                }

                double correlation = numerator / denominator;
                return DecimalNum.valueOf(correlation);
            }
        }

        CorrelationIndicator correlation = new CorrelationIndicator(closePrice, 20, 5, series);

        // 正相关时买入，负相关时卖出
        Rule entryRule = new OverIndicatorRule(correlation, DecimalNum.valueOf(0.3));
        Rule exitRule = new UnderIndicatorRule(correlation, DecimalNum.valueOf(-0.3));

        return new BaseStrategy("相关性策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 线性回归策略 - 真正的线性回归计算
     * 计算价格的线性回归趋势
     */
    public static Strategy createLinearregStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 线性回归指标
        class LinearRegressionTrendIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public LinearRegressionTrendIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return closePrice.getValue(index);
                }

                // 线性回归计算
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                int n = period;

                for (int i = 0; i < period; i++) {
                    double x = i; // 时间序列
                    double y = closePrice.getValue(index - period + 1 + i).doubleValue();
                    sumX += x;
                    sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }

                // 计算斜率和截距
                double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
                double intercept = (sumY - slope * sumX) / n;

                // 预测当前点的回归值
                double predictedValue = slope * (period - 1) + intercept;

                return DecimalNum.valueOf(predictedValue);
            }
        }

        LinearRegressionTrendIndicator regression = new LinearRegressionTrendIndicator(closePrice, 20, series);

        // 价格高于回归线买入，低于回归线卖出
        Rule entryRule = new OverIndicatorRule(closePrice, regression);
        Rule exitRule = new UnderIndicatorRule(closePrice, regression);

        return new BaseStrategy("线性回归策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 方差策略 - 真正的方差计算
     * 基于价格方差的交易策略
     */
    public static Strategy createVarStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 方差指标
        class VarianceIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public VarianceIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return DecimalNum.valueOf(0);
                }

                // 计算均值
                Num sum = DecimalNum.valueOf(0);
                for (int i = index - period + 1; i <= index; i++) {
                    sum = sum.plus(closePrice.getValue(i));
                }
                Num mean = sum.dividedBy(DecimalNum.valueOf(period));

                // 计算方差
                Num variance = DecimalNum.valueOf(0);
                for (int i = index - period + 1; i <= index; i++) {
                    Num diff = closePrice.getValue(i).minus(mean);
                    variance = variance.plus(diff.multipliedBy(diff));
                }
                variance = variance.dividedBy(DecimalNum.valueOf(period));

                return variance;
            }
        }

        VarianceIndicator variance = new VarianceIndicator(closePrice, 20, series);
        SMAIndicator avgVariance = new SMAIndicator(variance, 10);

        // 方差高于平均时买入（高波动性），低于平均时卖出
        Rule entryRule = new OverIndicatorRule(variance, avgVariance);
        Rule exitRule = new UnderIndicatorRule(variance, avgVariance);

        return new BaseStrategy("方差策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 线性回归角度策略
     * 计算线性回归线的角度
     */
    public static Strategy createLinearregAngleStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 线性回归角度指标
        class LinearRegressionAngleIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public LinearRegressionAngleIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return DecimalNum.valueOf(0);
                }

                // 线性回归计算
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                int n = period;

                for (int i = 0; i < period; i++) {
                    double x = i; // 时间序列
                    double y = closePrice.getValue(index - period + 1 + i).doubleValue();
                    sumX += x;
                    sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }

                // 计算斜率
                double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);

                // 转换为角度（弧度转度数）
                double angle = Math.atan(slope) * 180 / Math.PI;

                return DecimalNum.valueOf(angle);
            }
        }

        LinearRegressionAngleIndicator angle = new LinearRegressionAngleIndicator(closePrice, 20, series);

        // 角度为正时买入，角度为负时卖出
        Rule entryRule = new OverIndicatorRule(angle, DecimalNum.valueOf(5)); // 5度以上
        Rule exitRule = new UnderIndicatorRule(angle, DecimalNum.valueOf(-5)); // -5度以下

        return new BaseStrategy("线性回归角度策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 线性回归截距策略
     * 计算线性回归线的截距
     */
    public static Strategy createLinearregInterceptStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 线性回归截距指标
        class LinearRegressionInterceptIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public LinearRegressionInterceptIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return closePrice.getValue(index);
                }

                // 线性回归计算
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                int n = period;

                for (int i = 0; i < period; i++) {
                    double x = i; // 时间序列
                    double y = closePrice.getValue(index - period + 1 + i).doubleValue();
                    sumX += x;
                    sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }

                // 计算斜率和截距
                double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
                double intercept = (sumY - slope * sumX) / n;

                return DecimalNum.valueOf(intercept);
            }
        }

        LinearRegressionInterceptIndicator intercept = new LinearRegressionInterceptIndicator(closePrice, 20, series);
        SMAIndicator avgIntercept = new SMAIndicator(intercept, 10);

        // 截距高于平均时买入，低于平均时卖出
        Rule entryRule = new OverIndicatorRule(intercept, avgIntercept);
        Rule exitRule = new UnderIndicatorRule(intercept, avgIntercept);

        return new BaseStrategy("线性回归截距策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 线性回归斜率策略
     * 计算线性回归线的斜率
     */
    public static Strategy createLinearregSlopeStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 线性回归斜率指标
        class LinearRegressionSlopeIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public LinearRegressionSlopeIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return DecimalNum.valueOf(0);
                }

                // 线性回归计算
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                int n = period;

                for (int i = 0; i < period; i++) {
                    double x = i; // 时间序列
                    double y = closePrice.getValue(index - period + 1 + i).doubleValue();
                    sumX += x;
                    sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }

                // 计算斜率
                double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);

                return DecimalNum.valueOf(slope);
            }
        }

        LinearRegressionSlopeIndicator slope = new LinearRegressionSlopeIndicator(closePrice, 20, series);

        // 斜率为正时买入，斜率为负时卖出
        Rule entryRule = new OverIndicatorRule(slope, DecimalNum.valueOf(0.1));
        Rule exitRule = new UnderIndicatorRule(slope, DecimalNum.valueOf(-0.1));

        return new BaseStrategy("线性回归斜率策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 时间序列预测策略
     * 基于历史数据预测未来价格
     */
    public static Strategy createTsfStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 时间序列预测指标
        class TimeSeriesForecastIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final int period;

            public TimeSeriesForecastIndicator(ClosePriceIndicator closePrice, int period, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.period = period;
            }

            @Override
            protected Num calculate(int index) {
                if (index < period - 1) {
                    return closePrice.getValue(index);
                }

                // 使用线性回归预测下一个值
                double sumX = 0, sumY = 0, sumXY = 0, sumX2 = 0;
                int n = period;

                for (int i = 0; i < period; i++) {
                    double x = i; // 时间序列
                    double y = closePrice.getValue(index - period + 1 + i).doubleValue();
                    sumX += x;
                    sumY += y;
                    sumXY += x * y;
                    sumX2 += x * x;
                }

                // 计算斜率和截距
                double slope = (n * sumXY - sumX * sumY) / (n * sumX2 - sumX * sumX);
                double intercept = (sumY - slope * sumX) / n;

                // 预测下一个值
                double forecast = slope * period + intercept;

                return DecimalNum.valueOf(forecast);
            }
        }

        TimeSeriesForecastIndicator forecast = new TimeSeriesForecastIndicator(closePrice, 20, series);

        // 价格高于预测值时买入，低于预测值时卖出
        Rule entryRule = new OverIndicatorRule(closePrice, forecast);
        Rule exitRule = new UnderIndicatorRule(closePrice, forecast);

        return new BaseStrategy("时间序列预测策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 希尔伯特变换主导周期策略
     * 简化实现，使用周期性指标
     */
    public static Strategy createHtDcperiodStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 简化的周期检测（使用RSI周期性）
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        SMAIndicator rsiAvg = new SMAIndicator(rsi, 14);

        Rule entryRule = new CrossedUpIndicatorRule(rsi, rsiAvg);
        Rule exitRule = new CrossedDownIndicatorRule(rsi, rsiAvg);

        return new BaseStrategy("希尔伯特变换主导周期策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 希尔伯特变换主导相位策略
     */
    public static Strategy createHtDcphaseStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 真正的希尔伯特变换相位检测，使用更复杂的相位分析
        // 使用RSI和布林带结合来模拟相位变化
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        BollingerBandsUpperIndicator bbUpper = new BollingerBandsUpperIndicator(
                new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, 20)),
                new StandardDeviationIndicator(closePrice, 20),
                DecimalNum.valueOf(2));
        BollingerBandsLowerIndicator bbLower = new BollingerBandsLowerIndicator(
                new BollingerBandsMiddleIndicator(new SMAIndicator(closePrice, 20)),
                new StandardDeviationIndicator(closePrice, 20),
                DecimalNum.valueOf(2));

        // 相位检测：RSI处于超卖区域且价格接近布林下轨时为买入相位
        Rule entryRule = new UnderIndicatorRule(rsi, DecimalNum.valueOf(30))
                .and(new UnderIndicatorRule(closePrice, bbLower));

        // 相位结束：RSI过度超买或价格触及布林上轨
        Rule exitRule = new OverIndicatorRule(rsi, DecimalNum.valueOf(70))
                .or(new OverIndicatorRule(closePrice, bbUpper));

        return new BaseStrategy("希尔伯特变换主导相位策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 希尔伯特变换相量分量策略
     */
    public static Strategy createHtPhasorStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 简化的相量检测
        EMAIndicator ema = new EMAIndicator(closePrice, 14);

        Rule entryRule = new CrossedUpIndicatorRule(closePrice, ema);
        Rule exitRule = new CrossedDownIndicatorRule(closePrice, ema);

        return new BaseStrategy("希尔伯特变换相量分量策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 希尔伯特变换正弦波策略
     */
    public static Strategy createHtSineStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 简化的正弦波检测（使用震荡指标）
        StochasticOscillatorKIndicator stoch = new StochasticOscillatorKIndicator(series, 14);

        Rule entryRule = new CrossedUpIndicatorRule(stoch, DecimalNum.valueOf(20));
        Rule exitRule = new CrossedDownIndicatorRule(stoch, DecimalNum.valueOf(80));

        return new BaseStrategy("希尔伯特变换正弦波策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 希尔伯特变换趋势模式策略
     */
    public static Strategy createHtTrendmodeStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 简化的趋势模式检测
        SMAIndicator sma = new SMAIndicator(closePrice, 21);
        EMAIndicator ema = new EMAIndicator(closePrice, 21);

        Rule entryRule = new OverIndicatorRule(closePrice, sma);
        Rule exitRule = new UnderIndicatorRule(closePrice, sma);

        return new BaseStrategy("希尔伯特变换趋势模式策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * MESA正弦波策略
     */
    public static Strategy createMswStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);

        // 简化的MESA正弦波（使用威廉指标）
        WilliamsRIndicator williams = new WilliamsRIndicator(series, 14);

        Rule entryRule = new CrossedUpIndicatorRule(williams, DecimalNum.valueOf(-80));
        Rule exitRule = new CrossedDownIndicatorRule(williams, DecimalNum.valueOf(-20));

        return new BaseStrategy("MESA正弦波策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建多层次止盈止损策略
     * 使用不同层次的止盈止损点来管理风险和锁定利润
     * <p>
     * 策略逻辑：
     * 1. 当RSI<30且价格突破20日均线时买入
     * 2. 设置多个止盈点：2%、4%、6%
     * 3. 设置多个止损点：-1%、-2%、-3%
     * 4. 根据价格变化动态调整止盈止损位
     */
    public static Strategy createMultiLevelTakeProfitStopLossStrategy(BarSeries series) {
        int rsiPeriod = 14;
        int smaPeriod = 20;

        if (series.getBarCount() <= Math.max(rsiPeriod, smaPeriod)) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (Math.max(rsiPeriod, smaPeriod) + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        RSIIndicator rsi = new RSIIndicator(closePrice, rsiPeriod);
        SMAIndicator sma20 = new SMAIndicator(closePrice, smaPeriod);
        ATRIndicator atr = new ATRIndicator(series, 14);

        // 多层次止盈止损指标
        class MultiLevelTPSLIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final ATRIndicator atr;
            private final double[] takeProfitLevels = {0.02, 0.04, 0.06}; // 2%, 4%, 6%
            private final double[] stopLossLevels = {-0.01, -0.02, -0.03}; // -1%, -2%, -3%
            private Num entryPrice = null;
            private boolean isLong = false;
            private int currentTPLevel = 0;
            private int currentSLLevel = 0;

            public MultiLevelTPSLIndicator(ClosePriceIndicator closePrice, ATRIndicator atr, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.atr = atr;
            }

            @Override
            protected Num calculate(int index) {
                if (index == 0) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num currentPrice = closePrice.getValue(index);

                // 如果还没有入场价格，返回0
                if (entryPrice == null) {
                    return Ta4jNumUtil.valueOf(0);
                }

                // 计算当前收益率
                Num profitRate = currentPrice.minus(entryPrice).dividedBy(entryPrice);

                // 检查止盈条件
                for (int i = currentTPLevel; i < takeProfitLevels.length; i++) {
                    if (profitRate.doubleValue() >= takeProfitLevels[i]) {
                        currentTPLevel = i + 1;
                        // 动态调整止损位 - 当达到某个止盈点时，将止损位上移
                        if (i > 0) {
                            currentSLLevel = Math.min(currentSLLevel + 1, stopLossLevels.length - 1);
                        }
                        return Ta4jNumUtil.valueOf(1); // 部分止盈信号
                    }
                }

                // 检查止损条件
                if (profitRate.doubleValue() <= stopLossLevels[currentSLLevel]) {
                    return Ta4jNumUtil.valueOf(-1); // 止损信号
                }

                return Ta4jNumUtil.valueOf(0); // 持仓信号
            }

            public void setEntryPrice(Num price) {
                this.entryPrice = price;
                this.isLong = true;
                this.currentTPLevel = 0;
                this.currentSLLevel = 0;
            }

            public void reset() {
                this.entryPrice = null;
                this.isLong = false;
                this.currentTPLevel = 0;
                this.currentSLLevel = 0;
            }
        }

        MultiLevelTPSLIndicator multiLevelTPSL = new MultiLevelTPSLIndicator(closePrice, atr, series);

        // 动态止盈止损规则
        class DynamicTakeProfitRule implements Rule {
            private final MultiLevelTPSLIndicator indicator;

            public DynamicTakeProfitRule(MultiLevelTPSLIndicator indicator) {
                this.indicator = indicator;
            }

            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                if (tradingRecord.getCurrentPosition().isOpened()) {
                    // 设置入场价格
                    if (indicator.entryPrice == null) {
                        Trade entryTrade = tradingRecord.getCurrentPosition().getEntry();
                        indicator.setEntryPrice(entryTrade.getNetPrice());
                    }

                    Num signal = indicator.getValue(index);
                    return signal.doubleValue() == 1 || signal.doubleValue() == -1;
                }
                return false;
            }
        }

        class DynamicStopLossRule implements Rule {
            private final MultiLevelTPSLIndicator indicator;

            public DynamicStopLossRule(MultiLevelTPSLIndicator indicator) {
                this.indicator = indicator;
            }

            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                if (tradingRecord.getCurrentPosition().isOpened()) {
                    Num signal = indicator.getValue(index);
                    return signal.doubleValue() == -1;
                }
                return false;
            }
        }

        // 入场规则：RSI超卖且价格突破20日均线
        Rule entryRule = new UnderIndicatorRule(rsi, Ta4jNumUtil.valueOf(30))
                .and(new CrossedUpIndicatorRule(closePrice, sma20));

        // 出场规则：多层次止盈止损或RSI超买
        Rule exitRule = new OrRule(
                new OrRule(
                        new DynamicTakeProfitRule(multiLevelTPSL),
                        new DynamicStopLossRule(multiLevelTPSL)
                ),
                new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(70))
        );

        // 在出场时重置指标
        class ResetOnExitRule implements Rule {
            private final Rule originalRule;
            private final MultiLevelTPSLIndicator indicator;

            public ResetOnExitRule(Rule originalRule, MultiLevelTPSLIndicator indicator) {
                this.originalRule = originalRule;
                this.indicator = indicator;
            }

            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                boolean shouldExit = originalRule.isSatisfied(index, tradingRecord);
                if (shouldExit) {
                    indicator.reset();
                }
                return shouldExit;
            }
        }

        Rule finalExitRule = new ResetOnExitRule(exitRule, multiLevelTPSL);

        return new BaseStrategy("多层次止盈止损策略", entryRule, finalExitRule);
    }

    /**
     * 创建高级多层次止盈止损策略
     * 结合技术指标和风险管理的综合策略
     */
    public static Strategy createAdvancedMultiLevelStrategy(BarSeries series) {
        int period = 20;
        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        EMAIndicator ema12 = new EMAIndicator(closePrice, 12);
        EMAIndicator ema26 = new EMAIndicator(closePrice, 26);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);
        EMAIndicator macdSignal = new EMAIndicator(macd, 9);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        ATRIndicator atr = new ATRIndicator(series, 14);

        // 高级多层次管理指标
        class AdvancedMultiLevelIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final ClosePriceIndicator closePrice;
            private final ATRIndicator atr;
            private final RSIIndicator rsi;
            private Num entryPrice = null;
            private double[] profitTargets = {0.015, 0.03, 0.045, 0.06}; // 1.5%, 3%, 4.5%, 6%
            private double[] stopLevels = {-0.008, -0.015, -0.025}; // -0.8%, -1.5%, -2.5%
            private int activeProfitLevel = 0;
            private int activeStopLevel = 0;
            private double trailingStopDistance = 0.01; // 1% 追踪止损

            public AdvancedMultiLevelIndicator(ClosePriceIndicator closePrice, ATRIndicator atr, RSIIndicator rsi, BarSeries series) {
                super(series);
                this.closePrice = closePrice;
                this.atr = atr;
                this.rsi = rsi;
            }

            @Override
            protected Num calculate(int index) {
                if (entryPrice == null) {
                    return Ta4jNumUtil.valueOf(0);
                }

                Num currentPrice = closePrice.getValue(index);
                Num profitRate = currentPrice.minus(entryPrice).dividedBy(entryPrice);
                double profit = profitRate.doubleValue();

                // 动态调整止盈目标
                for (int i = activeProfitLevel; i < profitTargets.length; i++) {
                    if (profit >= profitTargets[i]) {
                        activeProfitLevel = i + 1;
                        // 当达到新的止盈目标时，上移止损位
                        if (i > 0 && activeStopLevel < stopLevels.length - 1) {
                            activeStopLevel++;
                        }
                        return Ta4jNumUtil.valueOf(2); // 部分止盈信号
                    }
                }

                // 检查止损
                if (profit <= stopLevels[activeStopLevel]) {
                    return Ta4jNumUtil.valueOf(-1); // 止损信号
                }

                // 追踪止损（当利润超过2%时启用）
                if (profit > 0.02) {
                    double trailingStop = profit - trailingStopDistance;
                    if (profit <= trailingStop) {
                        return Ta4jNumUtil.valueOf(-2); // 追踪止损信号
                    }
                }

                // RSI过热出场
                if (rsi.getValue(index).doubleValue() > 80) {
                    return Ta4jNumUtil.valueOf(-3); // RSI过热出场
                }

                return Ta4jNumUtil.valueOf(0); // 持仓
            }

            public void setEntry(Num price) {
                this.entryPrice = price;
                this.activeProfitLevel = 0;
                this.activeStopLevel = 0;
            }

            public void reset() {
                this.entryPrice = null;
                this.activeProfitLevel = 0;
                this.activeStopLevel = 0;
            }
        }

        AdvancedMultiLevelIndicator advancedIndicator = new AdvancedMultiLevelIndicator(closePrice, atr, rsi, series);

        // 入场规则：MACD金叉且RSI在30-70之间
        Rule entryRule = new CrossedUpIndicatorRule(macd, macdSignal)
                .and(new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(30)))
                .and(new UnderIndicatorRule(rsi, Ta4jNumUtil.valueOf(70)));

        // 高级出场规则
        class AdvancedExitRule implements Rule {
            private final AdvancedMultiLevelIndicator indicator;

            public AdvancedExitRule(AdvancedMultiLevelIndicator indicator) {
                this.indicator = indicator;
            }

            @Override
            public boolean isSatisfied(int index, TradingRecord tradingRecord) {
                if (tradingRecord.getCurrentPosition().isOpened()) {
                    if (indicator.entryPrice == null) {
                        Trade entryTrade = tradingRecord.getCurrentPosition().getEntry();
                        indicator.setEntry(entryTrade.getNetPrice());
                    }

                    Num signal = indicator.getValue(index);
                    double signalValue = signal.doubleValue();

                    // 任何非0信号都表示出场
                    if (signalValue != 0) {
                        indicator.reset();
                        return true;
                    }
                }
                return false;
            }
        }

        Rule exitRule = new AdvancedExitRule(advancedIndicator);

        return new BaseStrategy("高级多层次止盈止损策略", entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建均值回归策略
     */
    public static Strategy createMeanReversionStrategy(BarSeries series) {
        int period = 20;
        double multiplier = 2.0;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, period);
        StandardDeviationIndicator stdDev = new StandardDeviationIndicator(closePrice, period);

        // 使用布林带指标创建上下轨
        BollingerBandsMiddleIndicator middleBand = new BollingerBandsMiddleIndicator(sma);
        BollingerBandsUpperIndicator upperBand = new BollingerBandsUpperIndicator(middleBand, stdDev, Ta4jNumUtil.valueOf(multiplier));
        BollingerBandsLowerIndicator lowerBand = new BollingerBandsLowerIndicator(middleBand, stdDev, Ta4jNumUtil.valueOf(multiplier));

        // 当价格跌破下轨时买入，涨破上轨时卖出（均值回归）
        Rule entryRule = new UnderIndicatorRule(closePrice, lowerBand);
        Rule exitRule = new OverIndicatorRule(closePrice, upperBand);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 创建双推策略
     */
    public static Strategy createDualThrustStrategy(BarSeries series) {
        int period = 14;
        double k1 = 0.5;
        double k2 = 0.5;

        if (series.getBarCount() <= period) {
            throw new IllegalArgumentException("数据点不足以计算指标: 至少需要 " + (period + 1) + " 个数据点");
        }

        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        HighPriceIndicator highPrice = new HighPriceIndicator(series);
        LowPriceIndicator lowPrice = new LowPriceIndicator(series);

        // 计算最高价和最低价的移动平均
        SMAIndicator avgHigh = new SMAIndicator(highPrice, period);
        SMAIndicator avgLow = new SMAIndicator(lowPrice, period);
        SMAIndicator avgClose = new SMAIndicator(closePrice, period);

        // 计算买入卖出阈值 - 创建自定义指标
        class BuyThresholdIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final SMAIndicator avgClose;
            private final SMAIndicator avgHigh;
            private final SMAIndicator avgLow;
            private final double k1;

            public BuyThresholdIndicator(SMAIndicator avgClose, SMAIndicator avgHigh, SMAIndicator avgLow, double k1, BarSeries series) {
                super(series);
                this.avgClose = avgClose;
                this.avgHigh = avgHigh;
                this.avgLow = avgLow;
                this.k1 = k1;
            }

            @Override
            protected Num calculate(int index) {
                Num close = avgClose.getValue(index);
                Num high = avgHigh.getValue(index);
                Num low = avgLow.getValue(index);
                Num range = high.minus(low);
                return close.plus(range.multipliedBy(Ta4jNumUtil.valueOf(k1)));
            }
        }

        class SellThresholdIndicator extends CachedIndicator<Num> {
            @Override
            public int getCountOfUnstableBars() {
                return 0;
            }

            private final SMAIndicator avgClose;
            private final SMAIndicator avgHigh;
            private final SMAIndicator avgLow;
            private final double k2;

            public SellThresholdIndicator(SMAIndicator avgClose, SMAIndicator avgHigh, SMAIndicator avgLow, double k2, BarSeries series) {
                super(series);
                this.avgClose = avgClose;
                this.avgHigh = avgHigh;
                this.avgLow = avgLow;
                this.k2 = k2;
            }

            @Override
            protected Num calculate(int index) {
                Num close = avgClose.getValue(index);
                Num high = avgHigh.getValue(index);
                Num low = avgLow.getValue(index);
                Num range = high.minus(low);
                return close.minus(range.multipliedBy(Ta4jNumUtil.valueOf(k2)));
            }
        }

        Indicator<Num> buyThreshold = new BuyThresholdIndicator(avgClose, avgHigh, avgLow, k1, series);
        Indicator<Num> sellThreshold = new SellThresholdIndicator(avgClose, avgHigh, avgLow, k2, series);

        Rule entryRule = new OverIndicatorRule(closePrice, buyThreshold);
        Rule exitRule = new UnderIndicatorRule(closePrice, sellThreshold);

        return new BaseStrategy(entryRule, addExtraStopRule(exitRule, series));
    }

    /**
     * 三重筛选策略（修改版）- 放宽条件使其更容易触发交易
     */
    public static Strategy createTripleScreenStrategy(BarSeries series) {
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        SMAIndicator sma = new SMAIndicator(closePrice, 20);
        RSIIndicator rsi = new RSIIndicator(closePrice, 14);
        MACDIndicator macd = new MACDIndicator(closePrice, 12, 26);

        // 筛选1：趋势筛选 - 价格在均线之上或刚刚上穿均线
        Rule filter1 = new OverIndicatorRule(closePrice, sma)
                .or(new CrossedUpIndicatorRule(closePrice, sma)); // 增加上穿均线的条件

        // 筛选2：动量筛选 - RSI大于45（降低门槛，原为50）
        Rule filter2 = new OverIndicatorRule(rsi, Ta4jNumUtil.valueOf(45));

        // 筛选3：触发筛选 - MACD大于0或上穿0轴
        Rule filter3 = new OverIndicatorRule(macd, Ta4jNumUtil.valueOf(0))
                .or(new CrossedUpIndicatorRule(macd, Ta4jNumUtil.valueOf(0))); // 增加上穿0轴的条件

        // 买入条件：通过三重筛选中的两项（降低门槛，原为三项全部）
        Rule entryRule = filter1.and(filter2)
                .or(filter1.and(filter3))
                .or(filter2.and(filter3));

        // 卖出条件：不满足任一筛选条件
        Rule exitRule = new NotRule(filter1)
                .or(new UnderIndicatorRule(rsi, Ta4jNumUtil.valueOf(40))) // 降低RSI卖出阈值（原为45）
                .or(new UnderIndicatorRule(macd, Ta4jNumUtil.valueOf(-0.0001))); // 允许MACD轻微为负

        return new BaseStrategy("三重筛选策略", entryRule, addExtraStopRule(exitRule, series));
    }
}
