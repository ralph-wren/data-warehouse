package com.crypto.dw.trading;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 套利机会跟踪器
 * 
 * 用于跟踪套利机会的持续时间,确保机会稳定存在一段时间后再开仓
 */
public class OpportunityTracker implements Serializable {
    
    private static final long serialVersionUID = 1L;
    
    // 首次发现时间
    private long firstSeenTime;
    
    // 最后一次看到时间
    private long lastSeenTime;
    
    // 是否活跃
    private boolean active;
    
    // 价差率
    private BigDecimal spreadRate;
    
    // 最后日志输出时间(用于控制日志输出频率)
    private long lastLogTime;
    
    public OpportunityTracker() {
    }
    
    public long getFirstSeenTime() {
        return firstSeenTime;
    }
    
    public void setFirstSeenTime(long firstSeenTime) {
        this.firstSeenTime = firstSeenTime;
    }
    
    public long getLastSeenTime() {
        return lastSeenTime;
    }
    
    public void setLastSeenTime(long lastSeenTime) {
        this.lastSeenTime = lastSeenTime;
    }
    
    public boolean isActive() {
        return active;
    }
    
    public void setActive(boolean active) {
        this.active = active;
    }
    
    public BigDecimal getSpreadRate() {
        return spreadRate;
    }
    
    public void setSpreadRate(BigDecimal spreadRate) {
        this.spreadRate = spreadRate;
    }
    
    public long getLastLogTime() {
        return lastLogTime;
    }
    
    public void setLastLogTime(long lastLogTime) {
        this.lastLogTime = lastLogTime;
    }
    
    /**
     * 获取持续时间(毫秒)
     */
    public long getDuration() {
        return lastSeenTime - firstSeenTime;
    }
    
    /**
     * 获取持续时间(秒)
     */
    public double getDurationSeconds() {
        return getDuration() / 1000.0;
    }
}
