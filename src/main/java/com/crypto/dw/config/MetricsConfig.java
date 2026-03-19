package com.crypto.dw.config;

import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.MetricOptions;

/**
 * Flink Metrics 配置
 * 用于配置 Prometheus Reporter
 */
public class MetricsConfig {
    
    /**
     * 配置 Prometheus Pushgateway Reporter
     * 
     * @param config Flink Configuration
     * @param pushgatewayHost Pushgateway 主机地址
     * @param pushgatewayPort Pushgateway 端口
     * @param jobName 作业名称
     */
    public static void configurePushgatewayReporter(
            Configuration config, 
            String pushgatewayHost, 
            int pushgatewayPort,
            String jobName) {
        
        // 启用 Prometheus Pushgateway Reporter（使用 factory class）
        // 注意：Flink 1.17+ 需要使用 factory.class 而不是 class
        config.setString("metrics.reporter.promgateway.factory.class", 
            "org.apache.flink.metrics.prometheus.PrometheusPushGatewayReporterFactory");
        
        // Pushgateway 地址
        config.setString("metrics.reporter.promgateway.host", pushgatewayHost);
        config.setString("metrics.reporter.promgateway.port", String.valueOf(pushgatewayPort));
        
        // 作业名称（用于 Prometheus 标签）
        config.setString("metrics.reporter.promgateway.jobName", jobName);
        
        // 推送间隔（秒）
        config.setString("metrics.reporter.promgateway.interval", "15 SECONDS");
        
        // 是否删除关闭时的指标
        config.setString("metrics.reporter.promgateway.deleteOnShutdown", "false");
    }
    
    /**
     * 配置 Prometheus HTTP Server Reporter
     * 
     * @param config Flink Configuration
     * @param port HTTP Server 端口
     */
    public static void configureHttpServerReporter(Configuration config, int port) {
        // 启用 Prometheus HTTP Server Reporter（使用 factory class）
        config.setString("metrics.reporter.prom.factory.class", 
            "org.apache.flink.metrics.prometheus.PrometheusReporterFactory");
        
        // HTTP Server 端口
        config.setString("metrics.reporter.prom.port", String.valueOf(port));
    }
    
    /**
     * 配置通用 Metrics 选项
     * 
     * @param config Flink Configuration
     */
    public static void configureCommonMetrics(Configuration config) {
        // 启用系统资源指标
        config.setBoolean(MetricOptions.SYSTEM_RESOURCE_METRICS, true);
        
        // 系统资源指标探测间隔（毫秒）
        config.setLong(MetricOptions.SYSTEM_RESOURCE_METRICS_PROBING_INTERVAL, 5000L);
        
        // Metrics 作用域格式
        config.setString("metrics.scope.jm", "<host>.jobmanager");
        config.setString("metrics.scope.jm.job", "<host>.jobmanager.<job_name>");
        config.setString("metrics.scope.tm", "<host>.taskmanager.<tm_id>");
        config.setString("metrics.scope.tm.job", "<host>.taskmanager.<tm_id>.<job_name>");
        config.setString("metrics.scope.task", "<host>.taskmanager.<tm_id>.<job_name>.<task_name>.<subtask_index>");
        config.setString("metrics.scope.operator", "<host>.taskmanager.<tm_id>.<job_name>.<operator_name>.<subtask_index>");
    }
}
