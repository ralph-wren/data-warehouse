#!/bin/bash

# OKX 交易服务测试脚本
# 测试现货买卖、合约买卖功能
# 每次交易 6 美元,杠杆 1 倍

set -e

echo "=========================================="
echo "OKX 交易服务测试"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 项目根目录
PROJECT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$PROJECT_DIR"

# 检查是否已编译
if [ ! -f "target/crypto-dw-1.0-SNAPSHOT.jar" ]; then
    echo -e "${YELLOW}⚠ 项目未编译,开始编译...${NC}"
    mvn clean compile -DskipTests
fi

# 创建测试类
TEST_CLASS="src/test/java/com/crypto/dw/trading/OKXTradingServiceTest.java"
mkdir -p "src/test/java/com/crypto/dw/trading"

cat > "$TEST_CLASS" << 'EOF'
package com.crypto.dw.trading;

import com.crypto.dw.config.ConfigLoader;
import java.math.BigDecimal;

/**
 * OKX 交易服务测试类
 * 
 * 测试内容:
 * 1. 现货买入 (BTC-USDT, 6 USDT)
 * 2. 现货卖出 (BTC-USDT, 6 USDT)
 * 3. 合约开多 (BTC-USDT-SWAP, 6 USDT, 1x)
 * 4. 合约开空 (BTC-USDT-SWAP, 6 USDT, 1x)
 * 5. 合约平多 (BTC-USDT-SWAP, 6 USDT)
 * 6. 合约平空 (BTC-USDT-SWAP, 6 USDT)
 */
public class OKXTradingServiceTest {
    
    private static final String SYMBOL = "BTC-USDT";
    private static final BigDecimal AMOUNT = new BigDecimal("6");
    private static final int LEVERAGE = 1;
    
    public static void main(String[] args) {
        try {
            System.out.println("========================================");
            System.out.println("OKX 交易服务测试");
            System.out.println("========================================");
            System.out.println();
            
            // 加载配置
            ConfigLoader config = ConfigLoader.getInstance();
            
            // 创建交易服务
            OKXTradingService tradingService = new OKXTradingService(config);
            
            // 检查配置
            if (!tradingService.isConfigured()) {
                System.err.println("✗ OKX API 未配置!");
                System.err.println("请设置环境变量: OKX_API_KEY, OKX_SECRET_KEY, OKX_PASSPHRASE");
                System.exit(1);
            }
            
            System.out.println("✓ OKX API 配置已加载");
            System.out.println("✓ 模拟交易模式: " + tradingService.isSimulated());
            System.out.println();
            
            // 测试 1: 现货买入
            System.out.println("========================================");
            System.out.println("测试 1: 现货买入");
            System.out.println("========================================");
            System.out.println("交易对: " + SYMBOL);
            System.out.println("金额: " + AMOUNT + " USDT");
            System.out.println();
            
            String buySpotOrderId = tradingService.buySpot(SYMBOL, AMOUNT);
            if (buySpotOrderId != null) {
                System.out.println("✓ 现货买入成功");
                System.out.println("订单ID: " + buySpotOrderId);
            } else {
                System.out.println("✗ 现货买入失败");
            }
            System.out.println();
            
            // 等待 2 秒
            Thread.sleep(2000);
            
            // 测试 2: 现货卖出
            System.out.println("========================================");
            System.out.println("测试 2: 现货卖出");
            System.out.println("========================================");
            System.out.println("交易对: " + SYMBOL);
            System.out.println("金额: " + AMOUNT + " USDT");
            System.out.println();
            
            String sellSpotOrderId = tradingService.sellSpot(SYMBOL, AMOUNT);
            if (sellSpotOrderId != null) {
                System.out.println("✓ 现货卖出成功");
                System.out.println("订单ID: " + sellSpotOrderId);
            } else {
                System.out.println("✗ 现货卖出失败");
            }
            System.out.println();
            
            // 等待 2 秒
            Thread.sleep(2000);
            
            // 测试 3: 合约开多
            System.out.println("========================================");
            System.out.println("测试 3: 合约开多");
            System.out.println("========================================");
            System.out.println("交易对: " + SYMBOL + "-SWAP");
            System.out.println("金额: " + AMOUNT + " USDT");
            System.out.println("杠杆: " + LEVERAGE + "x");
            System.out.println();
            
            String longSwapOrderId = tradingService.longSwap(SYMBOL, AMOUNT, LEVERAGE);
            if (longSwapOrderId != null) {
                System.out.println("✓ 合约开多成功");
                System.out.println("订单ID: " + longSwapOrderId);
            } else {
                System.out.println("✗ 合约开多失败");
            }
            System.out.println();
            
            // 等待 2 秒
            Thread.sleep(2000);
            
            // 测试 4: 合约开空
            System.out.println("========================================");
            System.out.println("测试 4: 合约开空");
            System.out.println("========================================");
            System.out.println("交易对: " + SYMBOL + "-SWAP");
            System.out.println("金额: " + AMOUNT + " USDT");
            System.out.println("杠杆: " + LEVERAGE + "x");
            System.out.println();
            
            String shortSwapOrderId = tradingService.shortSwap(SYMBOL, AMOUNT, LEVERAGE);
            if (shortSwapOrderId != null) {
                System.out.println("✓ 合约开空成功");
                System.out.println("订单ID: " + shortSwapOrderId);
            } else {
                System.out.println("✗ 合约开空失败");
            }
            System.out.println();
            
            // 等待 2 秒
            Thread.sleep(2000);
            
            // 测试 5: 合约平多
            System.out.println("========================================");
            System.out.println("测试 5: 合约平多");
            System.out.println("========================================");
            System.out.println("交易对: " + SYMBOL + "-SWAP");
            System.out.println("金额: " + AMOUNT + " USDT");
            System.out.println();
            
            String closeLongOrderId = tradingService.closeLongSwap(SYMBOL, AMOUNT);
            if (closeLongOrderId != null) {
                System.out.println("✓ 合约平多成功");
                System.out.println("订单ID: " + closeLongOrderId);
            } else {
                System.out.println("✗ 合约平多失败");
            }
            System.out.println();
            
            // 等待 2 秒
            Thread.sleep(2000);
            
            // 测试 6: 合约平空
            System.out.println("========================================");
            System.out.println("测试 6: 合约平空");
            System.out.println("========================================");
            System.out.println("交易对: " + SYMBOL + "-SWAP");
            System.out.println("金额: " + AMOUNT + " USDT");
            System.out.println();
            
            String closeShortOrderId = tradingService.closeShortSwap(SYMBOL, AMOUNT);
            if (closeShortOrderId != null) {
                System.out.println("✓ 合约平空成功");
                System.out.println("订单ID: " + closeShortOrderId);
            } else {
                System.out.println("✗ 合约平空失败");
            }
            System.out.println();
            
            // 测试总结
            System.out.println("========================================");
            System.out.println("测试总结");
            System.out.println("========================================");
            
            int successCount = 0;
            int totalCount = 6;
            
            if (buySpotOrderId != null) successCount++;
            if (sellSpotOrderId != null) successCount++;
            if (longSwapOrderId != null) successCount++;
            if (shortSwapOrderId != null) successCount++;
            if (closeLongOrderId != null) successCount++;
            if (closeShortOrderId != null) successCount++;
            
            System.out.println("成功: " + successCount + "/" + totalCount);
            System.out.println("失败: " + (totalCount - successCount) + "/" + totalCount);
            System.out.println();
            
            if (successCount == totalCount) {
                System.out.println("✓ 所有测试通过!");
                System.exit(0);
            } else {
                System.out.println("✗ 部分测试失败!");
                System.exit(1);
            }
            
        } catch (Exception e) {
            System.err.println("✗ 测试异常: " + e.getMessage());
            e.printStackTrace();
            System.exit(1);
        }
    }
}
EOF

echo -e "${GREEN}✓ 测试类已创建${NC}"
echo ""

# 编译测试类
echo -e "${YELLOW}编译测试类...${NC}"
mvn test-compile -DskipTests

# 运行测试
echo ""
echo -e "${YELLOW}运行测试...${NC}"
echo ""

mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.trading.OKXTradingServiceTest" \
    -Dexec.classpathScope="test" \
    -Dexec.cleanupDaemonThreads=false

echo ""
echo -e "${GREEN}✓ 测试完成${NC}"
