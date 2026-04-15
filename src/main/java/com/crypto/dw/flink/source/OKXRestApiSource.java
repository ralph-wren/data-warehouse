package com.crypto.dw.flink.source;

import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.utils.SpreadRateUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;

/**
 * OKX REST API Source - 定期获取所有现货和合约价格
 * 
 * 功能:
 * 1. 定期调用 OKX REST API 获取所有 USDT 交易对的价格
 * 2. 计算现货和合约的价差
 * 3. 找出价差最大的前 N 个币种
 * 4. 输出币对列表供后续处理
 * 
 * API 文档:
 * - 获取所有 Ticker: https://www.okx.com/docs-v5/zh/#rest-api-market-data-get-tickers
 * 
 * 输出格式: PriceSpreadInfo
 * - symbol: 币种符号 (如 BTC)
 * - spotPrice: 现货价格
 * - swapPrice: 合约价格
 * - spread: 价差 (合约价格 - 现货价格)
 * - spreadRate: 价差率 (价差 / 现货价格)
 * 
 * @author Kiro
 * @date 2026-04-11
 */
public class OKXRestApiSource extends RichSourceFunction<OKXRestApiSource.PriceSpreadInfo> {

    private static final Logger logger = LoggerFactory.getLogger(OKXRestApiSource.class);

    /**
     * 默认 REST 根域名。部分网络环境下 www.okx.com 会被 WAF 拦截返回 403，可通过配置 okx.rest.base-url 改为官方文档中的备用域名。
     */
    private static final String DEFAULT_REST_BASE = "https://www.okx.com";

    /**
     * 浏览器风格 User-Agent：Java HttpURLConnection 默认 UA 常含 "Java/xx"，易被 CDN/WAF 直接 403。
     * 可通过 okx.rest.user-agent 覆盖。
     */
    private static final String DEFAULT_BROWSER_USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36";

    private static final String SPOT_TICKER_PATH = "/api/v5/market/tickers?instType=SPOT";
    private static final String SWAP_TICKER_PATH = "/api/v5/market/tickers?instType=SWAP";

    private final ConfigLoader config;
    private final long intervalMs;  // 获取价格的间隔时间(毫秒)
    private final int topN;  // 取价差最大的前 N 个
    private final BigDecimal minVolume24h;  // 最小24小时成交额(USDT),默认30万
    /** REST 根地址，无尾部斜杠 */
    private final String restBaseUrl;
    /** 请求头 User-Agent */
    private final String restUserAgent;
    
    private volatile boolean running = true;
    private transient ObjectMapper objectMapper;

    /**
     * 构造函数
     * 
     * @param config 配置加载器
     * @param intervalMs 获取价格的间隔时间(毫秒), 建议 30000 (30秒)
     * @param topN 取价差最大的前 N 个币种, 建议 10
     */
    public OKXRestApiSource(ConfigLoader config, long intervalMs, int topN) {
        this.config = config;
        this.intervalMs = intervalMs;
        this.topN = topN;
        // 读取最小24小时成交额配置,默认30万 USDT
        this.minVolume24h = new BigDecimal(config.getString("ticker.filter.min-volume-24h", "300000"));
        String base = config.getString("okx.rest.base-url", DEFAULT_REST_BASE).trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        this.restBaseUrl = base.isEmpty() ? DEFAULT_REST_BASE : base;
        String ua = config.getString("okx.rest.user-agent", "").trim();
        this.restUserAgent = ua.isEmpty() ? DEFAULT_BROWSER_USER_AGENT : ua;
        logger.info("OKX REST 请求: baseUrl={}, userAgent={}",
                restBaseUrl, ua.isEmpty() ? "(默认浏览器风格,避免 Java 默认 UA 触发 403)" : "(来自 okx.rest.user-agent)");
    }

    @Override
    public void open(Configuration parameters) throws Exception {
        super.open(parameters);
        objectMapper = new ObjectMapper();
    }

    @Override
    public void run(SourceContext<PriceSpreadInfo> ctx) throws Exception {

    }

    @Override
    public void cancel() {
        running = false;
        logger.info("OKX REST API Source 已停止");
    }

    /**
     * 获取所有现货价格 (公开方法,供外部调用)
     * 
     * @return Map<币种符号, 价格>  例如: {"BTC", 50000.0}
     */
    public Map<String, BigDecimal> fetchSpotPrices() throws Exception {
        Map<String, BigDecimal> prices = new HashMap<>();
        
        String response = httpGet(restBaseUrl + SPOT_TICKER_PATH);
        JsonNode rootNode = objectMapper.readTree(response);
        
        // 检查响应状态
        if (rootNode.has("code") && !"0".equals(rootNode.get("code").asText())) {
            logger.error("获取现货价格失败: {}", response);
            return prices;
        }
        
        int totalCount = 0;
        int filteredByVolume = 0;
        
        // 解析数据
        JsonNode dataArray = rootNode.get("data");
        if (dataArray != null && dataArray.isArray()) {
            for (JsonNode item : dataArray) {
                String instId = item.get("instId").asText();  // 例如: BTC-USDT

                totalCount++;

                // 只处理 USDT 交易对
                if (!instId.endsWith("-USDT")) {
                    continue;
                }
                
                // 提取币种符号 (去掉 -USDT 后缀)
                String symbol = instId.replace("-USDT", "");
                
                // ⭐ 过滤24小时成交额小于配置值的币种
                if (item.has("volCcy24h")) {
                    String volCcy24hStr = item.get("volCcy24h").asText();
                    try {
                        BigDecimal volCcy24h = new BigDecimal(volCcy24hStr);
                        if (volCcy24h.compareTo(minVolume24h) < 0) {
                            filteredByVolume++;
                            continue;  // 跳过成交额不足的币种
                        }
                    } catch (NumberFormatException e) {
                        logger.warn("解析24小时成交额失败: {} - {}", symbol, volCcy24hStr);
                        continue;
                    }
                }
                
                // 获取最新价格
                String lastPriceStr = item.get("last").asText();
                BigDecimal lastPrice = new BigDecimal(lastPriceStr);
                
                prices.put(symbol, lastPrice);
            }
        }
        
        logger.info("现货价格筛选: 总数={}, 成交额过滤={}, 保留={}", 
            totalCount, filteredByVolume, prices.size());
        
        return prices;
    }

    /**
     * 获取所有合约价格 (公开方法,供外部调用)
     * 
     * @return Map<币种符号, 价格>  例如: {"BTC", 50100.0}
     */
    public Map<String, BigDecimal> fetchSwapPrices() throws Exception {
        Map<String, BigDecimal> prices = new HashMap<>();
        
        String response = httpGet(restBaseUrl + SWAP_TICKER_PATH);
        JsonNode rootNode = objectMapper.readTree(response);
        
        // 检查响应状态
        if (rootNode.has("code") && !"0".equals(rootNode.get("code").asText())) {
            logger.error("获取合约价格失败: {}", response);
            return prices;
        }
        
        int totalCount = 0;
        int filteredByVolume = 0;
        
        // 解析数据
        JsonNode dataArray = rootNode.get("data");
        if (dataArray != null && dataArray.isArray()) {
            for (JsonNode item : dataArray) {
                String instId = item.get("instId").asText();  // 例如: BTC-USDT-SWAP

                totalCount++;

                // 只处理 USDT 永续合约
                if (!instId.endsWith("-USDT-SWAP")) {
                    continue;
                }
                
                // 提取币种符号 (去掉 -USDT-SWAP 后缀)
                String symbol = instId.replace("-USDT-SWAP", "");
                
                // ⭐ 过滤24小时成交额小于配置值的币种
                if (item.has("volCcy24h")) {
                    String volCcy24hStr = item.get("volCcy24h").asText();
                    try {
                        BigDecimal volCcy24h = new BigDecimal(volCcy24hStr);
                        if (volCcy24h.compareTo(minVolume24h) < 0) {
                            filteredByVolume++;
                            continue;  // 跳过成交额不足的币种
                        }
                    } catch (NumberFormatException e) {
                        logger.warn("解析24小时成交额失败: {} - {}", symbol, volCcy24hStr);
                        continue;
                    }
                }
                
                // 获取最新价格
                String lastPriceStr = item.get("last").asText();
                BigDecimal lastPrice = new BigDecimal(lastPriceStr);
                
                prices.put(symbol, lastPrice);
            }
        }
        
        logger.info("合约价格筛选: 总数={}, 成交额过滤={}, 保留={}", 
            totalCount, filteredByVolume, prices.size());
        
        return prices;
    }

    /**
     * 计算价差并按价差率绝对值排序 (公开方法,供外部调用)
     * 
     * @param spotPrices 现货价格
     * @param swapPrices 合约价格
     * @return 按价差率绝对值降序排列的列表
     */
    public List<PriceSpreadInfo> calculateSpreads(
            Map<String, BigDecimal> spotPrices, 
            Map<String, BigDecimal> swapPrices) {
        
        List<PriceSpreadInfo> spreadList = new ArrayList<>();
        
        // 遍历所有现货价格
        for (Map.Entry<String, BigDecimal> entry : spotPrices.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal spotPrice = entry.getValue();
            
            // 检查是否有对应的合约价格
            if (!swapPrices.containsKey(symbol)) {
                continue;
            }
            
            BigDecimal swapPrice = swapPrices.get(symbol);
            
            // 计算价差 (合约价格 - 现货价格)  差价绝对值/较低的价格
            BigDecimal spread = swapPrice.subtract(spotPrice).abs();
            
            // 统一价差率口径：|swap-spot| / min(spot,swap)
            BigDecimal spreadRate = SpreadRateUtils.calculateSpreadRatePercent(spotPrice, swapPrice, 6);
            
            // 创建价差信息对象
            PriceSpreadInfo info = new PriceSpreadInfo();
            info.symbol = symbol;
            info.spotPrice = spotPrice;
            info.swapPrice = swapPrice;
            info.spread = spread;
            info.spreadRate = spreadRate;
            info.timestamp = System.currentTimeMillis();
            
            spreadList.add(info);
        }
        
        // 按价差率绝对值降序排序
        spreadList.sort((a, b) -> b.spreadRate.abs().compareTo(a.spreadRate.abs()));
        
        return spreadList;
    }

    /**
     * HTTP GET 请求
     * 
     * @param urlString URL 地址
     * @return 响应内容
     */
    private String httpGet(String urlString) throws Exception {
        URL url = new URL(urlString);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();

        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        // 公开行情接口无需 Content-Type；Accept 标明期望 JSON
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("User-Agent", restUserAgent);

        int responseCode = conn.getResponseCode();
        String body = readStreamFully(
                responseCode >= 200 && responseCode < 300 ? conn.getInputStream() : conn.getErrorStream());
        conn.disconnect();

        if (responseCode != 200) {
            String snippet = body == null ? "" : body.length() > 800 ? body.substring(0, 800) + "..." : body;
            logger.warn("OKX REST 非 200: url={}, code={}, bodySnippet={}", urlString, responseCode, snippet);
            throw new RuntimeException("HTTP 请求失败, 状态码: " + responseCode
                    + (snippet.isEmpty() ? "" : ", 响应片段: " + snippet));
        }

        return body != null ? body : "";
    }

    /** 读取 HTTP 响应体（成功或错误流），便于 403 时从 HTML/JSON 片段判断是 WAF 还是业务错误 */
    private static String readStreamFully(InputStream stream) throws Exception {
        if (stream == null) {
            return null;
        }
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            StringBuilder response = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            return response.toString();
        }
    }

    /**
     * 价差信息 - 输出数据结构
     */
    public static class PriceSpreadInfo {
        public String symbol;  // 币种符号 (如 BTC)
        public BigDecimal spotPrice;  // 现货价格
        public BigDecimal swapPrice;  // 合约价格
        public BigDecimal spread;  // 价差 (合约价格 - 现货价格)
        public BigDecimal spreadRate;  // 价差率（|swap-spot| / min(spot,swap)）
        public long timestamp;  // 时间戳

        @Override
        public String toString() {
            return String.format("PriceSpreadInfo{symbol='%s', spotPrice=%s, swapPrice=%s, spread=%s, spreadRate=%s%%}", 
                symbol, spotPrice, swapPrice, spread, spreadRate);
        }
    }
}
