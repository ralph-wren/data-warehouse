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
 * 异步 CSV 日志写入器
 * 
 * 功能：
 * 1. 使用独立线程池异步写入 CSV 文件
 * 2. 使用阻塞队列缓冲日志，避免阻塞主线程
 * 3. 自动按小时滚动日志文件
 * 4. 优雅关闭，确保所有日志都被写入
 * 
 * 性能优化：
 * - 异步写入，不阻塞 Flink 算子
 * - 批量写入，减少磁盘 I/O
 * - 大缓冲区（64KB），提升写入效率
 */
public class AsyncCsvWriter implements AutoCloseable {
    
    private static final Logger logger = LoggerFactory.getLogger(AsyncCsvWriter.class);
    
    private final String logDir;
    private final String filePrefix;
    private final String[] headers;
    private final int queueCapacity;
    
    private final BlockingQueue<String> logQueue;
    private final ExecutorService executorService;
    private volatile boolean running = true;
    
    private BufferedWriter writer;
    private String currentLogDate;
    private int currentLogHour;
    
    /**
     * 构造函数
     * 
     * @param logDir 日志目录
     * @param filePrefix 文件名前缀（如 "order_detail"）
     * @param headers CSV 表头
     * @param queueCapacity 队列容量（建议 10000）
     */
    public AsyncCsvWriter(String logDir, String filePrefix, String[] headers, int queueCapacity) {
        this.logDir = logDir;
        this.filePrefix = filePrefix;
        this.headers = headers;
        this.queueCapacity = queueCapacity;
        
        // 创建阻塞队列
        this.logQueue = new LinkedBlockingQueue<>(queueCapacity);
        
        // 创建单线程执行器
        this.executorService = Executors.newSingleThreadExecutor(r -> {
            Thread thread = new Thread(r, "AsyncCsvWriter-" + filePrefix);
            thread.setDaemon(false);  // 非守护线程，确保日志写完
            return thread;
        });
        
        // 启动异步写入任务
        executorService.submit(this::writeLoop);
        
        logger.info("✓ 异步 CSV 写入器已启动: {}", filePrefix);
    }
    
    /**
     * 异步写入日志
     * 
     * @param logLine 日志行（不包含换行符）
     */
    public void writeAsync(String logLine) {
        try {
            // 非阻塞方式添加到队列
            if (!logQueue.offer(logLine, 100, TimeUnit.MILLISECONDS)) {
                logger.warn("日志队列已满，丢弃日志: {}", logLine.substring(0, Math.min(50, logLine.length())));
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("写入日志队列被中断: {}", e.getMessage());
        }
    }
    
    /**
     * 异步写入循环（在独立线程中运行）
     */
    private void writeLoop() {
        try {
            while (running || !logQueue.isEmpty()) {
                try {
                    // 从队列中取出日志（阻塞等待）
                    String logLine = logQueue.poll(1, TimeUnit.SECONDS);
                    
                    if (logLine != null) {
                        // 检查是否需要切换日志文件
                        checkAndRotateFile();
                        
                        // 写入日志
                        if (writer != null) {
                            writer.write(logLine);
                            writer.write('\n');
                        }
                    }
                    
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.warn("日志写入线程被中断");
                    break;
                } catch (IOException e) {
                    logger.error("写入日志失败: {}", e.getMessage(), e);
                }
            }
            
            // 刷新并关闭文件
            if (writer != null) {
                writer.flush();
                writer.close();
            }
            
            logger.info("✓ 异步 CSV 写入器已停止: {}", filePrefix);
            
        } catch (Exception e) {
            logger.error("日志写入循环异常: {}", e.getMessage(), e);
        }
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
            }
            
            logger.info("✓ 日志文件已创建: {}", logFile.getAbsolutePath());
        }
    }
    
    /**
     * 优雅关闭
     */
    @Override
    public void close() {
        logger.info("正在关闭异步 CSV 写入器: {}", filePrefix);
        
        // 停止接收新日志
        running = false;
        
        // 等待队列中的日志写完
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
        
        logger.info("✓ 异步 CSV 写入器已关闭: {}", filePrefix);
    }
    
    /**
     * 获取队列中待写入的日志数量
     */
    public int getPendingCount() {
        return logQueue.size();
    }
}
