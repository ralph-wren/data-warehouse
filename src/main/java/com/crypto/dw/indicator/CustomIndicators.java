package com.crypto.dw.indicator;


import com.crypto.dw.utils.Ta4jNumUtil;
import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.HighPriceIndicator;
import org.ta4j.core.indicators.helpers.LowPriceIndicator;
import org.ta4j.core.num.Num;

/**
 * 自定义指标类
 * 实现Ta4j 0.18版本中可能缺失或变更的指标
 */
public class CustomIndicators{

    /**
     * 平均真实范围(ATR)指标
     */
    public static class ATRIndicator extends CachedIndicator<Num> {
        private final Indicator<Num> trueRange;
        private final int barCount;

        /**
         * 构造函数
         *
         * @param series 数据序列
         * @param barCount 计算周期
         */
        public ATRIndicator(BarSeries series, int barCount) {
            super(series);
            this.barCount = barCount;
            this.trueRange = new TrueRangeIndicator(series);
        }

        @Override
        protected Num calculate(int index) {
            if (index == 0) {
                return trueRange.getValue(0);
            }
            
            Num sum = Ta4jNumUtil.zero();
            int startIndex = Math.max(0, index - barCount + 1);
            for (int i = startIndex; i <= index; i++) {
                sum = sum.plus(trueRange.getValue(i));
            }
            
            return sum.dividedBy(Ta4jNumUtil.valueOf(index - startIndex + 1));
        }
        
        @Override
        public int getCountOfUnstableBars() {
            return barCount;
        }
    }

    /**
     * 真实范围(TR)指标
     */
    public static class TrueRangeIndicator extends CachedIndicator<Num> {
        private final HighPriceIndicator highPrice;
        private final LowPriceIndicator lowPrice;
        private final ClosePriceIndicator closePrice;

        /**
         * 构造函数
         *
         * @param series 数据序列
         */
        public TrueRangeIndicator(BarSeries series) {
            super(series);
            this.highPrice = new HighPriceIndicator(series);
            this.lowPrice = new LowPriceIndicator(series);
            this.closePrice = new ClosePriceIndicator(series);
        }

        @Override
        protected Num calculate(int index) {
            if (index == 0) {
                return highPrice.getValue(0).minus(lowPrice.getValue(0));
            }

            Num previousClose = closePrice.getValue(index - 1);
            Num currentHigh = highPrice.getValue(index);
            Num currentLow = lowPrice.getValue(index);

            Num highMinusPrevClose = currentHigh.minus(previousClose).abs();
            Num lowMinusPrevClose = currentLow.minus(previousClose).abs();
            Num highMinusLow = currentHigh.minus(currentLow);

            return highMinusPrevClose.max(lowMinusPrevClose).max(highMinusLow);
        }
        
        @Override
        public int getCountOfUnstableBars() {
            return 1;
        }
    }

    /**
     * 最大价格指标
     */
    public static class MaxPriceIndicator extends CachedIndicator<Num> {
        private final HighPriceIndicator highPrice;
        private final int period;

        /**
         * 构造函数
         *
         * @param series 数据序列
         * @param period 计算周期
         */
        public MaxPriceIndicator(BarSeries series, int period) {
            super(series);
            this.highPrice = new HighPriceIndicator(series);
            this.period = period;
        }

        @Override
        protected Num calculate(int index) {
            Num highest = highPrice.getValue(index);
            int startIndex = Math.max(0, index - period + 1);
            for (int i = startIndex; i <= index; i++) {
                Num currentValue = highPrice.getValue(i);
                if (currentValue.isGreaterThan(highest)) {
                    highest = currentValue;
                }
            }
            return highest;
        }
        
        @Override
        public int getCountOfUnstableBars() {
            return period;
        }
    }

    /**
     * 最小价格指标
     */
    public static class MinPriceIndicator extends CachedIndicator<Num> {
        private final LowPriceIndicator lowPrice;
        private final int period;

        /**
         * 构造函数
         *
         * @param series 数据序列
         * @param period 计算周期
         */
        public MinPriceIndicator(BarSeries series, int period) {
            super(series);
            this.lowPrice = new LowPriceIndicator(series);
            this.period = period;
        }

        @Override
        protected Num calculate(int index) {
            Num lowest = lowPrice.getValue(index);
            int startIndex = Math.max(0, index - period + 1);
            for (int i = startIndex; i <= index; i++) {
                Num currentValue = lowPrice.getValue(i);
                if (currentValue.isLessThan(lowest)) {
                    lowest = currentValue;
                }
            }
            return lowest;
        }
        
        @Override
        public int getCountOfUnstableBars() {
            return period;
        }
    }

    /**
     * 中间价格指标
     */
    public static class MedianPriceIndicator extends CachedIndicator<Num> {
        private final HighPriceIndicator highPrice;
        private final LowPriceIndicator lowPrice;

        /**
         * 构造函数
         *
         * @param series 数据序列
         */
        public MedianPriceIndicator(BarSeries series) {
            super(series);
            this.highPrice = new HighPriceIndicator(series);
            this.lowPrice = new LowPriceIndicator(series);
        }

        @Override
        protected Num calculate(int index) {
            return highPrice.getValue(index).plus(lowPrice.getValue(index))
                    .dividedBy(Ta4jNumUtil.valueOf(2));
        }
        
        @Override
        public int getCountOfUnstableBars() {
            return 0;
        }
    }
    
    /**
     * 差值指标
     * 用于替代Ta4j 0.18中可能缺失的DifferenceIndicator
     */
    public static class DifferenceIndicator extends CachedIndicator<Num> {
        private final Indicator<Num> first;
        private final Indicator<Num> second;

        /**
         * 构造函数
         *
         * @param first 第一个指标
         * @param second 第二个指标
         */
        public DifferenceIndicator(Indicator<Num> first, Indicator<Num> second) {
            super(first);
            this.first = first;
            this.second = second;
        }

        @Override
        protected Num calculate(int index) {
            return first.getValue(index).minus(second.getValue(index));
        }

        @Override
        public int getCountOfUnstableBars() {
            return Math.max(
                first.getCountOfUnstableBars(), 
                second.getCountOfUnstableBars()
            );
        }
    }
} 