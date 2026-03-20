package test;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Watermark 时间戳提取测试
 * 测试正则表达式是否能正确提取 timestamp 或 ts 字段的 13 位数字
 * 
 * 运行方式：
 * javac test/TestWatermarkTimestampExtraction.java
 * java -cp . test.TestWatermarkTimestampExtraction
 */
public class TestWatermarkTimestampExtraction {
    
    // 时间戳提取正则表达式（与 WatermarkStrategyFactory 中的一致）
    private static final Pattern TIMESTAMP_PATTERN = Pattern.compile(
        "(?:timestamp|ts)[\"']?\\s*[:=]\\s*[\"']?(\\d{13})"
    );
    
    /**
     * 从字符串中提取时间戳
     */
    private static long extractTimestamp(String element) {
        try {
            Matcher matcher = TIMESTAMP_PATTERN.matcher(element);
            if (matcher.find()) {
                String timestampStr = matcher.group(1);
                return Long.parseLong(timestampStr);
            }
        } catch (Exception e) {
            System.err.println("提取失败: " + e.getMessage());
        }
        return -1; // 提取失败
    }
    
    /**
     * 测试用例
     */
    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("Watermark 时间戳提取测试");
        System.out.println("==========================================");
        System.out.println();
        
        // 测试用例数组
        String[] testCases = {
            // 标准 JSON 格式（带引号）
            "{\"ts\":1710921600000,\"price\":50000}",
            "{\"timestamp\":1710921600000,\"price\":50000}",
            
            // 带空格的格式
            "{\"ts\": 1710921600000, \"price\": 50000}",
            "{\"timestamp\": 1710921600000, \"price\": 50000}",
            
            // 不带引号的格式
            "{ts:1710921600000,price:50000}",
            "{timestamp:1710921600000,price:50000}",
            
            // 使用等号的格式
            "ts=1710921600000&price=50000",
            "timestamp=1710921600000&price=50000",
            
            // 混合格式
            "{\"inst_id\":\"BTC-USDT\",\"ts\":1710921600000,\"last\":50000}",
            "{\"inst_id\":\"BTC-USDT\",\"timestamp\":1710921600000,\"last\":50000}",
            
            // 边界情况
            "{\"ts\":1710921600000}",  // 最后一个字段
            "ts:1710921600000",  // 最简格式
            
            // 错误格式（应该提取失败）
            "{\"ts\":123}",  // 不足 13 位
            "{\"ts\":12345678901234}",  // 超过 13 位
            "{\"price\":50000}",  // 没有 ts 字段
        };
        
        int successCount = 0;
        int failCount = 0;
        
        for (int i = 0; i < testCases.length; i++) {
            String testCase = testCases[i];
            long timestamp = extractTimestamp(testCase);
            
            System.out.println("测试 " + (i + 1) + ":");
            System.out.println("  输入: " + testCase);
            
            if (timestamp != -1) {
                System.out.println("  结果: ✓ 成功提取");
                System.out.println("  时间戳: " + timestamp);
                System.out.println("  日期: " + new java.util.Date(timestamp));
                successCount++;
            } else {
                System.out.println("  结果: ✗ 提取失败");
                failCount++;
            }
            System.out.println();
        }
        
        System.out.println("==========================================");
        System.out.println("测试总结");
        System.out.println("==========================================");
        System.out.println("总计: " + testCases.length + " 个测试用例");
        System.out.println("成功: " + successCount + " 个");
        System.out.println("失败: " + failCount + " 个");
        System.out.println();
        
        // 预期结果
        int expectedSuccess = 12;  // 前 12 个应该成功
        int expectedFail = 3;      // 后 3 个应该失败
        
        if (successCount == expectedSuccess && failCount == expectedFail) {
            System.out.println("✓ 所有测试通过！");
            System.exit(0);
        } else {
            System.out.println("✗ 测试失败！");
            System.out.println("  预期成功: " + expectedSuccess + ", 实际: " + successCount);
            System.out.println("  预期失败: " + expectedFail + ", 实际: " + failCount);
            System.exit(1);
        }
    }
}
