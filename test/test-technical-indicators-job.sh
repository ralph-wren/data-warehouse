#!/bin/bash

# 测试实时技术指标计算作业
# 验证各个组件是否正常工作

echo "=========================================="
echo "测试 ADS层 - 实时技术指标计算作业"
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
echo "2. 检查Redis中的指标配置..."
if command -v redis-cli &> /dev/null; then
    config_count=$(redis-cli -h $REDIS_HOST -p $REDIS_PORT KEYS "indicator:config:*" | wc -l)
    echo "   Redis中共有 $config_count 个指标配置"
    
    if [ $config_count -eq 0 ]; then
        echo "⚠ 没有找到指标配置，请先运行: ./scripts/init-technical-indicators.sh"
    else
        echo "✓ 指标配置已设置"
        
        # 显示几个示例配置
        echo ""
        echo "   示例配置:"
        redis-cli -h $REDIS_HOST -p $REDIS_PORT GET "indicator:config:ma_20" | head -3
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
    if mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e "DESC crypto_ads.ads_technical_indicators" > /dev/null 2>&1; then
        echo "✓ ads_technical_indicators 表存在"
    else
        echo "✗ ads_technical_indicators 表不存在"
        echo "   请先运行: mysql -h $DORIS_HOST -P $DORIS_PORT -u root < sql/doris/create_ads_technical_indicators_table.sql"
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
echo "7. 检查TA4J依赖..."
if jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep -q "org/ta4j"; then
    echo "✓ TA4J依赖已包含"
else
    echo "✗ TA4J依赖缺失"
    echo "   请检查pom.xml中的ta4j-core依赖"
    exit 1
fi

echo ""
echo "8. 检查自定义指标类..."
if jar -tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep -q "com/crypto/dw/indicators/CustomVolumeWeightedMA"; then
    echo "✓ 自定义指标类已包含"
else
    echo "✗ 自定义指标类缺失"
    echo "   请检查src/main/java/com/crypto/dw/indicators/目录"
    exit 1
fi

echo ""
echo "=========================================="
echo "所有检查通过！可以启动作业了"
echo "=========================================="
echo ""
echo "启动命令:"
echo "  ./run-flink-ads-technical-indicators.sh"
echo ""
echo "监控命令:"
echo "  # 查看最新指标"
echo "  mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e \\"
echo "    \"SELECT symbol, indicator_name, indicator_value, create_time \\"
echo "     FROM crypto_ads.ads_technical_indicators ORDER BY timestamp DESC LIMIT 10;\""
echo ""
echo "  # 查看MACD指标"
echo "  mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e \\"
echo "    \"SELECT symbol, indicator_value, \\"
echo "     JSON_EXTRACT(extra_values, '$.dif') as dif, \\"
echo "     JSON_EXTRACT(extra_values, '$.dea') as dea \\"
echo "     FROM crypto_ads.ads_technical_indicators \\"
echo "     WHERE indicator_type='MACD' ORDER BY timestamp DESC LIMIT 10;\""
echo ""
echo "  # 查看布林带"
echo "  mysql -h $DORIS_HOST -P $DORIS_PORT -u root -e \\"
echo "    \"SELECT symbol, \\"
echo "     JSON_EXTRACT(extra_values, '$.upper') as upper, \\"
echo "     indicator_value as middle, \\"
echo "     JSON_EXTRACT(extra_values, '$.lower') as lower \\"
echo "     FROM crypto_ads.ads_technical_indicators \\"
echo "     WHERE indicator_type='BOLL' ORDER BY timestamp DESC LIMIT 10;\""
echo ""
