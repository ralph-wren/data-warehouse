#!/bin/bash

# OKX 单次下单测试脚本
# 用于调试 API 认证问题

set -e

echo "=========================================="
echo "OKX 单次下单测试（调试模式）"
echo "=========================================="
echo ""

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

# 创建测试类
TEST_CLASS="src/test/java/com/crypto/dw/trading/OKXSingleOrderTest.java"
mkdir -p "src/test/java/com/crypto/dw/trading"

cat > "$TEST_CLASS" << 'EOF'
package com.crypto.dw.trading;

import com.crypto.dw.config.ConfigLoader;
import java.math.BigDecimal;

/**
 * OKX 单次下单测试（调试模式）
 */
public class OKXSingleOrderTest {
    
    public static void main(String[] args) {
        try {
            System.out.println("========================================");
            System.out.println("OKX 单次下单测试（调试模式）");
            System.out.println("========================================");
            System.out.println();
            
            // 加载配置
            ConfigLoader config = ConfigLoader.getInstance();
            
            // 打印配置信息
            System.out.println("配置信息:");
            System.out.println("  OKX API Key: " + maskString(config.getString("okx.api.key", "")));
            System.out.println("  OKX Secret Key: " + maskString(config.getString("okx.api.secret", "")));
            System.out.println("  OKX Passphrase: " + maskString(config.getString("okx.api.passphrase", "")));
            System.out.println("  OKX Simulated: " + config.getBoolean("okx.api.simulated", false));
            System.out.println("  OKX Proxy Enabled: " + config.getBoolean("okx.proxy.enabled", false));
            System.out.println("  OKX Proxy Host: " + config.getString("okx.proxy.host", ""));
            System.out.println("  OKX Proxy Port: " + config.getInt("okx.proxy.port", 0));
            System.out.println();
            
            // 创建交易服务
            OKXTradingService tradingService = new OKXTradingService(config);
            
            // 检查配置
            if (!tradingService.isConfigured()) {
                System.err.println("✗ OKX API 未配置!");
                System.err.println("请设置环境变量: OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE");
                System.exit(1);
            }
            
            System.out.println("✓ OKX API 配置已加载");
            System.out.println();
            
            // 测试现货买入
            System.out.println("========================================");
            System.out.println("测试: 现货买入 BTC-USDT (6 USDT)");
            System.out.println("========================================");
            System.out.println();
            
            String orderId = tradingService.buySpot("BTC-USDT", new BigDecimal("6"));
            
            System.out.println();
            if (orderId != null) {
                System.out.println("✓ 下单成功!");
                System.out.println("订单ID: " + orderId);
                System.exit(0);
            } else {
                System.out.println("✗ 下单失败!");
                System.out.println("请查看上面的错误日志");
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("✗ 测试异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
    
    /**
     * 掩码字符串（只显示前3个和后3个字符）
     */
    private static String maskString(String str) {
        if (str == null || str.isEmpty()) {
            return "未设置";
        }
        if (str.length() <= 6) {
            return "***";
        }
        return str.substring(0, 3) + "***" + str.substring(str.length() - 3);
    }
}
EOF

echo "✓ 测试类已创建"
echo ""

# 编译测试类
echo "编译测试类..."
mvn test-compile -DskipTests -q

# 运行测试（启用 DEBUG 日志）
echo ""
echo "运行测试（DEBUG 模式）..."
echo ""

# 设置日志级别为 DEBUG
export JAVA_OPTS="-Dlog4j.configuration=file:src/main/resources/log4j2.properties"

mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.trading.OKXSingleOrderTest" \
    -Dexec.classpathScope="test" \
    -Dexec.cleanupDaemonThreads=false \
    -Dorg.slf4j.simpleLogger.defaultLogLevel=DEBUG

echo ""
echo "✓ 测试完成"
