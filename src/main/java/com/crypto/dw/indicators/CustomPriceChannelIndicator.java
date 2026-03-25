package com.crypto.dw.indicators;

import com.crypto.dw.flink.FlinkADSTechnicalIndicatorsJob;
import org.ta4j.core.Bar;
import org.ta4j.core.BarSeries;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 自定义指标示例 - 价格通道指标（Price Channel）
 * 
 * 计算公式：
 * - 上轨 = N周期内最高价
 * - 下轨 = N周期内最低价
 * - 中轨 = (上轨 + 下轨) / 2
 * 
 * 这个指标用于判断价格突破和支撑/阻力位
 * 
 * @author Crypto DW Team
 * @date 2026-03-25
 */
public class CustomPriceChannelIndicator implements FlinkADSTechnicalIndicatorsJob.CustomIndicator {
    
    @Override
    public void calculate(
            BarSeries series,
            Map<String, String> params,
            FlinkADSTechnicalIndicatorsJob.IndicatorResult result) {
        
        // 获取参数
        int period = Integer.parseInt(params.getOrDefault("period", "20"));
        
        // 确保有足够的数据
        if (series.getBarCount() < period) {
            result.indicatorValue = BigDecimal.ZERO;
            return;
        }
        
        int endIndex = series.getEndIndex();
        int startIndex = Math.max(0, endIndex - period + 1);
        
        // 找出周期内的最高价和最低价
        Num highest = series.getBar(startIndex).getHighPrice();
        Num lowest = series.getBar(startIndex).getLowPrice();
        
        for (int i = startIndex + 1; i <= endIndex; i++) {
            Bar bar = series.getBar(i);
            Num high = bar.getHighPrice();
            Num low = bar.getLowPrice();
            
            if (high.isGreaterThan(highest)) {
                highest = high;
            }
            if (low.isLessThan(lowest)) {
                lowest = low;
            }
        }
        
        // 计算中轨
        Num middle = highest.plus(lowest).dividedBy(DecimalNum.valueOf(2));
        
        // 计算通道宽度
        Num width = highest.minus(lowest);
        
        // 计算当前价格在通道中的位置（0-100）
        Num currentPrice = series.getBar(endIndex).getClosePrice();
        Num position = currentPrice.minus(lowest)
            .dividedBy(width)
            .multipliedBy(DecimalNum.valueOf(100));
        
        result.indicatorValue = new BigDecimal(middle.toString());
        result.extraValues.put("upper", new BigDecimal(highest.toString()));
        result.extraValues.put("lower", new BigDecimal(lowest.toString()));
        result.extraValues.put("width", new BigDecimal(width.toString()));
        result.extraValues.put("position", new BigDecimal(position.toString()));
    }
}
