package com.crypto.dw.processor;

import com.crypto.dw.model.ArbitrageOpportunity;
import org.apache.flink.api.common.state.BroadcastState;
import org.apache.flink.api.common.state.MapStateDescriptor;
import org.apache.flink.api.common.state.ReadOnlyBroadcastState;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.streaming.api.functions.co.BroadcastProcessFunction;
import org.apache.flink.util.Collector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 黑名单过滤器 - 广播处理函数
 * 
 * 功能：
 * 1. 接收广播的黑名单数据
 * 2. 更新广播状态
 * 3. 使用广播状态过滤数据流
 * 
 * 性能优化：
 * - 使用广播状态，避免每条数据查询 Redis
 * - 所有并行实例共享黑名单数据
 * - 定期更新黑名单，保持数据新鲜度
 */
public class BlacklistFilter 
        extends BroadcastProcessFunction<ArbitrageOpportunity, Tuple2<String, Boolean>, ArbitrageOpportunity> {
    
    private static final Logger logger = LoggerFactory.getLogger(BlacklistFilter.class);
    
    private final MapStateDescriptor<String, Boolean> blacklistStateDescriptor;
    
    public BlacklistFilter(MapStateDescriptor<String, Boolean> blacklistStateDescriptor) {
        this.blacklistStateDescriptor = blacklistStateDescriptor;
    }
    
    @Override
    public void processElement(
            ArbitrageOpportunity opportunity,
            ReadOnlyContext ctx,
            Collector<ArbitrageOpportunity> out) throws Exception {
        
        // 读取广播状态
        ReadOnlyBroadcastState<String, Boolean> blacklistState = 
            ctx.getBroadcastState(blacklistStateDescriptor);
        
        // 检查是否在黑名单中
        Boolean isBlacklisted = blacklistState.get(opportunity.symbol);
        
        if (isBlacklisted != null && isBlacklisted) {
            // 在黑名单中，过滤掉
            logger.debug("过滤黑名单交易对: {}", opportunity.symbol);
        } else {
            // 不在黑名单中，输出数据
            out.collect(opportunity);
        }
    }
    
    @Override
    public void processBroadcastElement(
            Tuple2<String, Boolean> blacklistEntry,
            Context ctx,
            Collector<ArbitrageOpportunity> out) throws Exception {
        
        // 更新广播状态
        BroadcastState<String, Boolean> blacklistState = 
            ctx.getBroadcastState(blacklistStateDescriptor);
        
        blacklistState.put(blacklistEntry.f0, blacklistEntry.f1);
        
        logger.info("更新黑名单: {} = {}", blacklistEntry.f0, blacklistEntry.f1);
    }
}
