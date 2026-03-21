#!/bin/bash

# 启动所有服务的脚本

echo "=========================================="
echo "Starting Crypto Data Warehouse Services"
echo "=========================================="
echo ""

# 检查环境变量
if [ ! -f .env ]; then
    echo "Error: .env file not found!"
    echo "Please copy .env.example to .env and configure it."
    exit 1
fi

# 加载环境变量
export $(cat .env | grep -v '^#' | xargs)
export APP_ENV=${APP_ENV:-dev}

echo "Environment: $APP_ENV"
echo ""

# 1. 启动 Kafka（包含 Topic 创建）
echo "Step 1: Starting Kafka..."
bash manage-kafka.sh start
echo ""

# 2. 启动 Doris
echo "Step 2: Starting Doris..."
bash manage-doris.sh start
echo ""

# 3. 检查 Doris 连接
echo "Step 3: Checking Doris connection..."
if mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" > /dev/null 2>&1; then
    echo "Doris connection OK"
else
    echo "Error: Cannot connect to Doris!"
    exit 1
fi
echo ""

# 4. 启动 Flink 集群
echo "Step 4: Starting Flink cluster..."
bash manage-flink.sh start
echo ""

# 5. 启动监控系统
echo "Step 5: Starting monitoring system..."
bash manage-monitoring.sh start
echo ""

# 6. 创建 Doris 表（如果不存在）
echo "Step 6: Creating Doris tables..."
mysql -h 127.0.0.1 -P 9030 -u root crypto_dw < sql/create_tables.sql 2>/dev/null
echo "Doris tables ready"
echo ""

# 7. 编译项目
echo "Step 7: Compiling project..."
mvn clean package -DskipTests -q
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi
echo "Build successful"
echo ""

# 8. 启动 StreamPark（可选，默认不启动）
# 如果需要启动 StreamPark，取消下面的注释
echo "Step 8: Starting StreamPark..."
bash manage-streampark.sh start
echo ""

echo "=========================================="
echo "All services are ready!"
echo "=========================================="
echo ""
echo "You can now start the following jobs:"
echo ""
echo "1. Data Collector (WebSocket -> Kafka):"
echo "   bash run-collector.sh"
echo ""
echo "2. Flink ODS Job (Kafka -> Doris ODS):"
echo "   bash run-flink-ods-sql.sh"
echo "   or"
echo "   bash run-flink-ods-datastream.sh"
echo ""
echo "3. Flink DWD Job (ODS -> DWD):"
echo "   bash run-flink-dwd-sql.sh"
echo ""
echo "4. Flink DWS Job (Kafka -> DWS 1min K-line):"
echo "   bash run-flink-dws-1min-sql.sh"
echo ""
echo "To query data:"
echo "   bash query-doris.sh"
echo ""
echo "Optional services:"
echo "   StreamPark: bash manage-streampark.sh start"
echo "   Access at: http://localhost:10000 (admin/streampark)"
echo ""
