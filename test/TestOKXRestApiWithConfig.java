import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.flink.source.OKXRestApiSource;
import org.apache.flink.configuration.Configuration;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * 测试 OKX REST API 在实际环境中的调用
 * 模拟 FlinkTickerCollectorJob 的调用方式
 */
public class TestOKXRestApiWithConfig {
    
    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("测试 OKX REST API 实际调用");
        System.out.println("==========================================");
        
        try {
            // 1. 加载配置
            System.out.println("\n1. 加载配置...");
            ConfigLoader config = new ConfigLoader("dev");
            System.out.println("配置加载成功");
            
            // 2. 创建 OKXRestApiSource 实例
            System.out.println("\n2. 创建 OKXRestApiSource 实例...");
            OKXRestApiSource apiSource = new OKXRestApiSource(config, 0, 10);
            System.out.println("实例创建成功");
            
            // 3. 初始化 Source
            System.out.println("\n3. 初始化 Source...");
            Configuration flinkConfig = new Configuration();
            apiSource.open(flinkConfig);
            System.out.println("初始化成功");
            
            // 4. 获取现货价格
            System.out.println("\n4. 获取现货价格...");
            Map<String, BigDecimal> spotPrices = apiSource.fetchSpotPrices();
            System.out.println("获取到 " + spotPrices.size() + " 个现货价格");
            
            // 5. 获取合约价格
            System.out.println("\n5. 获取合约价格...");
            Map<String, BigDecimal> swapPrices = apiSource.fetchSwapPrices();
            System.out.println("获取到 " + swapPrices.size() + " 个合约价格");
            
            // 6. 计算价差
            System.out.println("\n6. 计算价差...");
            List<OKXRestApiSource.PriceSpreadInfo> spreadList = 
                apiSource.calculateSpreads(spotPrices, swapPrices);
            System.out.println("计算出 " + spreadList.size() + " 个币种的价差");
            
            // 7. 显示前 10 个
            System.out.println("\n7. 价差排名前 10:");
            int count = Math.min(10, spreadList.size());
            for (int i = 0; i < count; i++) {
                OKXRestApiSource.PriceSpreadInfo info = spreadList.get(i);
                System.out.println(String.format("  %d. %s - 现货: %s, 合约: %s, 价差率: %s%%", 
                    i + 1, info.symbol, info.spotPrice, info.swapPrice, info.spreadRate));
            }
            
            System.out.println("\n==========================================");
            System.out.println("测试成功完成!");
            System.out.println("==========================================");
            
        } catch (Exception e) {
            System.err.println("\n==========================================");
            System.err.println("测试失败!");
            System.err.println("==========================================");
            System.err.println("错误信息: " + e.getMessage());
            System.err.println("\n详细堆栈:");
            e.printStackTrace();
        }
    }
}
