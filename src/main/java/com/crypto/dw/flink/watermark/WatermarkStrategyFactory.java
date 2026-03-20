package com.crypto.dw.flink.watermark;

import org.apache.flink.api.common.eventtime.SerializableTimestampAssigner;
import org.apache.flink.api.common.eventtime.WatermarkStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watermark 策略工厂类
 * 提供不同场景下的 Watermark 生成策略
 * 
 * 功能说明：
 * 1. 无 Watermark 策略：适用于不需要事件时间处理的场景
 * 2. 有界乱序 Watermark：适用于数据有一定延迟但延迟有界的场景
 * 3. 单调递增 Watermark：适用于数据严格按时间顺序到达的场景
 * 
 * 时间戳提取规则：
 * - 支持 "timestamp" 或 "ts" 字段
 * - 使用正则表达式提取后面的 13 位数字（毫秒级时间戳）
 * - 正则模式：(?:timestamp|ts)["']?\s*[:=]\s*["']?(\d{13})
 * 
 * @author Crypto DW Team
 * @date 2026-03-20
 */
public class WatermarkStrategyFactory {
    
    private static final Logger logger = LoggerFactory.getLogger(WatermarkStrategyFactory.class);
    
    /**
     * 时间戳提取正则表达式
     * 匹配模式：
     * - "timestamp": 1234567890123
     * - "ts": 1234567890123
     * - "timestamp":1234567890123
     * - "ts":1234567890123
     * - timestamp=1234567890123
     * - ts=1234567890123
     */
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
        "(?:timestamp|ts)[\"']?\\s*[:=]\\s*[\"']?(\\d{13})"
    );
    
    /**
     * 从字符串中提取时间戳（使用正则表达式）
     * 
     * 支持的格式：
     * - "timestamp": 1234567890123
     * - "ts": 1234567890123
     * - "timestamp":1234567890123
     * - "ts":1234567890123
     * - timestamp=1234567890123
     * - ts=1234567890123
     * 
     * @param element 数据字符串
     * @return 时间戳（毫秒），提取失败返回当前时间
     */
    private static long extractTimestamp(String element) {
        try {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(element);
            if (matcher.find()) {
                String timestampStr = matcher.group(1);
                return Long.parseLong(timestampStr);
            }
        } catch (Exception e) {
            logger.warn("无法从数据中提取时间戳，使用当前时间: {}", e.getMessage());
        }
        // 如果提取失败，使用当前时间
        return System.currentTimeMillis();
    }
    
    /**
     * 创建无 Watermark 策略
     * 适用场景：不需要基于事件时间的窗口计算
     * 
     * @param <T> 数据类型
     * @return WatermarkStrategy 实例
     */
    public static <T> WatermarkStrategy<T> noWatermarks() {
        logger.info("使用无 Watermark 策略");
        return WatermarkStrategy.noWatermarks();
    }
    
    /**
     * 创建有界乱序 Watermark 策略（针对字符串类型数据）
     * 适用场景：数据可能乱序到达，但延迟在可控范围内
     * 
     * 使用正则表达式从 JSON 字符串中提取时间戳：
     * - 匹配 "timestamp" 或 "ts" 字段
     * - 提取后面的 13 位数字（毫秒级时间戳）
     * 
     * @param maxOutOfOrderness 最大乱序时间（毫秒）
     * @return WatermarkStrategy 实例
     */
    public static WatermarkStrategy<String> forBoundedOutOfOrdernessString(long maxOutOfOrderness) {
        logger.info("使用有界乱序 Watermark 策略，最大乱序时间: {} ms", maxOutOfOrderness);
        
        return WatermarkStrategy
            .<String>forBoundedOutOfOrderness(Duration.ofMillis(maxOutOfOrderness))
            .withTimestampAssigner(new SerializableTimestampAssigner<String>() {
                @Override
                public long extractTimestamp(String element, long recordTimestamp) {
                    // 使用正则表达式提取时间戳
                    return WatermarkStrategyFactory.extractTimestamp(element);
                }
            });
    }
    
    /**
     * 创建单调递增 Watermark 策略（针对字符串类型数据）
     * 适用场景：数据严格按时间顺序到达，无乱序
     * 
     * 使用正则表达式从 JSON 字符串中提取时间戳：
     * - 匹配 "timestamp" 或 "ts" 字段
     * - 提取后面的 13 位数字（毫秒级时间戳）
     * 
     * @return WatermarkStrategy 实例
     */
    public static WatermarkStrategy<String> forMonotonousTimestampsString() {
        logger.info("使用单调递增 Watermark 策略");
        
        return WatermarkStrategy
            .<String>forMonotonousTimestamps()
            .withTimestampAssigner(new SerializableTimestampAssigner<String>() {
                @Override
                public long extractTimestamp(String element, long recordTimestamp) {
                    // 使用正则表达式提取时间戳
                    return WatermarkStrategyFactory.extractTimestamp(element);
                }
            });
    }
    
    /**
     * 创建有界乱序 Watermark 策略（通用类型，需要提供时间戳提取器）
     * 
     * @param <T> 数据类型
     * @param maxOutOfOrderness 最大乱序时间（毫秒）
     * @param timestampAssigner 时间戳提取器
     * @return WatermarkStrategy 实例
     */
    public static <T> WatermarkStrategy<T> forBoundedOutOfOrderness(
            long maxOutOfOrderness,
            SerializableTimestampAssigner<T> timestampAssigner) {
        
        logger.info("使用有界乱序 Watermark 策略（通用），最大乱序时间: {} ms", maxOutOfOrderness);
        
        return WatermarkStrategy
            .<T>forBoundedOutOfOrderness(Duration.ofMillis(maxOutOfOrderness))
            .withTimestampAssigner(timestampAssigner);
    }
    
    /**
     * 创建单调递增 Watermark 策略（通用类型，需要提供时间戳提取器）
     * 
     * @param <T> 数据类型
     * @param timestampAssigner 时间戳提取器
     * @return WatermarkStrategy 实例
     */
    public static <T> WatermarkStrategy<T> forMonotonousTimestamps(
            SerializableTimestampAssigner<T> timestampAssigner) {
        
        logger.info("使用单调递增 Watermark 策略（通用）");
        
        return WatermarkStrategy
            .<T>forMonotonousTimestamps()
            .withTimestampAssigner(timestampAssigner);
    }
    
    /**
     * 创建空闲检测 Watermark 策略
     * 适用场景：某些分区可能长时间没有数据，需要标记为空闲
     * 
     * @param <T> 数据类型
     * @param baseStrategy 基础 Watermark 策略
     * @param idleTimeout 空闲超时时间（毫秒）
     * @return WatermarkStrategy 实例
     */
    public static <T> WatermarkStrategy<T> withIdleness(
            WatermarkStrategy<T> baseStrategy,
            long idleTimeout) {
        
        logger.info("添加空闲检测，超时时间: {} ms", idleTimeout);
        
        return baseStrategy.withIdleness(Duration.ofMillis(idleTimeout));
    }
}
