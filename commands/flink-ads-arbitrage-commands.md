# Flink ADS Arbitrage 相关命令

## 代码定位

- `rg -n "class FlinkADSArbitrageJob|FlinkADSArbitrageJob" -S src`
- `sed -n '1,220p' src/main/java/com/crypto/dw/flink/FlinkADSArbitrageJob.java`

## 时间戳生成

- `date "+%Y%m%d%H%M"`

## 编译验证

- `mvn -q -DskipTests compile`
