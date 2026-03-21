#!/bin/bash

# StreamPark Java 11 验证脚本
# 用于验证 Java 11 是否正确挂载到 StreamPark 容器

echo "=========================================="
echo "StreamPark Java 11 验证"
echo "=========================================="

# 检查 StreamPark 容器是否运行
if ! docker ps --filter "name=streampark" --format "{{.Names}}" | grep -q streampark; then
    echo "❌ StreamPark 容器未运行"
    echo ""
    echo "请先启动 StreamPark:"
    echo "  docker-compose -f docker-compose-streampark.yml up -d"
    exit 1
fi

echo ""
echo "✅ StreamPark 容器正在运行"
echo ""

# 1. 检查 Java 11 目录是否挂载
echo "1. 检查 Java 11 目录挂载..."
if docker exec streampark test -d /opt/java11; then
    echo "   ✅ Java 11 目录已挂载: /opt/java11"
else
    echo "   ❌ Java 11 目录未挂载"
    echo ""
    echo "   请检查 docker-compose-streampark.yml 中的挂载配置:"
    echo "   - E:\\DataFiles\\Java\\jdk-11:/opt/java11:ro"
    exit 1
fi

# 2. 检查 Java 版本
echo ""
echo "2. 检查 Java 版本..."
JAVA_VERSION=$(docker exec streampark /opt/java11/bin/java -version 2>&1 | head -1)
echo "   Java 版本: $JAVA_VERSION"

if echo "$JAVA_VERSION" | grep -q "11\."; then
    echo "   ✅ Java 11 版本正确"
else
    echo "   ⚠️  Java 版本可能不是 11"
fi

# 3. 检查 JAVA_HOME 环境变量
echo ""
echo "3. 检查 JAVA_HOME 环境变量..."
JAVA_HOME_VALUE=$(docker exec streampark printenv JAVA_HOME)
echo "   JAVA_HOME: $JAVA_HOME_VALUE"

if [ "$JAVA_HOME_VALUE" = "/opt/java11" ]; then
    echo "   ✅ JAVA_HOME 配置正确"
else
    echo "   ⚠️  JAVA_HOME 配置可能不正确"
fi

# 4. 检查 PATH 环境变量
echo ""
echo "4. 检查 PATH 环境变量..."
PATH_VALUE=$(docker exec streampark printenv PATH)
if echo "$PATH_VALUE" | grep -q "/opt/java11/bin"; then
    echo "   ✅ PATH 包含 Java 11"
else
    echo "   ⚠️  PATH 可能不包含 Java 11"
fi

# 5. 检查默认 java 命令
echo ""
echo "5. 检查默认 java 命令..."
DEFAULT_JAVA=$(docker exec streampark which java 2>/dev/null)
if [ -n "$DEFAULT_JAVA" ]; then
    echo "   默认 java 路径: $DEFAULT_JAVA"
    DEFAULT_JAVA_VERSION=$(docker exec streampark java -version 2>&1 | head -1)
    echo "   默认 java 版本: $DEFAULT_JAVA_VERSION"
    
    if echo "$DEFAULT_JAVA_VERSION" | grep -q "11\."; then
        echo "   ✅ 默认 java 是 Java 11"
    else
        echo "   ⚠️  默认 java 不是 Java 11"
        echo ""
        echo "   这可能是因为容器内置的 Java 8 优先级更高"
        echo "   但 /opt/java11/bin/java 仍然可用"
    fi
else
    echo "   ⚠️  未找到默认 java 命令"
fi

# 6. 测试编译 Java 11 代码
echo ""
echo "6. 测试编译 Java 11 代码..."
docker exec streampark bash -c 'cat > /tmp/Test.java << EOF
public class Test {
    public static void main(String[] args) {
        log.info("Java version: " + System.getProperty("java.version"));
        log.info("Java home: " + System.getProperty("java.home"));
    }
}
EOF'

if docker exec streampark /opt/java11/bin/javac /tmp/Test.java 2>/dev/null; then
    echo "   ✅ Java 11 编译成功"
    
    # 运行测试
    echo ""
    echo "   运行测试程序:"
    docker exec streampark /opt/java11/bin/java -cp /tmp Test
else
    echo "   ❌ Java 11 编译失败"
fi

# 清理测试文件
docker exec streampark rm -f /tmp/Test.java /tmp/Test.class 2>/dev/null

echo ""
echo "=========================================="
echo "验证完成"
echo "=========================================="
echo ""
echo "📌 使用建议:"
echo ""
echo "1. 在 StreamPark 中编译项目时，确保使用 Java 11:"
echo "   - 项目 pom.xml 中设置: <maven.compiler.source>11</maven.compiler.source>"
echo "   - StreamPark 会自动使用 /opt/java11/bin/java"
echo ""
echo "2. 如果遇到 Java 版本问题，可以在 StreamPark 配置中指定:"
echo "   - JAVA_HOME=/opt/java11"
echo "   - 或在项目构建命令中使用绝对路径"
echo ""
echo "3. 查看 StreamPark 日志:"
echo "   docker logs streampark"
echo ""
