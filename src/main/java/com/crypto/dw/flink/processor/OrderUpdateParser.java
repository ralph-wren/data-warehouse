package com.crypto.dw.flink.processor;

import com.crypto.dw.flink.model.OrderUpdate;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.flink.api.common.functions.RichMapFunction;

import java.math.BigDecimal;

/**
 * 订单更新解析器
 * 
 * 功能：
 * 1. 解析 OKX WebSocket 订单更新消息
 * 2. 提取订单关键信息
 * 3. 转换为 OrderUpdate 对象
 */
public class OrderUpdateParser extends RichMapFunction<String, OrderUpdate> {
    
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    
    @Override
    public OrderUpdate map(String json) throws Exception {
        JsonNode rootNode = OBJECT_MAPPER.readTree(json);
        
        // 检查是否是订单数据
        if (!rootNode.has("data")) {
            return null;
        }
        
        JsonNode dataArray = rootNode.get("data");
        if (!dataArray.isArray() || dataArray.size() == 0) {
            return null;
        }
        
        JsonNode orderNode = dataArray.get(0);
        
        OrderUpdate order = new OrderUpdate();
        order.orderId = orderNode.get("ordId").asText();
        order.symbol = extractSymbol(orderNode.get("instId").asText());
        order.instType = orderNode.get("instType").asText();
        order.side = orderNode.get("side").asText();
        order.state = orderNode.get("state").asText();
        
        // 成交价格和数量
        if (orderNode.has("fillPx")) {
            order.fillPrice = new BigDecimal(orderNode.get("fillPx").asText());
        }
        if (orderNode.has("fillSz")) {
            order.fillSize = new BigDecimal(orderNode.get("fillSz").asText());
        }
        
        // 手续费信息
        if (orderNode.has("fee")) {
            order.fee = new BigDecimal(orderNode.get("fee").asText());
        }
        if (orderNode.has("feeCcy")) {
            order.feeCcy = orderNode.get("feeCcy").asText();
        }
        
        order.timestamp = System.currentTimeMillis();
        
        return order;
    }
    
    private String extractSymbol(String instId) {
        // BTC-USDT-SWAP → BTC-USDT
        // BTC-USDT → BTC-USDT
        if (instId.endsWith("-SWAP")) {
            return instId.substring(0, instId.length() - 5);
        }
        return instId;
    }
}
