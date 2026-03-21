#!/bin/bash
# 诊断 StreamPark 作业提交失败的问题

set -e

echo "=========================================="
echo "StreamPark 作业诊断"
echo "=========================================="

# 1. 检查 StreamPark 日志
echo ""
echo "1. 检查 StreamPark 最新日志..."
docker logs streampark 2>&1 | tail -50 | grep -E "(ERROR|Exception|Failed|System.exit)" || echo "未发现明显错误"

# 2. 检查配置文件是否存在
echo ""
echo "2. 检查配置文件..."
if [ -f "src/main/resources/config/application.yml" ]; then
    echo "✓ application.yml 存在"
else
    echo "✗ application.yml 不存在"
fi

if [ -f "src/main/resources/config/application-dev.yml" ]; then
    echo "✓ application-dev.yml 存在"
else
    echo "✗ application-dev.yml 不存在"
fi

# 3. 检查 JAR 包中的配置文件
echo ""
echo "3. 检查 JAR 包内容..."
JAR_FILE=$(find target -name "*.jar" -not -name "*-sources.jar" -not -name "*-javadoc.jar" 2>/dev/null | head -1)

if [ -n "$JAR_FILE" ]; then
    echo "找到 JAR 文件: $JAR_FILE"
    echo ""
    echo "JAR 包中的配置文件:"
    jar tf "$JAR_FILE" | grep -E "config/.*\\.yml" || echo "未找到配置文件"
    echo ""
    echo "JAR 包中的类文件:"
    jar tf "$JAR_FILE" | grep -E "com/crypto/dw/.*\\.class" | head -10
else
    echo "未找到 JAR 文件，请先编译: mvn clean package -DskipTests"
fi

# 4. 检查 Flink 集群状态
echo ""
echo "4. 检查 Flink 集群状态..."
if curl -s http://localhost:8081/overview > /dev/null 2>&1; then
    echo "✓ Flink 集群运行正常"
    curl -s http://localhost:8081/overview | grep -o '"taskmanagers":[0-9]*' || true
else
    echo "✗ Flink 集群无法访问"
fi

# 5. 检查 Kafka 状态
echo ""
echo "5. 检查 Kafka 状态..."
if docker ps | grep -q kafka; then
    echo "✓ Kafka 容器运行中"
else
    echo "✗ Kafka 容器未运行"
fi

# 6. 检查 Doris 状态
echo ""
echo "6. 检查 Doris 状态..."
if docker ps | grep -q doris-fe; then
    echo "✓ Doris FE 运行中"
else
    echo "✗ Doris FE 未运行"
fi

if docker ps | grep -q doris-be; then
    echo "✓ Doris BE 运行中"
else
    echo "✗ Doris BE 未运行"
fi

# 7. 建议
echo ""
echo "=========================================="
echo "诊断建议"
echo "=========================================="
echo ""
echo "常见问题和解决方案:"
echo ""
echo "1. 配置文件未打包到 JAR"
echo "   解决: 确保 pom.xml 中包含资源配置"
echo "   <resources>"
echo "     <resource>"
echo "       <directory>src/main/resources</directory>"
echo "     </resource>"
echo "   </resources>"
echo ""
echo "2. 环境变量缺失"
echo "   解决: 在 StreamPark 作业配置中添加环境变量"
echo "   APP_ENV=dev"
echo ""
echo "3. 依赖冲突"
echo "   解决: 检查 pom.xml 中的依赖版本"
echo "   mvn dependency:tree"
echo ""
echo "4. Flink 集群连接失败"
echo "   解决: 确保 StreamPark 可以访问 Flink 集群"
echo "   docker exec streampark ping -c 3 jobmanager"
echo ""
echo "5. 本地模式运行测试"
echo "   解决: 先在本地运行测试"
echo "   bash run-flink-ods-datastream.sh"
echo ""
