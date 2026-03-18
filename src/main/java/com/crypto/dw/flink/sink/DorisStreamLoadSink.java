package com.crypto.dw.flink.sink;

import com.alibaba.fastjson2.JSONObject;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.sink.RichSinkFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * 自定义 Doris Stream Load Sink
 * 直接使用 HTTP Stream Load API 写入数据,绕过 schema 映射问题
 */
public class DorisStreamLoadSink extends RichSinkFunction<String> {

    private static final Logger logger = LoggerFactory.getLogger(DorisStreamLoadSink.class);

    private final String feHttpUrl;
    private final String database;
    private final String table;
    private final String username;
    private final String password;
    private final int batchSize;
    private final long batchIntervalMs;
    private final int maxRetries;  // 最大重试次数

    private List<String> buffer;
    private long lastFlushTime;

    /**
     * 构造函数
     */
    public DorisStreamLoadSink(String feHttpUrl, String database, String table,
                               String username, String password,
                               int batchSize, long batchIntervalMs) {
        this.feHttpUrl = feHttpUrl;
        this.database = database;
        this.table = table;
        this.username = username;
        this.password = password;
        this.batchSize = batchSize;
        this.batchIntervalMs = batchIntervalMs;
        this.maxRetries = 3;  // 默认重试 3 次
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        // 初始化缓冲区
        this.buffer = new ArrayList<>(batchSize);
        this.lastFlushTime = System.currentTimeMillis();
        logger.info("DorisStreamLoadSink opened: {}:{}.{}", feHttpUrl, database, table);
    }

    @Override
    public void invoke(String value, Context context) throws Exception {
        // 添加到缓冲区
        buffer.add(value);

        // 检查是否需要刷新
        long currentTime = System.currentTimeMillis();
        boolean shouldFlush = buffer.size() >= batchSize ||
                (currentTime - lastFlushTime) >= batchIntervalMs;

        if (shouldFlush) {
            flush();
        }
    }

    @Override
    public void close() throws Exception {
        // 关闭前刷新剩余数据
        if (!buffer.isEmpty()) {
            flush();
        }
        super.close();
        logger.info("DorisStreamLoadSink closed");
    }

    /**
     * 刷新缓冲区数据到 Doris (带重试机制)
     */
    private void flush() throws Exception {
        if (buffer.isEmpty()) {
            return;
        }

        int recordCount = buffer.size();
        Exception lastException = null;

        // 重试机制: 处理偶发的连接重置问题
        for (int retry = 0; retry < maxRetries; retry++) {
            try {
                if (retry > 0) {
                    logger.warn("第 {} 次重试写入 Doris...", retry);
                    // 重试前等待一段时间
                    Thread.sleep(1000 * retry);
                }

                doFlush(recordCount);

                // 成功则清空缓冲区并返回
                buffer.clear();
                lastFlushTime = System.currentTimeMillis();
                logger.info("========== 数据刷新完成 ==========\n");
                return;

            } catch (java.net.SocketException e) {
                // 连接重置错误,可以重试
                lastException = e;
                logger.warn("连接被重置 (第 {} 次尝试): {}", retry + 1, e.getMessage());

                if (retry == maxRetries - 1) {
                    logger.error("达到最大重试次数 {}, 放弃写入", maxRetries);
                }
            } catch (Exception e) {
                // 其他错误,直接抛出
                logger.error("========== 刷新数据到 Doris 失败 ==========");
                logger.error("记录数: {}", recordCount);
                logger.error("错误信息: {}", e.getMessage(), e);
                throw e;
            }
        }

        // 所有重试都失败
        logger.error("========== 刷新数据到 Doris 失败 (重试 {} 次后) ==========", maxRetries);
        logger.error("记录数: {}", recordCount);
        throw lastException;
    }

    /**
     * 执行实际的数据刷新操作
     */
    private void doFlush(int recordCount) throws Exception {
        logger.info("========== 开始刷新数据到 Doris ==========");
        logger.info("记录数: {}", recordCount);
        // 构建 Stream Load URL
        String loadUrl = String.format("%s/api/%s/%s/_stream_load",
                feHttpUrl, database, table);

        logger.info("Stream Load URL: {}", loadUrl);

        // 准备数据
        StringBuilder data = new StringBuilder();
        for (String record : buffer) {
            data.append(record).append("\n");
        }

        logger.info("数据大小: {} bytes", data.length());
        logger.debug("前3条数据示例:\n{}",
                buffer.size() > 0 ? buffer.subList(0, Math.min(3, buffer.size())) : "无数据");

        // 发送 HTTP 请求
        HttpURLConnection conn = (HttpURLConnection) new URL(loadUrl).openConnection();
        conn.setRequestMethod("PUT");
        conn.setDoOutput(true);

        // 修复: 增加超时时间,避免大批量数据时连接被重置
        conn.setConnectTimeout(60000);  // 连接超时 60 秒
        conn.setReadTimeout(120000);    // 读取超时 120 秒

        // 关键修复: 禁用 Expect: 100-continue 头,避免 Doris 报错
        conn.setRequestProperty("Expect", "");

        // 修复: 设置 Connection 为 close,避免连接复用导致的问题
        conn.setRequestProperty("Connection", "close");

        // 设置认证
        String auth = username + ":" + password;
        String encodedAuth = Base64.getEncoder().encodeToString(auth.getBytes(StandardCharsets.UTF_8));
        conn.setRequestProperty("Authorization", "Basic " + encodedAuth);

        // 设置 Stream Load 参数
        conn.setRequestProperty("format", "json");
        conn.setRequestProperty("read_json_by_line", "true");
        conn.setRequestProperty("strip_outer_array", "false");
        conn.setRequestProperty("max_filter_ratio", "0.1");

        logger.info("发送 HTTP PUT 请求...");

        // 写入数据
        try (OutputStream os = conn.getOutputStream()) {
            os.write(data.toString().getBytes(StandardCharsets.UTF_8));
            os.flush();
        }

        // 读取响应
        int responseCode = conn.getResponseCode();
        logger.info("HTTP 响应码: {}", responseCode);

        StringBuilder response = new StringBuilder();
        // 根据响应码选择读取正常流或错误流
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(
                        responseCode >= 400 ? conn.getErrorStream() : conn.getInputStream(),
                        StandardCharsets.UTF_8))) {
            String line;
            while ((line = br.readLine()) != null) {
                response.append(line);
            }
        } catch (Exception e) {
            logger.warn("读取响应流失败: {}", e.getMessage());
        }

        String responseStr = response.toString();
        logger.info("响应内容: {}", responseStr);

        // 解析响应 JSON
        JSONObject responseJson = null;
        try {
            responseJson = JSONObject.parseObject(responseStr);
        } catch (Exception e) {
            logger.error("解析响应 JSON 失败: {}", e.getMessage());
        }

        // 检查结果
        if (responseCode == 200 && responseJson != null) {
            String status = responseJson.getString("status");
            logger.info("Stream Load 状态: {}", status);

            if ("Success".equalsIgnoreCase(status)) {
                logger.info("✓ Stream Load 成功: {} 条记录", recordCount);
                logger.info("导入统计: {}", responseJson.toJSONString());
            } else {
                logger.error("✗ Stream Load 失败: status={}", status);
                logger.error("错误信息: {}", responseJson.getString("msg"));
                throw new Exception("Stream Load failed with status: " + status);
            }
        } else {
            logger.error("✗ Stream Load 失败: HTTP code={}", responseCode);
            logger.error("响应内容: {}", responseStr);
            throw new Exception("Stream Load failed: HTTP " + responseCode + ", " + responseStr);
        }
    }

}
