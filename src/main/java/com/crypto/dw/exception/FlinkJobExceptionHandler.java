package com.crypto.dw.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Flink 作业统一异常处理器
 * 
 * 提供统一的异常处理逻辑，包括：
 * 1. 格式化的错误日志输出
 * 2. 异常信息的详细记录
 * 3. 可扩展的告警机制
 * 4. 可扩展的监控指标记录
 * 
 * 优势：
 * 1. 统一的错误日志格式，便于日志分析
 * 2. 集中管理异常处理逻辑，便于维护
 * 3. 便于添加告警和监控功能
 * 4. 提高代码可读性和一致性
 * 
 * 使用示例：
 * <pre>
 * public static void main(String[] args) {
 *     try {
 *         // 作业逻辑
 *         env.execute("My Flink Job");
 *     } catch (Exception e) {
 *         // 统一异常处理
 *         FlinkJobExceptionHandler.handleException("My Flink Job", e);
 *     }
 * }
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class FlinkJobExceptionHandler {
    
    private static final Logger logger = LoggerFactory.getLogger(FlinkJobExceptionHandler.class);
    
    /**
     * 处理 Flink 作业异常
     * 
     * 功能：
     * 1. 记录详细的错误日志
     * 2. 格式化输出异常信息
     * 3. 可扩展告警功能
     * 4. 可扩展监控指标记录
     * 
     * @param jobName 作业名称
     * @param e 异常对象
     * @throws RuntimeException 重新抛出异常，让作业失败
     */
    public static void handleException(String jobName, Exception e) {
        logger.error("==========================================");
        logger.error("Flink 作业失败: {}", jobName);
        logger.error("==========================================");
        
        // 记录异常类型
        logger.error("异常类型: {}", e.getClass().getName());
        
        // 记录异常消息
        logger.error("异常消息: {}", e.getMessage());
        
        // 记录异常堆栈（前 10 行）
        logger.error("异常堆栈:");
        StackTraceElement[] stackTrace = e.getStackTrace();
        int maxLines = Math.min(10, stackTrace.length);
        for (int i = 0; i < maxLines; i++) {
            logger.error("  at {}", stackTrace[i]);
        }
        if (stackTrace.length > maxLines) {
            logger.error("  ... {} more", stackTrace.length - maxLines);
        }
        
        // 记录根本原因（如果存在）
        Throwable cause = e.getCause();
        if (cause != null && cause != e) {
            logger.error("根本原因: {}", cause.getClass().getName());
            logger.error("根本原因消息: {}", cause.getMessage());
        }
        
        logger.error("==========================================");
        
        // TODO: 发送告警（可选）
        // 可以集成钉钉、企业微信、邮件等告警方式
        // alertService.sendAlert(jobName, e);
        
        // TODO: 记录到监控系统（可选）
        // 可以集成 Prometheus、Grafana 等监控系统
        // metricsService.recordFailure(jobName, e);
        
        // 重新抛出异常，让作业失败
        throw new RuntimeException("Flink 作业失败: " + jobName, e);
    }
    
    /**
     * 处理 Flink 作业异常（带上下文信息）
     * 
     * @param jobName 作业名称
     * @param context 上下文信息（如配置、参数等）
     * @param e 异常对象
     * @throws RuntimeException 重新抛出异常，让作业失败
     */
    public static void handleException(String jobName, String context, Exception e) {
        logger.error("==========================================");
        logger.error("Flink 作业失败: {}", jobName);
        logger.error("上下文信息: {}", context);
        logger.error("==========================================");
        
        // 调用基础异常处理方法
        handleException(jobName, e);
    }
    
    /**
     * 记录警告信息
     * 
     * 用于记录非致命错误，不会导致作业失败
     * 
     * @param jobName 作业名称
     * @param message 警告消息
     */
    public static void logWarning(String jobName, String message) {
        logger.warn("==========================================");
        logger.warn("Flink 作业警告: {}", jobName);
        logger.warn("警告消息: {}", message);
        logger.warn("==========================================");
    }
    
    /**
     * 记录警告信息（带异常）
     * 
     * @param jobName 作业名称
     * @param message 警告消息
     * @param e 异常对象
     */
    public static void logWarning(String jobName, String message, Exception e) {
        logger.warn("==========================================");
        logger.warn("Flink 作业警告: {}", jobName);
        logger.warn("警告消息: {}", message);
        logger.warn("异常类型: {}", e.getClass().getName());
        logger.warn("异常消息: {}", e.getMessage());
        logger.warn("==========================================");
    }
    
    /**
     * 记录信息日志
     * 
     * 用于记录作业的重要信息
     * 
     * @param jobName 作业名称
     * @param message 信息消息
     */
    public static void logInfo(String jobName, String message) {
        logger.info("==========================================");
        logger.info("Flink 作业信息: {}", jobName);
        logger.info("信息消息: {}", message);
        logger.info("==========================================");
    }
    
    /**
     * 记录作业启动信息
     * 
     * @param jobName 作业名称
     * @param config 配置信息
     */
    public static void logJobStart(String jobName, String config) {
        logger.info("==========================================");
        logger.info("Flink 作业启动: {}", jobName);
        logger.info("配置信息: {}", config);
        logger.info("启动时间: {}", new java.util.Date());
        logger.info("==========================================");
    }
    
    /**
     * 记录作业完成信息
     * 
     * @param jobName 作业名称
     * @param duration 运行时长（毫秒）
     */
    public static void logJobComplete(String jobName, long duration) {
        logger.info("==========================================");
        logger.info("Flink 作业完成: {}", jobName);
        logger.info("运行时长: {} 毫秒 ({} 秒)", duration, duration / 1000);
        logger.info("完成时间: {}", new java.util.Date());
        logger.info("==========================================");
    }
    
    /**
     * 私有构造函数，防止实例化
     * 工具类不应该被实例化，所有方法都是静态的
     */
    private FlinkJobExceptionHandler() {
        throw new AssertionError("工具类不能被实例化");
    }
}
