package com.crypto.dw.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * SQL 文件加载工具类
 * 
 * 功能：从 classpath 或文件系统加载 SQL 文件
 * 
 * 优势：
 * 1. SQL 与代码分离，便于维护和修改
 * 2. 支持 SQL 注释，提高可读性
 * 3. 支持多行 SQL，格式清晰
 * 4. 便于 SQL 版本管理
 * 
 * 使用示例：
 * <pre>
 * // 从 classpath 加载 SQL
 * String sql = SqlFileLoader.loadSql("sql/flink/dwd_insert.sql");
 * 
 * // 执行 SQL
 * tableEnv.executeSql(sql);
 * </pre>
 * 
 * @author Kiro AI Assistant
 * @date 2026-03-24
 */
public class SqlFileLoader {
    
    private static final Logger logger = LoggerFactory.getLogger(SqlFileLoader.class);
    
    /**
     * 从 classpath 加载 SQL 文件
     * 
     * @param sqlFilePath SQL 文件路径（相对于 classpath）
     * @return SQL 内容
     * @throws RuntimeException 如果文件不存在或读取失败
     */
    public static String loadSql(String sqlFilePath) {
        logger.info("加载 SQL 文件: {}", sqlFilePath);
        
        try (InputStream inputStream = SqlFileLoader.class.getClassLoader().getResourceAsStream(sqlFilePath)) {
            if (inputStream == null) {
                throw new RuntimeException("SQL 文件不存在: " + sqlFilePath);
            }
            
            // 读取文件内容
            String sql = new BufferedReader(new InputStreamReader(inputStream, StandardCharsets.UTF_8))
                    .lines()
                    .collect(Collectors.joining("\n"));
            
            logger.info("✅ SQL 文件加载成功，长度: {} 字符", sql.length());
            return sql;
            
        } catch (Exception e) {
            logger.error("❌ 加载 SQL 文件失败: {}", sqlFilePath, e);
            throw new RuntimeException("加载 SQL 文件失败: " + sqlFilePath, e);
        }
    }
}
