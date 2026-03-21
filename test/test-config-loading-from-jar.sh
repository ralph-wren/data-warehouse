#!/bin/bash

# 测试从 JAR 包加载配置文件
# 用于验证 ConfigLoader 是否能正确加载配置文件

echo "========================================="
echo "测试从 JAR 包加载配置文件"
echo "========================================="
echo ""

# 1. 检查 JAR 包是否存在
echo "1. 检查 JAR 包..."
if [ ! -f "target/realtime-crypto-datawarehouse-1.0.0.jar" ]; then
    echo "❌ JAR 包不存在，请先编译项目"
    echo "   运行: mvn clean package -DskipTests"
    exit 1
fi
echo "✅ JAR 包存在"
echo ""

# 2. 检查 JAR 包中的配置文件
echo "2. 检查 JAR 包中的配置文件..."
jar tf target/realtime-crypto-datawarehouse-1.0.0.jar | grep "application.*yml"
echo ""

# 3. 提取配置文件内容
echo "3. 提取 application-docker.yml 内容（前 10 行）..."
jar xf target/realtime-crypto-datawarehouse-1.0.0.jar config/application-docker.yml 2>/dev/null
if [ -f "config/application-docker.yml" ]; then
    head -10 config/application-docker.yml
    echo "✅ 配置文件提取成功"
else
    echo "❌ 配置文件提取失败"
fi
echo ""

# 4. 测试 Java ClassLoader 加载
echo "4. 测试 Java ClassLoader 加载配置文件..."
cat > /tmp/TestConfigLoader.java << 'EOF'
import java.io.InputStream;

public class TestConfigLoader {
    public static void main(String[] args) {
        String[] paths = {
            "config/application-docker.yml",
            "application-docker.yml",
            "/application-docker.yml",
            "/config/application-docker.yml"
        };
        
        System.out.println("测试 ClassLoader 加载配置文件:");
        System.out.println("JAR 文件: " + args[0]);
        System.out.println("");
        
        for (String path : paths) {
            InputStream is = TestConfigLoader.class.getClassLoader().getResourceAsStream(path);
            if (is != null) {
                System.out.println("✅ 成功加载: " + path);
                try {
                    is.close();
                } catch (Exception e) {}
            } else {
                System.out.println("❌ 加载失败: " + path);
            }
        }
    }
}
EOF

# 编译测试类
javac /tmp/TestConfigLoader.java 2>/dev/null

# 运行测试（将 JAR 包添加到 classpath）
if [ -f "/tmp/TestConfigLoader.class" ]; then
    java -cp "target/realtime-crypto-datawarehouse-1.0.0.jar:/tmp" TestConfigLoader "target/realtime-crypto-datawarehouse-1.0.0.jar"
else
    echo "❌ 测试类编译失败"
fi
echo ""

# 5. 清理临时文件
echo "5. 清理临时文件..."
rm -rf config/
rm -f /tmp/TestConfigLoader.java /tmp/TestConfigLoader.class
echo "✅ 清理完成"
echo ""

echo "========================================="
echo "测试完成"
echo "========================================="
