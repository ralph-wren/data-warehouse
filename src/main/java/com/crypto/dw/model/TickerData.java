package com.crypto.dw.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.math.BigDecimal;

/**
 * OKX Ticker 数据模型
 * 
 * 更新说明：
 * - 添加辅助方法，方便获取 BigDecimal 类型的数值
 * - 添加 hasAnomaly 字段，用于标记价格异常
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class TickerData {
    
    @JsonProperty("instId")
    private String instId;           // 交易对，例如 "BTC-USDT"
    
    @JsonProperty("last")
    private String last;             // 最新成交价
    
    @JsonProperty("lastSz")
    private String lastSz;           // 最新成交数量
    
    @JsonProperty("askPx")
    private String askPx;            // 卖一价
    
    @JsonProperty("askSz")
    private String askSz;            // 卖一量
    
    @JsonProperty("bidPx")
    private String bidPx;            // 买一价
    
    @JsonProperty("bidSz")
    private String bidSz;            // 买一量
    
    @JsonProperty("open24h")
    private String open24h;          // 24小时开盘价
    
    @JsonProperty("high24h")
    private String high24h;          // 24小时最高价
    
    @JsonProperty("low24h")
    private String low24h;           // 24小时最低价
    
    @JsonProperty("volCcy24h")
    private String volCcy24h;        // 24小时成交量（计价货币）
    
    @JsonProperty("vol24h")
    private String vol24h;           // 24小时成交量（交易货币）
    
    @JsonProperty("ts")
    private String ts;               // 数据生成时间戳（毫秒）
    
    @JsonProperty("sodUtc0")
    private String sodUtc0;          // UTC 0 时开盘价
    
    @JsonProperty("sodUtc8")
    private String sodUtc8;          // UTC+8 时开盘价
    
    // 非 JSON 字段：用于 Flink 处理
    private boolean hasAnomaly = false;  // 是否检测到价格异常
    
    // Getters and Setters
    
    public String getInstId() {
        return instId;
    }
    
    public void setInstId(String instId) {
        this.instId = instId;
    }
    
    public String getLast() {
        return last;
    }
    
    public void setLast(String last) {
        this.last = last;
    }
    
    public String getLastSz() {
        return lastSz;
    }
    
    public void setLastSz(String lastSz) {
        this.lastSz = lastSz;
    }
    
    public String getAskPx() {
        return askPx;
    }
    
    public void setAskPx(String askPx) {
        this.askPx = askPx;
    }
    
    public String getAskSz() {
        return askSz;
    }
    
    public void setAskSz(String askSz) {
        this.askSz = askSz;
    }
    
    public String getBidPx() {
        return bidPx;
    }
    
    public void setBidPx(String bidPx) {
        this.bidPx = bidPx;
    }
    
    public String getBidSz() {
        return bidSz;
    }
    
    public void setBidSz(String bidSz) {
        this.bidSz = bidSz;
    }
    
    public String getOpen24h() {
        return open24h;
    }
    
    public void setOpen24h(String open24h) {
        this.open24h = open24h;
    }
    
    public String getHigh24h() {
        return high24h;
    }
    
    public void setHigh24h(String high24h) {
        this.high24h = high24h;
    }
    
    public String getLow24h() {
        return low24h;
    }
    
    public void setLow24h(String low24h) {
        this.low24h = low24h;
    }
    
    public String getVolCcy24h() {
        return volCcy24h;
    }
    
    public void setVolCcy24h(String volCcy24h) {
        this.volCcy24h = volCcy24h;
    }
    
    public String getVol24h() {
        return vol24h;
    }
    
    public void setVol24h(String vol24h) {
        this.vol24h = vol24h;
    }
    
    public String getTs() {
        return ts;
    }
    
    public void setTs(String ts) {
        this.ts = ts;
    }
    
    public String getSodUtc0() {
        return sodUtc0;
    }
    
    public void setSodUtc0(String sodUtc0) {
        this.sodUtc0 = sodUtc0;
    }
    
    public String getSodUtc8() {
        return sodUtc8;
    }
    
    public void setSodUtc8(String sodUtc8) {
        this.sodUtc8 = sodUtc8;
    }
    
    public boolean isHasAnomaly() {
        return hasAnomaly;
    }
    
    public void setHasAnomaly(boolean hasAnomaly) {
        this.hasAnomaly = hasAnomaly;
    }
    
    // 辅助方法：获取 BigDecimal 类型的数值
    // 说明：方便 Flink 处理时使用，避免重复的字符串转换
    
    /**
     * 获取交易对符号（去掉 -USDT 后缀）
     */
    public String getSymbol() {
        if (instId != null) {
            return instId.replace("-USDT", "");
        }
        return instId;
    }
    
    /**
     * 获取最新成交价（BigDecimal 类型）
     */
    public BigDecimal getLastPrice() {
        try {
            return last != null ? new BigDecimal(last) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 获取 24 小时交易量（BigDecimal 类型）
     */
    public BigDecimal getVolume24h() {
        try {
            return vol24h != null ? new BigDecimal(vol24h) : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
    
    /**
     * 获取时间戳（long 类型）
     */
    public long getTimestamp() {
        try {
            return ts != null ? Long.parseLong(ts) : System.currentTimeMillis();
        } catch (NumberFormatException e) {
            return System.currentTimeMillis();
        }
    }
    
    @Override
    public String toString() {
        return "TickerData{" +
                "instId='" + instId + '\'' +
                ", last='" + last + '\'' +
                ", bidPx='" + bidPx + '\'' +
                ", askPx='" + askPx + '\'' +
                ", high24h='" + high24h + '\'' +
                ", low24h='" + low24h + '\'' +
                ", vol24h='" + vol24h + '\'' +
                ", ts='" + ts + '\'' +
                ", hasAnomaly=" + hasAnomaly +
                '}';
    }
}
