#!/bin/bash

# Kafka 管理脚本
# 用法: ./manage-kafka.sh [start|stop|restart|clean]
# 默认: restart
# clean: 停止并清理所有数据卷（用于解决启动问题）

set -e

ACTION=${1:-restart}

echo "=========================================="
echo "Kafka 管理 - $ACTION"
echo "=========================================="

case "$ACTION" in
    start)
        echo "启动 Kafka 集群..."
        docker-compose -f docker-compose-kafka.yml up -d
        
        echo "等待 Kafka 启动..."
        sleep 15
        
        # 创建 Topic
        echo "创建 crypto_ticker Topic..."
        docker exec kafka kafka-topics \
            --create \
            --topic crypto_ticker \
            --bootstrap-server localhost:9092 \
            --partitions 4 \
            --replication-factor 1 \
            --config retention.ms=604800000 \
            --config segment.ms=3600000 \
            --config compression.type=lz4 \
            --if-not-exists 2>/dev/null || echo "Topic 已存在"
        
        echo "✅ Kafka 启动完成"
        ;;
        
    stop)
        echo "停止 Kafka 集群..."
        docker-compose -f docker-compose-kafka.yml down
        echo "✅ Kafka 已停止"
        ;;
        
    clean)
        echo "停止 Kafka 并清理数据卷..."
        docker-compose -f docker-compose-kafka.yml down -v
        echo "✅ Kafka 已停止，数据卷已清理"
        echo "⚠️  注意：所有 Kafka 数据已删除，下次启动将是全新环境"
        ;;
        
    restart)
        echo "重启 Kafka 集群..."
        bash manage-kafka.sh stop
        sleep 3
        bash manage-kafka.sh start
        ;;
        
    *)
        echo "错误: 未知操作 '$ACTION'"
        echo "用法: $0 [start|stop|restart|clean]"
        echo ""
        echo "操作说明:"
        echo "  start   - 启动 Kafka 和 Zookeeper"
        echo "  stop    - 停止 Kafka 和 Zookeeper"
        echo "  restart - 重启 Kafka 和 Zookeeper"
        echo "  clean   - 停止并清理所有数据（解决启动问题）"
        exit 1
        ;;
esac

echo ""
echo "Kafka 状态:"
docker-compose -f docker-compose-kafka.yml ps
