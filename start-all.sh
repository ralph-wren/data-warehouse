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

# 1. 启动 Kafka（如果未运行）
echo "Step 1: Checking Kafka..."
if ! docker ps | grep -q kafka; then
    echo "Starting Kafka..."
    docker-compose up -d
    echo "Waiting for Kafka to be ready..."
    sleep 10
else
    echo "Kafka is already running"
fi
echo ""

# 2. 创建 Kafka Topic（如果不存在）
echo "Step 2: Creating Kafka Topic..."
bash kafka-setup.sh
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

# 4. 创建 Doris 表（如果不存在）
echo "Step 4: Creating Doris tables..."
mysql -h 127.0.0.1 -P 9030 -u root crypto_dw < sql/create_tables.sql 2>/dev/null
echo "Doris tables ready"
echo ""

# 5. 编译项目
echo "Step 5: Compiling project..."
mvn clean package -DskipTests -q
if [ $? -ne 0 ]; then
    echo "Build failed!"
    exit 1
fi
echo "Build successful"
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
