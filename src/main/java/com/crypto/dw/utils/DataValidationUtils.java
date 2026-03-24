package com.crypto.dw.utils;

import com.crypto.dw.constants.FlinkConstants;
import java.math.BigDecimal;

/**
 * 数据验证工具类
 * 
 * 提供统一的数据验证方法，避免在各个作业中重复实现相同的验证逻辑。
 * 
 * 功能：
 * 1. 价格验证：检查价格是否在合理范围内
 * 2. 时间戳验证：检查时间戳是否在容忍范围内
 * 3. 字符串验证：检查字符串是否为空
 * 4. 数值验证：检查数值是否为正数
 * 
 * 使用示例：
 * <pre>
 * // 验证价格
 * if (DataValidationUtils.isValidPrice(price)) {
 *     // 价格有效，继续处理
 * }
 * 
 * // 验证时间戳
 * if (DataValidationUtils.isValidTimestamp(timestamp)) {
 *     // 时间戳有效，继续处理
 * }
 * 
 * // 验证字符串
 * if (DataValidationUtils.isNotEmpty(instId)) {
 *     // 字符串非空，继续处理
 * }
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class DataValidationUtils {
    
    // ==================== 价格验证 ====================
    
    /**
     * 验证价格是否合法
     * 
     * 合法条件：
     * 1. 价格不为 null
     * 2. 价格大于 0
     * 3. 价格在合理范围内（0.00000001 ~ 10000000）
     * 
     * @param price 价格（BigDecimal 类型）
     * @return true 表示价格合法，false 表示价格非法
     */
    public static boolean isValidPrice(BigDecimal price) {
        if (price == null) {
            return false;
        }
        
        // 价格必须大于 0
        if (price.compareTo(BigDecimal.ZERO) <= 0) {
            return false;
        }
        
        // 价格必须在合理范围内
        BigDecimal minPrice = BigDecimal.valueOf(FlinkConstants.MIN_PRICE);
        BigDecimal maxPrice = BigDecimal.valueOf(FlinkConstants.MAX_PRICE);
        
        return price.compareTo(minPrice) >= 0 && price.compareTo(maxPrice) <= 0;
    }
    
    /**
     * 验证价格是否合法（Double 类型）
     * 
     * @param price 价格（Double 类型）
     * @return true 表示价格合法，false 表示价格非法
     */
    public static boolean isValidPrice(Double price) {
        if (price == null) {
            return false;
        }
        
        return price > 0 && 
               price >= FlinkConstants.MIN_PRICE && 
               price <= FlinkConstants.MAX_PRICE;
    }
    
    /**
     * 验证价格是否合法（String 类型）
     * 
     * @param priceStr 价格字符串
     * @return true 表示价格合法，false 表示价格非法
     */
    public static boolean isValidPrice(String priceStr) {
        if (priceStr == null || priceStr.trim().isEmpty()) {
            return false;
        }
        
        try {
            BigDecimal price = new BigDecimal(priceStr);
            return isValidPrice(price);
        } catch (NumberFormatException e) {
            return false;
        }
    }
    
    // ==================== 时间戳验证 ====================
    
    /**
     * 验证时间戳是否合法
     * 
     * 合法条件：
     * 1. 时间戳大于 0
     * 2. 时间戳与当前时间的差值在容忍范围内（默认 1 小时）
     * 
     * 注意：
     * - 时间戳单位为毫秒
     * - 容忍范围可以通过 FlinkConstants.TIMESTAMP_TOLERANCE_MS 配置
     * 
     * @param timestamp 时间戳（毫秒）
     * @return true 表示时间戳合法，false 表示时间戳非法
     */
    public static boolean isValidTimestamp(long timestamp) {
        // 时间戳必须大于 0
        if (timestamp <= 0) {
            return false;
        }
        
        // 计算时间戳与当前时间的差值
        long now = System.currentTimeMillis();
        long diff = Math.abs(now - timestamp);
        
        // 差值必须在容忍范围内
        return diff < FlinkConstants.TIMESTAMP_TOLERANCE_MS;
    }
    
    /**
     * 验证时间戳是否合法（自定义容忍度）
     * 
     * @param timestamp 时间戳（毫秒）
     * @param toleranceMs 容忍度（毫秒）
     * @return true 表示时间戳合法，false 表示时间戳非法
     */
    public static boolean isValidTimestamp(long timestamp, long toleranceMs) {
        if (timestamp <= 0) {
            return false;
        }
        
        long now = System.currentTimeMillis();
        long diff = Math.abs(now - timestamp);
        
        return diff < toleranceMs;
    }
    
    // ==================== 字符串验证 ====================
    
    /**
     * 验证字符串是否非空
     * 
     * 非空条件：
     * 1. 字符串不为 null
     * 2. 去除空格后长度大于 0
     * 
     * @param str 字符串
     * @return true 表示字符串非空，false 表示字符串为空
     */
    public static boolean isNotEmpty(String str) {
        return str != null && !str.trim().isEmpty();
    }
    
    /**
     * 验证字符串是否为空
     * 
     * @param str 字符串
     * @return true 表示字符串为空，false 表示字符串非空
     */
    public static boolean isEmpty(String str) {
        return !isNotEmpty(str);
    }
    
    /**
     * 验证字符串长度是否在指定范围内
     * 
     * @param str 字符串
     * @param minLength 最小长度
     * @param maxLength 最大长度
     * @return true 表示长度合法，false 表示长度非法
     */
    public static boolean isValidLength(String str, int minLength, int maxLength) {
        if (str == null) {
            return false;
        }
        
        int length = str.length();
        return length >= minLength && length <= maxLength;
    }
    
    // ==================== 数值验证 ====================
    
    /**
     * 验证数值是否为正数
     * 
     * @param value 数值
     * @return true 表示为正数，false 表示为负数或零
     */
    public static boolean isPositive(BigDecimal value) {
        return value != null && value.compareTo(BigDecimal.ZERO) > 0;
    }
    
    /**
     * 验证数值是否为正数（Double 类型）
     * 
     * @param value 数值
     * @return true 表示为正数，false 表示为负数或零
     */
    public static boolean isPositive(Double value) {
        return value != null && value > 0;
    }
    
    /**
     * 验证数值是否为正数（Long 类型）
     * 
     * @param value 数值
     * @return true 表示为正数，false 表示为负数或零
     */
    public static boolean isPositive(Long value) {
        return value != null && value > 0;
    }
    
    /**
     * 验证数值是否在指定范围内
     * 
     * @param value 数值
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @return true 表示在范围内，false 表示超出范围
     */
    public static boolean isInRange(BigDecimal value, BigDecimal min, BigDecimal max) {
        if (value == null || min == null || max == null) {
            return false;
        }
        
        return value.compareTo(min) >= 0 && value.compareTo(max) <= 0;
    }
    
    /**
     * 验证数值是否在指定范围内（Double 类型）
     * 
     * @param value 数值
     * @param min 最小值（包含）
     * @param max 最大值（包含）
     * @return true 表示在范围内，false 表示超出范围
     */
    public static boolean isInRange(Double value, Double min, Double max) {
        if (value == null || min == null || max == null) {
            return false;
        }
        
        return value >= min && value <= max;
    }
    
    // ==================== 交易对验证 ====================
    
    /**
     * 验证交易对格式是否正确
     * 
     * 正确格式：BASE-QUOTE（如 BTC-USDT）
     * - BASE: 基础货币（如 BTC）
     * - QUOTE: 计价货币（如 USDT）
     * - 中间用短横线连接
     * 
     * @param instId 交易对 ID
     * @return true 表示格式正确，false 表示格式错误
     */
    public static boolean isValidInstId(String instId) {
        if (isEmpty(instId)) {
            return false;
        }
        
        // 检查是否包含短横线
        if (!instId.contains("-")) {
            return false;
        }
        
        // 分割交易对
        String[] parts = instId.split("-");
        
        // 必须是两部分
        if (parts.length != 2) {
            return false;
        }
        
        // 每部分都不能为空
        return isNotEmpty(parts[0]) && isNotEmpty(parts[1]);
    }
    
    // ==================== 私有构造函数 ====================
    
    /**
     * 私有构造函数，防止实例化
     * 工具类不应该被实例化，所有方法都是静态的
     */
    private DataValidationUtils() {
        throw new AssertionError("工具类不能被实例化");
    }
}
