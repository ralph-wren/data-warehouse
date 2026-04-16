package com.crypto.dw.flink.async;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;

/**
 * 同步 CSV 日志写入器（使用线程池）
 * 
 * 功能：
 * 1. 使用线程池同步写入 CSV 文件（确保写入成功）
 * 2. 自动按小时滚动日志文件
 * 3. 线程安全，支持并发写入
 * 
 * 与 AsyncCsvWriter 的区别：
 * - AsyncCsvWriter: 使用队列异步写入，可能丢失数据
 * - SyncCsvWriter: 使用线程池同步写入，确保数据写入成功
 */
public class SyncCsvWriter implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(SyncCsvWriter.class);
    
    private final String logDir;
    private final String filePrefix;
    private final String[] headers;
    
    private final ExecutorService executorService;
    private final Object lock = new Object();
    
    private BufferedWriter writer;
    private String currentLogDate;
    private int currentLogHour;
    
    /**
     * 构造函数
     * 
     * @param logDir 日志目录
     * @param filePrefix 文件名前缀（如 "order_detail"）
     * @param headers CSV 表头
     * @param threadPoolSize 线程池大小（建议 2-4）
     */
    public SyncCsvWriter(String logDir, String filePrefix, String[] headers, int threadPoolSize) {
        this.logDir = logDir;
        this.filePrefix = filePrefix;
        this.headers = headers;
        
        // 创建固定大小的线程池
        this.executorService = Executors.newFixedThreadPool(threadPoolSize, r -> {
            Thread thread = new Thread(r, "SyncCsvWriter-" + filePrefix);
            thread.setDaemon(false);  // 非守护线程，确保日志写完
            return thread;
        });
        
        logger.info("✓ 同步 CSV 写入器已启动: {} (线程池大小: {})", filePrefix, threadPoolSize);
    }
    
    /**
     * 同步写入日志（使用线程池）
     * 
     * @param logLine 日志行（不包含换行符）
     */
    public void write(String logLine) {
        // 提交到线程池执行
        executorService.submit(() -> {
            try {
                synchronized (lock) {
                    // 检查是否需要切换日志文件
                    checkAndRotateFile();
                    
                    // 写入日志
                    if (writer != null) {
                        writer.write(logLine);
                        writer.write('\n');
                        writer.flush();  // 立即刷新，确保写入成功
                    }
                }
            } catch (IOException e) {
                logger.error("写入日志失败: {}", e.getMessage(), e);
            }
        });
    }
    
    /**
     * 检查并切换日志文件（按小时滚动）
     */
    private void checkAndRotateFile() throws IOException {
        LocalDateTime now = LocalDateTime.now();
        String today = now.format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        int hour = now.getHour();
        
        // 如果日期或小时变了，关闭旧文件，创建新文件
        if (writer != null && (!today.equals(currentLogDate) || hour != currentLogHour)) {
            writer.flush();
            writer.close();
            writer = null;
        }
        
        if (writer == null) {
            currentLogDate = today;
            currentLogHour = hour;
            
            // 确保日志目录存在
            File dir = new File(logDir);
            if (!dir.exists()) {
                dir.mkdirs();
            }
            
            String hourStr = String.format("%02d", hour);
            File logFile = new File(dir, filePrefix + "_" + today + hourStr + ".csv");
            boolean isNewFile = !logFile.exists();
            
            // 创建 BufferedWriter，使用 64KB 缓冲区
            writer = new BufferedWriter(new FileWriter(logFile, true), 65536);
            
            // 如果是新文件，写入表头
            if (isNewFile && headers != null) {
                writer.write(String.join(",", headers));
                writer.write('\n');
                writer.flush();
            }
            
//            logger.info("✓ 日志文件已创建: {}", logFile.getAbsolutePath());
        }
    }
    
    /**
     * 优雅关闭
     */
    @Override
    public void close() {
        logger.info("正在关闭同步 CSV 写入器: {}", filePrefix);
        
        // 等待所有任务完成
        try {
            executorService.shutdown();
            if (!executorService.awaitTermination(30, TimeUnit.SECONDS)) {
                logger.warn("日志写入超时，强制关闭");
                executorService.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            executorService.shutdownNow();
        }
        
        // 关闭文件
        synchronized (lock) {
            if (writer != null) {
                try {
                    writer.flush();
                    writer.close();
                } catch (IOException e) {
                    logger.error("关闭日志文件失败: {}", e.getMessage(), e);
                }
            }
        }
        
        logger.info("✓ 同步 CSV 写入器已关闭: {}", filePrefix);
    }
}
