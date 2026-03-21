#!/bin/bash

# 修复 Doris BE 地址,使 Flink SQL 作业可以从宿主机访问
# 问题: BE 注册的是 Docker 内部 IP,宿主机无法访问

echo "=========================================="
echo "修复 Doris BE 地址"
echo "=========================================="
echo ""

# 1. 检查当前 BE 地址
echo "1. 当前 BE 地址:"
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW BACKENDS\G" 2>/dev/null | grep -E "Host|HeartbeatPort|BePort"
echo ""

# 2. 获取当前 BE 的内部 IP
be_internal_ip=$(docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW BACKENDS\G" 2>/dev/null | grep "Host:" | awk '{print $2}')
echo "BE 内部 IP: $be_internal_ip"
echo ""

# 3. 删除旧的 BE
echo "2. 删除旧的 BE..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "ALTER SYSTEM DROP BACKEND '${be_internal_ip}:9050';" 2>/dev/null
sleep 2
echo "   ✅ 已删除"
echo ""

# 4. 添加新的 BE (使用宿主机地址)
echo "3. 添加新的 BE (使用 localhost)..."
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "ALTER SYSTEM ADD BACKEND 'host.docker.internal:9050';" 2>/dev/null
sleep 3
echo "   ✅ 已添加"
echo ""

# 5. 验证新的 BE 地址
echo "4. 验证新的 BE 地址:"
docker exec doris-fe mysql -h127.0.0.1 -P9030 -uroot -e "SHOW BACKENDS\G" 2>/dev/null | grep -E "Host|HeartbeatPort|BePort|Alive"
echo ""

echo "=========================================="
echo "修复完成"
echo "=========================================="
echo ""
echo "说明:"
echo "  - 旧地址: $be_internal_ip (Docker 内部 IP,宿主机无法访问)"
echo "  - 新地址: host.docker.internal (宿主机可以访问)"
echo ""
echo "现在可以重新运行 Flink SQL 作业:"
echo "  bash test/test-flink-ods-sql.sh"
