package com.crypto.dw.config;


import com.crypto.dw.redis.RedisConnectionManager;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;


import java.math.BigDecimal;

/**
 * 回测参数配置类
 * 从Redis获取回测止损百分比和移动止盈百分比参数
 */

@Slf4j
@Getter
public class BacktestParameterConfig{

    // Redis键名
    private static final String STOP_LOSS_PERCENT_KEY = "backtest:stop_loss_percent";
    private static final String TRAILING_PROFIT_PERCENT_KEY = "backtest:trailing_profit_percent";

    // 默认值
    private static final BigDecimal DEFAULT_STOP_LOSS_PERCENT = new BigDecimal("0.05");
    private static final BigDecimal DEFAULT_TRAILING_PROFIT_PERCENT = new BigDecimal("0.05");

    private final ConfigLoader config = ConfigLoader.getInstance();
    private RedisConnectionManager redisManager = new RedisConnectionManager(config);

    // 当前参数值
    private BigDecimal stopLossPercent = DEFAULT_STOP_LOSS_PERCENT;
    private BigDecimal trailingProfitPercent = DEFAULT_TRAILING_PROFIT_PERCENT;

    /**
     * 应用启动时加载配置
     */
    public void init(){
        loadParametersFromRedis();
        log.info("初始化回测参数: 止损百分比={}, 移动止盈百分比={}",
            stopLossPercent, trailingProfitPercent);
    }

    /**
     * 从Redis加载参数
     */
    private void loadParametersFromRedis(){
        try{
            // 获取止损百分比
            String stopLossStr = redisManager.get(STOP_LOSS_PERCENT_KEY);
            if(StringUtils.isNoneBlank(stopLossStr)){
                stopLossPercent = new BigDecimal(stopLossStr);
            }

            // 获取移动止盈百分比
            String trailingProfitStr = redisManager.get(TRAILING_PROFIT_PERCENT_KEY);
            if(StringUtils.isNoneBlank(trailingProfitStr)){
                trailingProfitPercent = new BigDecimal(trailingProfitStr);
            }
        }catch(Exception e){
            log.error("从Redis加载回测参数失败，使用默认值", e);
            // 发生异常时保留当前值，不重置为默认值
        }
    }

    /**
     * 更新止损百分比
     *
     * @param percent 新的止损百分比
     */
    public void updateStopLossPercent(BigDecimal percent){
        if(percent != null && percent.compareTo(BigDecimal.ZERO) >= 0){
            stopLossPercent = percent;
            redisManager.set(STOP_LOSS_PERCENT_KEY, percent.toString());
            log.info("更新止损百分比: {}", percent);
        }
    }

    /**
     * 更新移动止盈百分比
     *
     * @param percent 新的移动止盈百分比
     */
    public void updateTrailingProfitPercent(BigDecimal percent){
        if(percent != null && percent.compareTo(BigDecimal.ZERO) >= 0){
            trailingProfitPercent = percent;
            redisManager.set(TRAILING_PROFIT_PERCENT_KEY, percent.toString());
            log.info("更新移动止盈百分比: {}", percent);
        }
    }

    /**
     * 重置为默认值
     */
    public void resetToDefaults(){
        stopLossPercent = DEFAULT_STOP_LOSS_PERCENT;
        trailingProfitPercent = DEFAULT_TRAILING_PROFIT_PERCENT;
        redisManager.set(STOP_LOSS_PERCENT_KEY, DEFAULT_STOP_LOSS_PERCENT.toString());
        redisManager.set(TRAILING_PROFIT_PERCENT_KEY, DEFAULT_TRAILING_PROFIT_PERCENT.toString());
        log.info("重置回测参数为默认值");
    }
}
