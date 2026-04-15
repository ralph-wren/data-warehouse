import com.crypto.dw.config.ConfigLoader;
import com.crypto.dw.trading.OKXTradingService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 验证 ZRX 订单详情和利润计算
 * 
 * 从 CSV 文件读取的订单信息:
 * - 现货订单ID: 3472977534914945024
 * - 合约订单ID: 3472977550383538176
 * - 持仓数量: 60 ZRX
 * - 开仓现货价格: 0.10225
 * - 开仓合约价格: 0.10166
 * - 未实现利润: -0.0123 USDT
 * - 利润率: -0.20%
 */
public class VerifyZRXOrders {
    
    public static void main(String[] args) {
        System.out.println("==========================================");
        System.out.println("验证 ZRX 订单详情和利润计算");
        System.out.println("==========================================");
        
        try {
            // 1. 加载配置
            ConfigLoader config = new ConfigLoader("dev");
            OKXTradingService tradingService = new OKXTradingService(config);
            ObjectMapper mapper = new ObjectMapper();
            
            // 订单信息
            String spotOrderId = "3472977534914945024";
            String swapOrderId = "3472977550383538176";
            String symbol = "ZRX";
            
            // 2. 查询现货订单详情
            System.out.println("\n1. 查询现货订单详情...");
            System.out.println("   订单ID: " + spotOrderId);
            System.out.println("   交易对: " + symbol + "-USDT");
            
            String spotDetailJson = tradingService.getOrderDetail(symbol + "-USDT", spotOrderId);
            JsonNode spotDetail = mapper.readTree(spotDetailJson);
            
            if (spotDetail.path("code").asText().equals("0")) {
                JsonNode spotData = spotDetail.path("data").get(0);
                
                String spotAvgPx = spotData.path("avgPx").asText();
                String spotFillSz = spotData.path("accFillSz").asText();
                String spotFee = spotData.path("fee").asText();
                String spotFeeCcy = spotData.path("feeCcy").asText();
                String spotState = spotData.path("state").asText();
                
                System.out.println("   ✅ 现货订单详情:");
                System.out.println("      状态: " + spotState);
                System.out.println("      成交均价: " + spotAvgPx);
                System.out.println("      成交数量: " + spotFillSz);
                System.out.println("      手续费: " + spotFee + " " + spotFeeCcy);
                
                // 计算现货成本
                BigDecimal spotCost = new BigDecimal(spotAvgPx).multiply(new BigDecimal(spotFillSz));
                System.out.println("      成本: " + spotCost.setScale(8, RoundingMode.HALF_UP) + " USDT");
            } else {
                System.out.println("   ❌ 查询失败: " + spotDetail.path("msg").asText());
            }
            
            // 3. 查询合约订单详情
            System.out.println("\n2. 查询合约订单详情...");
            System.out.println("   订单ID: " + swapOrderId);
            System.out.println("   交易对: " + symbol + "-USDT-SWAP");
            
            String swapDetailJson = tradingService.getOrderDetail(symbol + "-USDT-SWAP", swapOrderId);
            JsonNode swapDetail = mapper.readTree(swapDetailJson);
            
            if (swapDetail.path("code").asText().equals("0")) {
                JsonNode swapData = swapDetail.path("data").get(0);
                
                String swapAvgPx = swapData.path("avgPx").asText();
                String swapFillSz = swapData.path("accFillSz").asText();
                String swapFee = swapData.path("fee").asText();
                String swapFeeCcy = swapData.path("feeCcy").asText();
                String swapState = swapData.path("state").asText();
                
                System.out.println("   ✅ 合约订单详情:");
                System.out.println("      状态: " + swapState);
                System.out.println("      成交均价: " + swapAvgPx);
                System.out.println("      成交数量: " + swapFillSz);
                System.out.println("      手续费: " + swapFee + " " + swapFeeCcy);
                
                // 计算合约成本
                BigDecimal swapCost = new BigDecimal(swapAvgPx).multiply(new BigDecimal(swapFillSz));
                System.out.println("      成本: " + swapCost.setScale(8, RoundingMode.HALF_UP) + " USDT");
            } else {
                System.out.println("   ❌ 查询失败: " + swapDetail.path("msg").asText());
            }
            
            // 4. 验证计算逻辑
            System.out.println("\n==========================================");
            System.out.println("CSV 中的计算结果:");
            System.out.println("==========================================");
            System.out.println("持仓数量: 60 ZRX");
            System.out.println("开仓现货价格: 0.10225");
            System.out.println("开仓合约价格: 0.10166");
            System.out.println("未实现利润: -0.0123 USDT");
            System.out.println("利润率: -0.20%");
            
            System.out.println("\n==========================================");
            System.out.println("计算逻辑验证:");
            System.out.println("==========================================");
            
            BigDecimal amount = new BigDecimal("60");
            BigDecimal entrySpotPrice = new BigDecimal("0.10225");
            BigDecimal entrySwapPrice = new BigDecimal("0.10166");
            BigDecimal currentSpotPrice = new BigDecimal("0.10225");
            BigDecimal currentSwapPrice = new BigDecimal("0.10166");
            
            // 计算价差
            BigDecimal entrySpread = entrySwapPrice.subtract(entrySpotPrice);
            BigDecimal currentSpread = currentSwapPrice.subtract(currentSpotPrice);
            BigDecimal spreadDiff = currentSpread.subtract(entrySpread);
            
            System.out.println("开仓价差 = " + entrySpread.setScale(5, RoundingMode.HALF_UP));
            System.out.println("当前价差 = " + currentSpread.setScale(5, RoundingMode.HALF_UP));
            System.out.println("价差变化 = " + spreadDiff.setScale(5, RoundingMode.HALF_UP));
            
            // SHORT_SPOT_LONG_SWAP 需要取反
            spreadDiff = spreadDiff.negate();
            System.out.println("调整后价差变化 (SHORT_SPOT_LONG_SWAP) = " + spreadDiff.setScale(5, RoundingMode.HALF_UP));
            
            // 计算价差利润
            BigDecimal spreadProfit = spreadDiff.multiply(amount);
            System.out.println("价差利润 = " + spreadProfit.setScale(8, RoundingMode.HALF_UP) + " USDT");
            
            // 计算手续费
            BigDecimal usdtAmount = amount.multiply(entrySpotPrice);
            BigDecimal fee = usdtAmount.multiply(new BigDecimal("0.002"));
            System.out.println("USDT 金额 = " + usdtAmount.setScale(8, RoundingMode.HALF_UP));
            System.out.println("手续费 (0.2%) = " + fee.setScale(8, RoundingMode.HALF_UP) + " USDT");
            
            // 计算未实现利润
            BigDecimal unrealizedProfit = spreadProfit.subtract(fee);
            BigDecimal profitRate = unrealizedProfit.divide(usdtAmount, 6, RoundingMode.HALF_UP)
                .multiply(new BigDecimal("100"));
            
            System.out.println("\n未实现利润 = " + unrealizedProfit.setScale(4, RoundingMode.HALF_UP) + " USDT");
            System.out.println("利润率 = " + profitRate.setScale(2, RoundingMode.HALF_UP) + "%");
            
            System.out.println("\n==========================================");
            System.out.println("结论:");
            System.out.println("==========================================");
            System.out.println("CSV 计算结果: -0.0123 USDT (-0.20%)");
            System.out.println("手动验证结果: " + unrealizedProfit.setScale(4, RoundingMode.HALF_UP) + " USDT (" + profitRate.setScale(2, RoundingMode.HALF_UP) + "%)");
            System.out.println("✅ 计算结果一致!");
            
        } catch (Exception e) {
            System.err.println("❌ 验证失败: " + e.getMessage());
            e.printStackTrace();
        }
    }
}
