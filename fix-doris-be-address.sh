#!/bin/bash

# 修复 Doris BE 地址问题
# 将 Docker 内部 IP 替换为 127.0.0.1

echo "=========================================="
echo "Doris BE 地址诊断和修复工具"
echo "=========================================="
echo ""

# 1. 检查当前 BE 节点
echo "1. 检查当前 BE 节点..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" > /tmp/doris_backends.txt

# 提取 BE 信息
BE_HOST=$(grep "Host:" /tmp/doris_backends.txt | awk '{print $2}')
BE_PORT=$(grep "HeartbeatPort:" /tmp/doris_backends.txt | awk '{print $2}')
BE_HTTP_PORT=$(grep "HttpPort:" /tmp/doris_backends.txt | awk '{print $2}')
BE_ALIVE=$(grep "Alive:" /tmp/doris_backends.txt | awk '{print $2}')

echo "  当前 BE 地址: $BE_HOST:$BE_PORT"
echo "  HTTP 端口: $BE_HTTP_PORT"
echo "  状态: $BE_ALIVE"
echo ""

# 2. 判断是否需要修复
if [[ $BE_HOST == 172.* ]]; then
    echo "2. 检测到 Docker 内部 IP ($BE_HOST),需要修复!"
    echo ""
    
    # 3. 删除旧 BE
    echo "3. 删除旧 BE 节点..."
    mysql -h 127.0.0.1 -P 9030 -u root -e "ALTER SYSTEM DROPP BACKEND '${BE_HOST}:${BE_PORT}' FORCE;"
    
    sleep 2
    
    # 4. 添加新 BE (使用 127.0.0.1)
    echo "4. 添加新 BE 节点 (127.0.0.1:9050)..."
    mysql -h 127.0.0.1 -P 9030 -u root -e "ALTER SYSTEM ADD BACKEND '127.0.0.1:9050';"
    
    sleep 5
    
    # 5. 验证
    echo ""
    echo "5. 验证修复结果..."
    mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" | grep -E "Host|HttpPort|Alive"
    
    echo ""
    echo "=========================================="
    echo "修复完成!"
    echo "=========================================="
    echo ""
    echo "请重新运行 Flink 作业:"
    echo "  ./run-flink-ods-datastream.sh"
    
elif [[ $BE_HOST == "127.0.0.1" ]]; then
    echo "2. BE 地址正确 (127.0.0.1),无需修复"
    echo ""
    
    # 检查 BE 是否在线
    if [[ $BE_ALIVE == "true" ]]; then
        echo "✓ BE 节点在线,可以正常使用"
        echo ""
        echo "如果 Flink 仍然报错,请检查:"
        echo "  1. BE HTTP 端口是否可访问: curl http://127.0.0.1:8040/api/health"
        echo "  2. 配置文件中的 BE 地址: cat config/application.yml | grep 'be:' -A 2"
    else
        echo "✗ BE 节点离线,请检查 Docker 容器状态:"
        echo "  docker ps | grep doris"
        echo "  docker logs doris-be"
    fi
else
    echo "2. 未知的 BE 地址: $BE_HOST"
    echo "   请手动检查 Doris 配置"
fi

echo ""
