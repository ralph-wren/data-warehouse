import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * 测试 OKX REST API 连接
 * 验证添加请求头后是否能成功访问
 */
public class TestOKXRestApi {
    
    private static final String SPOT_TICKER_API = "https://www.okx.com/api/v5/market/tickers?instType=SPOT";
    
    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("测试 OKX REST API 连接");
        System.out.println("==========================================");
        
        try {
            // 测试不带请求头
            System.out.println("\n1. 测试不带请求头的请求...");
            testWithoutHeaders();
            
            // 测试带请求头
            System.out.println("\n2. 测试带请求头的请求...");
            testWithHeaders();
            
        } catch (Exception e) {
            e.printStackTrace();
        }
        
        System.out.println("\n==========================================");
        System.out.println("测试完成");
        System.out.println("==========================================");
    }
    
    /**
     * 测试不带请求头的请求
     */
    private static void testWithoutHeaders() throws Exception {
        URL url = new URL(SPOT_TICKER_API);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        int responseCode = conn.getResponseCode();
        System.out.println("HTTP 状态码: " + responseCode);
        
        if (responseCode == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line = reader.readLine();
                System.out.println("响应内容 (前100字符): " + (line != null ? line.substring(0, Math.min(100, line.length())) : "null"));
            }
        } else {
            System.out.println("请求失败!");
        }
        
        conn.disconnect();
    }
    
    /**
     * 测试带请求头的请求
     */
    private static void testWithHeaders() throws Exception {
        URL url = new URL(SPOT_TICKER_API);
        HttpURLConnection conn = (HttpURLConnection) url.openConnection();
        
        conn.setRequestMethod("GET");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        
        // 添加必要的请求头
        conn.setRequestProperty("Content-Type", "application/json");
        conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36");
        conn.setRequestProperty("Accept", "application/json");
        conn.setRequestProperty("Accept-Language", "zh-CN,zh;q=0.9,en;q=0.8");
        conn.setRequestProperty("Connection", "keep-alive");
        
        int responseCode = conn.getResponseCode();
        System.out.println("HTTP 状态码: " + responseCode);
        
        if (responseCode == 200) {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line = reader.readLine();
                System.out.println("响应内容 (前100字符): " + (line != null ? line.substring(0, Math.min(100, line.length())) : "null"));
            }
        } else {
            System.out.println("请求失败!");
        }
        
        conn.disconnect();
    }
}
