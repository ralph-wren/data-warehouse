#!/bin/bash

# 测试实时交易信号生成作业
# 验证各个组件是否正常工作

echo "=========================================="
echo "测试 ADS层 - 实时交易信号生成作业"
echo "=========================================="

# 加载环境变量
if [ -f .env ]; then
    export $(cat .env | grep -v '^#' | xargs)
fi

REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
DORIS_HOST=${DORIS_HOST:-127.0.0.1}
DORIS_PORT=${DORIS_PORT:-9030}

echo ""
echo "1. 检查Redis连接..."
if command -v redis-cli &> /dev/null; then
    if redis-cli -h $REDIS_HOST -p $REDIS_PORT ping > /dev/null 2>&1; then
        echo "✓ Redis连接正常"
    else
        echo "✗ Redis连接失败"
        exit 1
    fi
else
    echo "⚠ redis-cli未安装，跳过Redis检查"
fi

echo ""
echo "2. 检查Redis中的交易策略..."
if command -v redis-cli &> /dev/null; then
    strategy_count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT KEYS "trading:strategy:*" | wc -l)
    echo "   Redis中共有 $strategy_count 个交易策略"
    
    if [ $strategy_count -eq 0 ]; then
        echo "⚠ 没有找到交易策略，请先运行: ./scripts/init-trading-strategy.sh"
    else
        echo "✓ 交易策略已配置"
        
        # 显示几个示例策略
        echo ""
        echo "   示例策略:"
        redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "trading:strategy:BTC-USDT" | head -3
    fi
fi

echo ""
echo "3. 检查Doris连接..."
if command -v mysql &> /dev/null; then
    if mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e "SELECT 1" > /dev/null 2>&1; then
        echo "✓ Doris连接正常"
    else
        echo "✗ Doris连接失败"
        exit 1
    fi
else
    echo "⚠ mysql客户端未安装，跳过Doris检查"
fi

echo ""
echo "4. 检查Doris表是否存在..."
if command -v mysql &> /dev/null; then
    # 检查主信号表
    if mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e "DESC crypto_ads.ads_trading_signals" > /dev/null 2>&1; then
        echo "✓ ads_trading_signals 表存在"
    else
        echo "✗ ads_trading_signals 表不存在"
        echo "   请先运行: mysql -h $DORIS_HOST -P $DORIS_PORT -u root < sql/doris/create_ads_trading_signal_table.sql"
        exit 1
    fi
    
    # 检查高风险信号表
    if mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e "DESC crypto_ads.ads_high_risk_signals" > /dev/null 2>&1; then
        echo "✓ ads_high_risk_signals 表存在"
    else
        echo "✗ ads_high_risk_signals 表不存在"
        exit 1
    fi
fi

echo ""
echo "5. 检查Kafka Topic..."
if command -v kafka-topics.sh &> /dev/null; then
    if kafka-topics.sh --bootstrap-server localhost:9092 --list | grep -q "crypto_ticker_spot"; then
        echo "✓ crypto_ticker_spot Topic存在"
    else
        echo "✗ crypto_ticker_spot Topic不存在"
        echo "   请先创建Topic或启动数据采集作业"
    fi
else
    echo "⚠ kafka-topics.sh未找到，跳过Kafka检查"
fi

echo ""
echo "6. 检查JAR文件..."
if [ -f "target/realtime-crypto-datawarehouse-1.0.0.jar" ]; then
    echo "✓ JAR文件存在"
    jar_size=$(du -h target/realtime-crypto-datawarehouse-1.0.0.jar | cut -f1)
    echo "   文件大小: $jar_size"
else
    echo "✗ JAR文件不存在"
    echo "   请先编译: mvn clean package -DskipTests"
    exit 1
fi

echo ""
echo "7. 检查Flink CEP依赖..."
if jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep -q "org/apache/flink/cep"; then
    echo "✓ Flink CEP依赖已包含"
else
    echo "✗ Flink CEP依赖缺失"
    echo "   请检查pom.xml中的flink-cep依赖"
    exit 1
fi

echo ""
echo "=========================================="
echo "所有检查通过！可以启动作业了"
echo "=========================================="
echo ""
echo "启动命令:"
echo "  ./run-flink-ads-trading-signal.sh"
echo ""
echo "监控命令:"
echo "  # 查看最新信号"
echo "  mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e \\"
echo "    \"SELECT symbol, signal_type, current_price, confidence, risk_score, create_time \\"
echo "     FROM crypto_ads.ads_trading_signals ORDER BY timestamp DESC LIMIT 10;\""
echo ""
echo "  # 查看高风险信号"
echo "  mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e \\"
echo "    \"SELECT symbol, signal_type, risk_score, create_time \\"
echo "     FROM crypto_ads.ads_high_risk_signals ORDER BY risk_score DESC LIMIT 10;\""
echo ""
