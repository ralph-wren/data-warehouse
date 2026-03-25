package com.crypto.dw.indicators;

import com.crypto.dw.flink.FlinkADSTechnicalIndicatorsJob;
import org.ta4j.core.BarSeries;
import org.ta4j.core.indicators.helpers.ClosePriceIndicator;
import org.ta4j.core.indicators.helpers.VolumeIndicator;
import org.ta4j.core.num.DecimalNum;
import org.ta4j.core.num.Num;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 自定义指标示例 - 成交量加权移动平均线（VWMA）
 * 
 * 计算公式：
 * VWMA = Σ(价格 × 成交量) / Σ(成交量)
 * 
 * 这个指标考虑了成交量的影响，比普通MA更能反映市场真实情况
 * 
 * @author Crypto DW Team
 * @date 2026-03-25
 */
public class CustomVolumeWeightedMA implements FlinkADSTechnicalIndicatorsJob.CustomIndicator {
    
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
        
        ClosePriceIndicator closePrice = new ClosePriceIndicator(series);
        VolumeIndicator volume = new VolumeIndicator(series);
        
        int endIndex = series.getEndIndex();
        int startIndex = Math.max(0, endIndex - period + 1);
        
        // 计算 Σ(价格 × 成交量)
        Num priceVolumeSum = DecimalNum.valueOf(0);
        // 计算 Σ(成交量)
        Num volumeSum = DecimalNum.valueOf(0);
        
        for (int i = startIndex; i <= endIndex; i++) {
            Num price = closePrice.getValue(i);
            Num vol = volume.getValue(i);
            
            priceVolumeSum = priceVolumeSum.plus(price.multipliedBy(vol));
            volumeSum = volumeSum.plus(vol);
        }
        
        // 计算VWMA
        Num vwma = priceVolumeSum.dividedBy(volumeSum);
        
        result.indicatorValue = new BigDecimal(vwma.toString());
        
        // 额外信息：普通MA用于对比
        Num simpleMA = DecimalNum.valueOf(0);
        for (int i = startIndex; i <= endIndex; i++) {
            simpleMA = simpleMA.plus(closePrice.getValue(i));
        }
        simpleMA = simpleMA.dividedBy(DecimalNum.valueOf(period));
        
        result.extraValues.put("simple_ma", new BigDecimal(simpleMA.toString()));
        result.extraValues.put("volume_sum", new BigDecimal(volumeSum.toString()));
    }
}
