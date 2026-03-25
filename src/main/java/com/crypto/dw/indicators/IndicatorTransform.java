package com.crypto.dw.indicators;

import org.ta4j.core.BarSeries;
import org.ta4j.core.Indicator;
import org.ta4j.core.indicators.CachedIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.function.Function;

/**
 * 指标转换工具类
 * 
 * 用于替代 ta4j 0.18 中的 TransformIndicator（在 0.21 中已移除）
 * 
 * ta4j 0.21 变更：
 * - TransformIndicator 被移除
 * - 使用自定义 CachedIndicator 实现转换功能
 * - CachedIndicator 需要实现 getCountOfUnstableBars() 方法
 * - Num.numOf() 方法改为使用 BarSeries.numOf()
 * 
 * 本类提供兼容性包装，简化代码迁移
 * 
 * @author Crypto DW Team
 * @date 2026-03-25
 */
public class IndicatorTransform {
    
    /**
     * 乘法转换
     * 
     * 将指标值乘以一个常数
     * 
     * @param indicator 原始指标
     * @param multiplier 乘数
     * @return 转换后的指标
     */
    public static Indicator<Num> multiply(Indicator<Num> indicator, double multiplier) {
        return new CachedIndicator<Num>(indicator.getBarSeries()) {
            @Override
            protected Num calculate(int index) {
                Num value = indicator.getValue(index);
                // ta4j 0.21: 使用 DecimalNum.valueOf() 创建 Num 对象
                return value.multipliedBy(DecimalNum.valueOf(multiplier));
            }
            
            // ta4j 0.21: 必须实现 getCountOfUnstableBars() 方法
            @Override
            public int getCountOfUnstableBars() {
                return indicator.getCountOfUnstableBars();
            }
        };
    }
    
    /**
     * 乘法转换（BigDecimal版本）
     * 
     * @param indicator 原始指标
     * @param multiplier 乘数
     * @return 转换后的指标
     */
    public static Indicator<Num> multiply(Indicator<Num> indicator, BigDecimal multiplier) {
        return multiply(indicator, multiplier.doubleValue());
    }
    
    /**
     * 除法转换
     * 
     * 将指标值除以一个常数
     * 
     * @param indicator 原始指标
     * @param divisor 除数
     * @return 转换后的指标
     */
    public static Indicator<Num> divide(Indicator<Num> indicator, double divisor) {
        return new CachedIndicator<Num>(indicator.getBarSeries()) {
            @Override
            protected Num calculate(int index) {
                Num value = indicator.getValue(index);
                // ta4j 0.21: 使用 DecimalNum.valueOf() 创建 Num 对象
                return value.dividedBy(DecimalNum.valueOf(divisor));
            }
            
            // ta4j 0.21: 必须实现 getCountOfUnstableBars() 方法
            @Override
            public int getCountOfUnstableBars() {
                return indicator.getCountOfUnstableBars();
            }
        };
    }
    
    /**
     * 加法转换
     * 
     * 将指标值加上一个常数
     * 
     * @param indicator 原始指标
     * @param addend 加数
     * @return 转换后的指标
     */
    public static Indicator<Num> add(Indicator<Num> indicator, double addend) {
        return new CachedIndicator<Num>(indicator.getBarSeries()) {
            @Override
            protected Num calculate(int index) {
                Num value = indicator.getValue(index);
                // ta4j 0.21: 使用 DecimalNum.valueOf() 创建 Num 对象
                return value.plus(DecimalNum.valueOf(addend));
            }
            
            // ta4j 0.21: 必须实现 getCountOfUnstableBars() 方法
            @Override
            public int getCountOfUnstableBars() {
                return indicator.getCountOfUnstableBars();
            }
        };
    }
    
    /**
     * 减法转换
     * 
     * 将指标值减去一个常数
     * 
     * @param indicator 原始指标
     * @param subtrahend 减数
     * @return 转换后的指标
     */
    public static Indicator<Num> subtract(Indicator<Num> indicator, double subtrahend) {
        return new CachedIndicator<Num>(indicator.getBarSeries()) {
            @Override
            protected Num calculate(int index) {
                Num value = indicator.getValue(index);
                // ta4j 0.21: 使用 DecimalNum.valueOf() 创建 Num 对象
                return value.minus(DecimalNum.valueOf(subtrahend));
            }
            
            // ta4j 0.21: 必须实现 getCountOfUnstableBars() 方法
            @Override
            public int getCountOfUnstableBars() {
                return indicator.getCountOfUnstableBars();
            }
        };
    }
    
    /**
     * 自定义转换
     * 
     * 使用自定义函数转换指标值
     * 
     * @param indicator 原始指标
     * @param function 转换函数
     * @return 转换后的指标
     */
    public static Indicator<Num> transform(Indicator<Num> indicator, Function<Num, Num> function) {
        return new CachedIndicator<Num>(indicator.getBarSeries()) {
            @Override
            protected Num calculate(int index) {
                return function.apply(indicator.getValue(index));
            }
            
            // ta4j 0.21: 必须实现 getCountOfUnstableBars() 方法
            @Override
            public int getCountOfUnstableBars() {
                return indicator.getCountOfUnstableBars();
            }
        };
    }
}
