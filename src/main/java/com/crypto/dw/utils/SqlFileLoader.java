package com.crypto.dw.utils;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * SQL 文件加载工具类
 * 
 * 功能：从 classpath 或文件系统加载 SQL 文件，支持参数替换
 * 
 * 优势：
 * 1. SQL 与代码分离，便于维护和修改
 * 2. 支持 SQL 注释，提高可读性
 * 3. 支持多行 SQL，格式清晰
 * 4. 便于 SQL 版本管理
 * 5. 支持参数替换，实现 SQL 模板化
 * 
 * 使用示例：
 * <pre>
 * // 加载普通 SQL
 * String sql = SqlFileLoader.loadSql("sql/flink/dwd_insert.sql");
 * 
 * // 加载 SQL 模板并替换参数
 * Map<String, String> params = new HashMap<>();
 * params.put("tableName", "kafka_source");
 * params.put("schema", "id INT, name STRING");
 * String ddl = SqlFileLoader.loadSqlWithParams("sql/flink/ddl/kafka_source.sql", params);
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
    
    /**
     * 从 classpath 加载 SQL 文件并替换参数
     * 
     * 参数格式：${paramName}
     * 
     * 示例：
     * SQL 模板：CREATE TABLE ${tableName} (${schema})
     * 参数：{"tableName": "my_table", "schema": "id INT"}
     * 结果：CREATE TABLE my_table (id INT)
     * 
     * @param sqlFilePath SQL 文件路径（相对于 classpath）
     * @param params 参数 Map，key 为参数名，value 为参数值
     * @return 替换参数后的 SQL 内容
     * @throws RuntimeException 如果文件不存在或读取失败
     */
    public static String loadSqlWithParams(String sqlFilePath, Map<String, String> params) {
        logger.info("加载 SQL 模板文件: {}", sqlFilePath);
        logger.info("参数数量: {}", params.size());
        
        // 加载 SQL 文件
        String sql = loadSql(sqlFilePath);
        
        // 替换参数
        String result = sql;
        for (Map.Entry<String, String> entry : params.entrySet()) {
            String paramName = entry.getKey();
            String paramValue = entry.getValue();
            
            // 替换 ${paramName} 为实际值
            String placeholder = "${" + paramName + "}";
            result = result.replace(placeholder, paramValue);
            
            logger.debug("替换参数: {} = {}", paramName, paramValue);
        }
        
        logger.info("✅ SQL 参数替换完成");
        return result;
    }
    
    /**
     * 验证 SQL 中是否还有未替换的参数
     * 
     * @param sql SQL 内容
     * @return true 表示有未替换的参数，false 表示所有参数都已替换
     */
    public static boolean hasUnresolvedParams(String sql) {
        return sql.contains("${");
    }
    
    /**
     * 获取 SQL 中所有未替换的参数名称
     * 
     * @param sql SQL 内容
     * @return 未替换的参数名称列表
     */
    public static java.util.List<String> getUnresolvedParams(String sql) {
        java.util.List<String> unresolvedParams = new java.util.ArrayList<>();
        
        int startIndex = 0;
        while ((startIndex = sql.indexOf("${", startIndex)) != -1) {
            int endIndex = sql.indexOf("}", startIndex);
            if (endIndex != -1) {
                String paramName = sql.substring(startIndex + 2, endIndex);
                unresolvedParams.add(paramName);
                startIndex = endIndex + 1;
            } else {
                break;
            }
        }
        
        return unresolvedParams;
    }
}
