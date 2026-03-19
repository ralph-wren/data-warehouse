#!/bin/bash

# 重启 Doris 使用 host 网络模式
# 解决 BE 地址问题

echo "=========================================="
echo "重启 Doris (使用 host 网络模式)"
echo "=========================================="
echo ""

# 1. 停止旧容器
echo "1. 停止旧容器..."
docker stop doris-fe doris-be 2>/dev/null
docker rm doris-fe doris-be 2>/dev/null

# 2. 使用 host 网络模式启动
echo "2. 启动 Doris FE..."
docker run -d \
  --name doris-fe \
  --network host \
  -v doris-fe-data:/opt/apache-doris/fe/doris-meta \
  -v doris-fe-log:/opt/apache-doris/fe/log \
  apache/doris:fe-3.1.0

echo "等待 FE 启动..."
sleep 20

echo "3. 启动 Doris BE..."
docker run -d \
  --name doris-be \
  --network host \
  -e FE_SERVERS="127.0.0.1:9010" \
  -e BE_ADDR="127.0.0.1:9050" \
  -v doris-be-data:/opt/apache-doris/be/storage \
  -v doris-be-log:/opt/apache-doris/be/log \
  apache/doris:be-3.1.0

echo "等待 BE 启动..."
sleep 20

# 4. 检查状态
echo ""
echo "4. 检查 Doris 状态..."
mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" | grep -E "Host|HttpPort|Alive"

echo ""
echo "=========================================="
echo "Doris 重启完成!"
echo "=========================================="
echo ""
echo "如果 BE 仍然显示 172.19.0.x,请手动添加:"
echo "  mysql -h 127.0.0.1 -P 9030 -u root"
echo "  ALTER SYSTEM ADD BACKEND '127.0.0.1:9050';"
echo "  ALTER SYSTEM DROPP BACKEND '172.19.0.x:9050' FORCE;"
