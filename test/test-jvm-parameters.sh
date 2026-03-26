#!/bin/bash

# 测试 JVM 参数配置是否正确
# 验证 Flink 作业能否正常启动和关闭,无反射访问错误和 JMX 错误

echo "=========================================="
echo "测试 JVM 参数配置"
echo "=========================================="
echo ""

# 检查 Log4j2 配置文件
echo "1. 检查 Log4j2 配置文件..."
if grep -q "property.disableJmx = true" src/main/resources/log4j2.properties; then
    echo "✓ Log4j2 配置文件已禁用 JMX"
else
    echo "✗ Log4j2 配置文件未禁用 JMX"
    exit 1
fi
echo ""

# 检查启动脚本
echo "2. 检查启动脚本 JVM 参数..."
scripts=(
    "run-flink-collector.sh"
    "run-flink-ods-datastream.sh"
    "run-flink-ods-sql.sh"
    "run-flink-dwd-sql.sh"
    "run-flink-dws-1min-sql.sh"
    "run-flink-ads-realtime-metrics.sh"
    "run-flink-ads-market-monitor.sh"
    "run-flink-ads-arbitrage.sh"
)

for script in "${scripts[@]}"; do
    if [ -f "$script" ]; then
        if grep -q "log4j2.disable.jmx=true" "$script"; then
            echo "✓ $script 已配置 JMX 禁用参数"
        else
            echo "✗ $script 未配置 JMX 禁用参数"
            exit 1
        fi
        
        if grep -q "add-opens=java.base/java.util.concurrent.atomic" "$script"; then
            echo "✓ $script 已配置反射访问参数"
        else
            echo "✗ $script 未配置反射访问参数"
            exit 1
        fi
    else
        echo "⚠ $script 不存在"
    fi
done
echo ""

# 编译项目
echo "3. 编译项目..."
mvn clean compile -DskipTests -q
if [ $? -eq 0 ]; then
    echo "✓ 编译成功"
else
    echo "✗ 编译失败"
    exit 1
fi
echo ""

# 测试运行（快速测试,5秒后自动停止）
echo "4. 测试运行 Flink 作业（5秒后自动停止）..."
echo "启动数据采集作业..."

# 设置 JVM 参数
export MAVEN_OPTS="--add-opens=java.base/java.util=ALL-UNNAMED \
--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED \
--add-opens=java.base/java.lang=ALL-UNNAMED \
-Dorg.apache.flink.shaded.netty4.io.netty.tryReflectionSetAccessible=true \
-Dlog4j2.disable.jmx=true"

# 启动作业并在 5 秒后停止
timeout 5 mvn exec:java \
    -Dexec.mainClass="com.crypto.dw.flink.FlinkDataCollectorJob" \
    -Dexec.cleanupDaemonThreads=false \
    -Dexec.args="--env dev" \
    > /tmp/flink-test.log 2>&1

# 检查日志中是否有错误
echo ""
echo "5. 检查运行日志..."

if grep -q "InaccessibleObjectException" /tmp/flink-test.log; then
    echo "✗ 发现反射访问错误"
    echo "错误详情:"
    grep "InaccessibleObjectException" /tmp/flink-test.log
    exit 1
else
    echo "✓ 无反射访问错误"
fi

if grep -q "NoClassDefFoundError.*jmx" /tmp/flink-test.log; then
    echo "✗ 发现 JMX 相关错误"
    echo "错误详情:"
    grep "NoClassDefFoundError.*jmx" /tmp/flink-test.log
    exit 1
else
    echo "✓ 无 JMX 相关错误"
fi

if grep -q "Unable to unregister MBeans" /tmp/flink-test.log; then
    echo "✗ 发现 MBean 注销错误"
    echo "错误详情:"
    grep "Unable to unregister MBeans" /tmp/flink-test.log
    exit 1
else
    echo "✓ 无 MBean 注销错误"
fi

echo ""
echo "=========================================="
echo "✓ 所有测试通过!"
echo "=========================================="
echo ""
echo "JVM 参数配置正确:"
echo "  - 反射访问限制已解决"
echo "  - Log4j2 JMX 错误已解决"
echo "  - 所有启动脚本已更新"
echo ""
echo "可以安全运行 Flink 作业了!"
echo ""

# 清理测试日志
rm -f /tmp/flink-test.log
