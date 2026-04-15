package com.crypto.dw.utils;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 价差率计算工具类
 *
 * 统一口径：
 * 1. 价差使用绝对值：|swap - spot|
 * 2. 分母使用较小价格：min(spot, swap)
 * 3. 返回百分比值（例如 0.64 表示 0.64%）
 */
public class SpreadRateUtils {

    /**
     * 计算价差率（百分比）
     *
     * @param spotPrice 现货价格
     * @param swapPrice 合约价格
     * @param scale 保留小数位
     * @return 价差率百分比（如 0.640000），异常时返回 0
     */
    public static BigDecimal calculateSpreadRatePercent(
            BigDecimal spotPrice,
            BigDecimal swapPrice,
            int scale) {
        if (spotPrice == null || swapPrice == null) {
            return BigDecimal.ZERO;
        }

        BigDecimal denominator = spotPrice.min(swapPrice);
        if (denominator.compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }

        BigDecimal spreadAbs = swapPrice.subtract(spotPrice).abs();
        return spreadAbs
            .divide(denominator, scale, RoundingMode.HALF_UP)
            .multiply(new BigDecimal("100"));
    }

    private SpreadRateUtils() {
        throw new AssertionError("工具类不能被实例化");
    }
}
