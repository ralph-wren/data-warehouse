#!/bin/bash

# Doris 和 Flink 连接验证脚本
# 用于快速检查 Doris 部署是否正确,Flink 是否能连接

echo "=========================================="
echo "Doris 和 Flink 连接验证"
echo "=========================================="
echo ""

# 颜色定义
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

# 测试结果统计
PASS=0
FAIL=0

# 测试函数
test_step() {
    local name=$1
    local command=$2
    
    echo -n "测试: $name ... "
    
    if eval "$command" &>/dev/null; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASS++))
        return 0
    else
        echo -e "${RED}✗ 失败${NC}"
        ((FAIL++))
        return 1
    fi
}

# 1. 检查 Docker 容器
echo "1. 检查 Docker 容器状态"
echo "-------------------------------------------"

test_step "Doris FE 容器运行中" "docker ps | grep -q doris-fe"
test_step "Doris BE 容器运行中" "docker ps | grep -q doris-be"

echo ""

# 2. 检查 Doris 端口
echo "2. 检查 Doris 端口"
echo "-------------------------------------------"

test_step "FE MySQL 端口 (9030)" "nc -z 127.0.0.1 9030"
test_step "FE HTTP 端口 (8030)" "nc -z 127.0.0.1 8030"
test_step "BE HTTP 端口 (8040)" "nc -z 127.0.0.1 8040"
test_step "BE Heartbeat 端口 (9050)" "nc -z 127.0.0.1 9050"

echo ""

# 3. 检查 Doris 连接
echo "3. 检查 Doris 连接"
echo "-------------------------------------------"

test_step "FE MySQL 连接" "mysql -h 127.0.0.1 -P 9030 -u root -e 'SELECT 1'"

if mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" &>/dev/null; then
    # 检查 BE 地址
    BE_HOST=$(mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep "Host:" | awk '{print $2}')
    BE_ALIVE=$(mysql -h 127.0.0.1 -P 9030 -u root -e "SHOW BACKENDS\G" 2>/dev/null | grep "Alive:" | awk '{print $2}')
    
    echo -n "测试: BE 地址正确 (127.0.0.1) ... "
    if [[ $BE_HOST == "127.0.0.1" ]]; then
        echo -e "${GREEN}✓ 通过${NC} (地址: $BE_HOST)"
        ((PASS++))
    else
        echo -e "${RED}✗ 失败${NC} (地址: $BE_HOST)"
        echo -e "  ${YELLOW}提示: 运行 ./fix-doris-be-address.sh 修复${NC}"
        ((FAIL++))
    fi
    
    echo -n "测试: BE 节点在线 ... "
    if [[ $BE_ALIVE == "true" ]]; then
        echo -e "${GREEN}✓ 通过${NC}"
        ((PASS++))
    else
        echo -e "${RED}✗ 失败${NC} (状态: $BE_ALIVE)"
        ((FAIL++))
    fi
fi

echo ""

# 4. 检查 BE HTTP API
echo "4. 检查 BE HTTP API"
echo "-------------------------------------------"

test_step "BE Health API" "curl -s http://127.0.0.1:8040/api/health | grep -q 'OK\|status'"

echo ""

# 5. 检查数据库和表
echo "5. 检查数据库和表"
echo "-------------------------------------------"

if mysql -h 127.0.0.1 -P 9030 -u root -e "SELECT 1" &>/dev/null; then
    test_step "数据库 crypto_dw 存在" "mysql -h 127.0.0.1 -P 9030 -u root -e 'USE crypto_dw'"
    test_step "表 ods_crypto_ticker_rt 存在" "mysql -h 127.0.0.1 -P 9030 -u root -e 'DESC crypto_dw.ods_crypto_ticker_rt'"
fi

echo ""

# 6. 检查 Kafka
echo "6. 检查 Kafka (可选)"
echo "-------------------------------------------"

test_step "Kafka 容器运行中" "docker ps | grep -q kafka"
test_step "Kafka 端口 (9092)" "nc -z 127.0.0.1 9092"

echo ""

# 7. 检查配置文件
echo "7. 检查配置文件"
echo "-------------------------------------------"

test_step "application.yml 存在" "test -f config/application.yml"
test_step "Doris FE 配置正确" "grep -q '127.0.0.1:8030' config/application.yml"
test_step "Doris BE 配置正确" "grep -q '127.0.0.1:8040' config/application.yml"

echo ""

# 8. 总结
echo "=========================================="
echo "测试总结"
echo "=========================================="
echo -e "通过: ${GREEN}$PASS${NC}"
echo -e "失败: ${RED}$FAIL${NC}"
echo ""

if [ $FAIL -eq 0 ]; then
    echo -e "${GREEN}✓ 所有测试通过!${NC}"
    echo ""
    echo "下一步:"
    echo "  1. 启动数据采集: ./run-collector.sh"
    echo "  2. 运行 Flink 作业: ./run-flink-ods-datastream.sh"
    echo "  3. 查询数据: mysql -h 127.0.0.1 -P 9030 -u root -e 'SELECT * FROM crypto_dw.ods_crypto_ticker_rt LIMIT 10'"
    exit 0
else
    echo -e "${RED}✗ 有 $FAIL 个测试失败${NC}"
    echo ""
    echo "故障排查:"
    
    if [[ $BE_HOST == 172.* ]]; then
        echo "  1. BE 地址错误,运行: ./fix-doris-be-address.sh"
    fi
    
    if [[ $BE_ALIVE != "true" ]]; then
        echo "  2. BE 节点离线,检查日志: docker logs doris-be"
    fi
    
    if ! docker ps | grep -q doris-fe; then
        echo "  3. Doris 未启动,运行: ./start-doris-for-flink.sh"
    fi
    
    if ! mysql -h 127.0.0.1 -P 9030 -u root -e "USE crypto_dw" &>/dev/null; then
        echo "  4. 数据库未创建,运行: mysql -h 127.0.0.1 -P 9030 -u root < sql/create_tables.sql"
    fi
    
    exit 1
fi
