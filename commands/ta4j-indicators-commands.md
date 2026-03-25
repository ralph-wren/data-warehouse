# ta4j 指标相关命令

## 编译命令

### 清理并编译项目
```bash
mvn clean compile -DskipTests
```

### 查看编译错误详情
```bash
mvn clean compile -DskipTests 2>&1 | tail -50
```

### 查看完整编译日志
```bash
mvn clean compile -DskipTests -X
```

## 测试命令

### 运行 ta4j 0.21 新增指标测试
```bash
bash test/test-ta4j-021-indicators.sh
```

### 给测试脚本添加执行权限
```bash
chmod +x test/test-ta4j-021-indicators.sh
```

## 策略使用示例

### 在代码中使用新策略

```java
// 1. 沙夫趋势周期策略
Strategy strategy = StrategyRegisterCenter.createStrategy(series, "SCHAFF_TREND_CYCLE");

// 2. 百分位排名策略
Strategy strategy = StrategyRegisterCenter.createStrategy(series, "PERCENT_RANK");

// 3. 净动量策略
Strategy strategy = StrategyRegisterCenter.createStrategy(series, "NET_MOMENTUM");

// 4. 通用随机指标策略
Strategy strategy = StrategyRegisterCenter.createStrategy(series, "STOCHASTIC_INDICATOR");
```

## 查看 ta4j 版本

### 查看项目中的 ta4j 版本
```bash
mvn dependency:tree | grep ta4j
```

### 查看 ta4j 依赖详情
```bash
mvn dependency:tree -Dincludes=org.ta4j:ta4j-core
```

## 搜索指标使用

### 搜索项目中使用的 ta4j 指标
```bash
grep -r "import org.ta4j.core.indicators" src/main/java --include="*.java"
```

### 搜索特定指标的使用
```bash
grep -r "SchaffTrendCycleIndicator" src/main/java --include="*.java"
grep -r "PercentRankIndicator" src/main/java --include="*.java"
grep -r "NetMomentumIndicator" src/main/java --include="*.java"
```

## 策略工厂相关

### 查看所有策略工厂
```bash
ls -la src/main/java/com/crypto/dw/strategy/StrategyFactory*.java
```

### 统计策略数量
```bash
grep -c "public static Strategy create" src/main/java/com/crypto/dw/strategy/StrategyFactory*.java
```

### 查看策略注册
```bash
grep "strategyCreators.put" src/main/java/com/crypto/dw/strategy/StrategyRegisterCenter.java | wc -l
```

## 文档生成

### 查看所有 ta4j 相关文档
```bash
ls -la docs/*ta4j*.md
```

### 搜索文档中的指标说明
```bash
grep -r "SchaffTrendCycle\|PercentRank\|NetMomentum" docs/*.md
```

## 代码检查

### 检查编译警告
```bash
mvn clean compile -DskipTests 2>&1 | grep WARNING
```

### 检查是否有未使用的 import
```bash
mvn clean compile -DskipTests 2>&1 | grep "never used"
```

## Git 操作

### 查看修改的文件
```bash
git status
```

### 查看具体修改内容
```bash
git diff src/main/java/com/crypto/dw/strategy/StrategyFactory5.java
git diff src/main/java/com/crypto/dw/strategy/StrategyRegisterCenter.java
```

### 提交代码
```bash
git add src/main/java/com/crypto/dw/strategy/StrategyFactory5.java
git add src/main/java/com/crypto/dw/strategy/StrategyRegisterCenter.java
git add docs/202603252030-ta4j-0.21新增指标分析和引入建议.md
git add docs/202603252040-ta4j-0.21新增指标引入实施.md
git add test/test-ta4j-021-indicators.sh
git add commands/ta4j-indicators-commands.md
git commit -m "feat: 引入 ta4j 0.21 新增指标 - SchaffTrendCycle, PercentRank, NetMomentum, StochasticIndicator"
```

## 性能测试

### 运行回测测试策略性能
```bash
# 需要实际实现回测功能
# mvn exec:java -Dexec.mainClass="com.crypto.dw.backtest.BacktestRunner" \
#     -Dexec.args="--strategy SCHAFF_TREND_CYCLE --symbol BTCUSDT --period 1d"
```

## 常见问题排查

### 问题1: 找不到指标类
```bash
# 检查 ta4j 版本
mvn dependency:tree | grep ta4j

# 确认是否是 0.21 版本
grep "<ta4j.version>" pom.xml
```

### 问题2: 构造函数参数错误
```bash
# 查看编译错误详情
mvn clean compile -DskipTests 2>&1 | grep "构造器"

# 搜索官方文档或源码
# https://github.com/ta4j/ta4j
```

### 问题3: 类型转换错误
```bash
# 查看类型不匹配错误
mvn clean compile -DskipTests 2>&1 | grep "不兼容的类型"

# 检查参数类型
# Num vs Number vs Double
```

## 学习资源

### ta4j 官方文档
```bash
# 在浏览器中打开
open https://ta4j.github.io/ta4j-wiki/

# 查看指标清单
open https://ta4j.github.io/ta4j-wiki/Indicators-Inventory.html

# 查看发布说明
open https://ta4j.github.io/ta4j-wiki/Release-notes.html
```

### ta4j GitHub 仓库
```bash
# 克隆 ta4j 源码(可选)
git clone https://github.com/ta4j/ta4j.git

# 查看示例代码
cd ta4j/ta4j-examples
```

## 总结

本文档记录了 ta4j 指标开发和测试相关的常用命令,包括:
- 编译和测试命令
- 策略使用示例
- 代码检查和搜索
- Git 操作
- 问题排查
- 学习资源

使用这些命令可以高效地开发和测试 ta4j 指标策略。
