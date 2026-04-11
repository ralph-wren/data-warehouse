package com.crypto.dw.flink.source;

import com.crypto.dw.config.ConfigLoader;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.streaming.api.functions.source.RichSourceFunction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
    
    // OKX REST API 地址
    private static final String SPOT_TICKER_API = "https://www.okx.com/api/v5/market/tickers?instType=SPOT";
    private static final String SWAP_TICKER_API = "https://www.okx.com/api/v5/market/tickers?instType=SWAP";
    
    private final ConfigLoader config;
    private final long intervalMs;  // 获取价格的间隔时间(毫秒)
    private final int topN;  // 取价差最大的前 N 个
    private final boolean filterMarginOnly;  // 是否只订阅支持现货杠杆的币对
    private final BigDecimal minVolume24h;  // 最小24小时成交额(USDT),默认30万
    
    private volatile boolean running = true;
    private transient ObjectMapper objectMapper;
    private transient Proxy proxy;
    private transient com.crypto.dw.trading.MarginSupportCache marginCache;  // 杠杆支持信息缓存

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
        this.filterMarginOnly = config.getBoolean("ticker.filter.margin-only", true);  // 默认过滤
        // 读取最小24小时成交额配置,默认30万 USDT
        this.minVolume24h = new BigDecimal(config.getString("ticker.filter.min-volume-24h", "300000"));
    }

    @Override
    public void open(org.apache.flink.configuration.Configuration parameters) throws Exception {
        super.open(parameters);
        objectMapper = new ObjectMapper();
        
        // 配置代理(如果启用)
        boolean proxyEnabled = config.getBoolean("okx.proxy.enabled", false);
        if (proxyEnabled) {
            String proxyHost = config.getString("okx.proxy.host", "localhost");
            int proxyPort = config.getInt("okx.proxy.port", 10809);
            proxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(proxyHost, proxyPort));
            logger.info("代理已启用: {}:{}", proxyHost, proxyPort);
        } else {
            proxy = Proxy.NO_PROXY;
            logger.info("代理未启用");
        }
        
        // 初始化杠杆支持信息缓存（如果启用过滤）
        if (filterMarginOnly) {
            marginCache = new com.crypto.dw.trading.MarginSupportCache(config);
            logger.info("杠杆过滤已启用，将只订阅支持现货杠杆的币对");
        } else {
            logger.info("杠杆过滤已禁用，将订阅所有币对");
        }
    }

    @Override
    public void run(SourceContext<PriceSpreadInfo> ctx) throws Exception {
        logger.info("OKX REST API Source 已启动, 间隔: {}ms, 取前 {} 个币种", intervalMs, topN);
        
        while (running) {
            try {
                // 1. 获取所有现货价格
                Map<String, BigDecimal> spotPrices = fetchSpotPrices();
                logger.info("获取到 {} 个现货价格", spotPrices.size());
                
                // 2. 获取所有合约价格
                Map<String, BigDecimal> swapPrices = fetchSwapPrices();
                logger.info("获取到 {} 个合约价格", swapPrices.size());
                
                // 3. 计算价差并排序
                List<PriceSpreadInfo> spreadList = calculateSpreads(spotPrices, swapPrices);
                logger.info("计算出 {} 个币种的价差", spreadList.size());
                
                // 4. 取价差最大的前 N 个
                List<PriceSpreadInfo> topSpreads = spreadList.subList(0, Math.min(topN, spreadList.size()));
                
                // 5. 输出结果
                for (PriceSpreadInfo info : topSpreads) {
                    ctx.collect(info);
                    logger.info("价差排名: {} - 现货: {}, 合约: {}, 价差率: {}%", 
                        info.symbol, info.spotPrice, info.swapPrice, info.spreadRate.multiply(new BigDecimal("100")));
                }
                
                // 6. 等待下一次获取
                Thread.sleep(intervalMs);
                
            } catch (Exception e) {
                logger.error("获取价格数据失败: {}", e.getMessage(), e);
                // 失败后等待一段时间再重试
                Thread.sleep(5000);
            }
        }
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
        
        String response = httpGet(SPOT_TICKER_API);
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
                
                // 只处理 USDT 交易对
                if (!instId.endsWith("-USDT")) {
                    continue;
                }
                
                totalCount++;
                
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
        
        String response = httpGet(SWAP_TICKER_API);
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
                
                // 只处理 USDT 永续合约
                if (!instId.endsWith("-USDT-SWAP")) {
                    continue;
                }
                
                totalCount++;
                
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
        int filteredCount = 0;  // 被过滤的币对数量
        
        // 遍历所有现货价格
        for (Map.Entry<String, BigDecimal> entry : spotPrices.entrySet()) {
            String symbol = entry.getKey();
            BigDecimal spotPrice = entry.getValue();
            
            // 检查是否有对应的合约价格
            if (!swapPrices.containsKey(symbol)) {
                continue;
            }
            
            // 如果启用杠杆过滤，检查是否支持现货杠杆
            if (filterMarginOnly && marginCache != null) {
                String instId = symbol + "-USDT";
                boolean supportsMargin = marginCache.supportsMargin(instId);
                if (!supportsMargin) {
                    filteredCount++;
                    logger.debug("过滤不支持杠杆的币对: {}", instId);
                    continue;
                }
            }
            
            BigDecimal swapPrice = swapPrices.get(symbol);
            
            // 计算价差 (合约价格 - 现货价格)
            BigDecimal spread = swapPrice.subtract(spotPrice);
            
            // 计算价差率 (价差 / 现货价格)
            BigDecimal spreadRate = spread.divide(spotPrice, 6, BigDecimal.ROUND_HALF_UP);
            
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
        
        if (filterMarginOnly && filteredCount > 0) {
            logger.info("杠杆过滤: 过滤了 {} 个不支持杠杆的币对，剩余 {} 个币对", filteredCount, spreadList.size());
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
        HttpURLConnection conn = (HttpURLConnection) url.openConnection(proxy);
        
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        conn.setRequestProperty("Content-Type", "application/json");
        
        int responseCode = conn.getResponseCode();
        if (responseCode != 200) {
            throw new RuntimeException("HTTP 请求失败, 状态码: " + responseCode);
        }
        
        BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()));
        StringBuilder response = new StringBuilder();
        String line;
        while ((line = reader.readLine()) != null) {
            response.append(line);
        }
        reader.close();
        conn.disconnect();
        
        return response.toString();
    }

    /**
     * 价差信息 - 输出数据结构
     */
    public static class PriceSpreadInfo {
        public String symbol;  // 币种符号 (如 BTC)
        public BigDecimal spotPrice;  // 现货价格
        public BigDecimal swapPrice;  // 合约价格
        public BigDecimal spread;  // 价差 (合约价格 - 现货价格)
        public BigDecimal spreadRate;  // 价差率 (价差 / 现货价格)
        public long timestamp;  // 时间戳

        @Override
        public String toString() {
            return String.format("PriceSpreadInfo{symbol='%s', spotPrice=%s, swapPrice=%s, spread=%s, spreadRate=%s%%}", 
                symbol, spotPrice, swapPrice, spread, spreadRate.multiply(new BigDecimal("100")));
        }
    }
}
