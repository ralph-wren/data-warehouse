package com.crypto.dw.utils;

import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;

/**
 * Ta4j数值工具类
 * 用于处理Ta4j 0.18版本中的数值转换
 * 替代旧版本中的series.numOf()方法
 */
public class Ta4jNumUtil{

    /**
     * 将double值转换为Num
     * 替代旧版本中的series.numOf(double)方法
     *
     * @param value double值
     * @return Num对象
     */
    public static Num valueOf(double value) {
        return DecimalNum.valueOf(value);
    }

    /**
     * 将int值转换为Num
     * 替代旧版本中的series.numOf(int)方法
     *
     * @param value int值
     * @return Num对象
     */
    public static Num valueOf(int value) {
        return DecimalNum.valueOf(value);
    }

    /**
     * 将BigDecimal值转换为Num
     * 替代旧版本中的series.numOf(BigDecimal)方法
     *
     * @param value BigDecimal值
     * @return Num对象
     */
    public static Num valueOf(BigDecimal value) {
        return DecimalNum.valueOf(value);
    }

    /**
     * 返回值为0的Num对象
     * 替代旧版本中的series.numOf(0)方法
     *
     * @return 值为0的Num对象
     */
    public static Num zero() {
        return DecimalNum.valueOf(0);
    }

    /**
     * 返回值为1的Num对象
     * 替代旧版本中的series.numOf(1)方法
     *
     * @return 值为1的Num对象
     */
    public static Num one() {
        return DecimalNum.valueOf(1);
    }

    /**
     * 创建值为100的Num
     * 替代旧版本中的series.numOf(100)方法
     *
     * @return 值为100的Num对象
     */
    public static Num hundred() {
        return DecimalNum.valueOf(100);
    }

    /**
     * 创建值为指定值的Num
     * 兼容旧代码中的series.numOf()调用
     * 
     * @param series 数据序列（仅用于兼容旧代码，实际未使用）
     * @param value 数值
     * @return Num对象
     */
    public static Num numOf(BarSeries series, double value) {
        return DecimalNum.valueOf(value);
    }
} 