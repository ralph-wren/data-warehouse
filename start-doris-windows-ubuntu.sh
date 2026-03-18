#!/bin/bash

# Default version
DORIS_QUICK_START_VERSION="4.0.1"

# Parse parameters
while getopts "v:" opt; do
  case $opt in
    v) DORIS_QUICK_START_VERSION="$OPTARG"
    ;;
    \?) echo "Invalid option: -$OPTARG" >&2
    exit 1
    ;;
  esac
done

# Check system type
OS_TYPE=$(uname -s)
if [[ "$OS_TYPE" != "Linux" && "$OS_TYPE" != "Darwin" ]]; then
  echo "Error: Unsupported operating system [$OS_TYPE], only Linux and Mac are supported"
  exit 1
fi

# Check Docker environment
if ! command -v docker &> /dev/null; then
  echo "Error: Docker environment not detected, please install Docker first"
  exit 1
fi

# Check docker-compose
COMPOSE_CMD=""
if command -v docker-compose &> /dev/null; then
  COMPOSE_CMD="docker-compose"
elif docker compose version &> /dev/null; then
  COMPOSE_CMD="docker compose"
else
  echo "Error: docker-compose plugin or docker-compose command is required"
  exit 1
fi

# Create a custom network to ensure containers can communicate
docker network create doris-network 2>/dev/null || true

# Create docker-compose configuration
cat > docker-compose-doris-fixed.yaml <<EOF
version: "3.8"
services:
  fe:
    image: apache/doris:fe-${DORIS_QUICK_START_VERSION}
    container_name: doris_fe
    ports:
      - "8030:8030"
      - "9030:9030"
      - "9010:9010"
    environment:
      - FE_SERVERS=fe1:172.19.0.2:9010
      - FE_ID=1
      - PRIORITY_NETWORKS=172.19.0.0/16
    networks:
      doris-network:
        ipv4_address: 172.19.0.2

  be:
    image: apache/doris:be-${DORIS_QUICK_START_VERSION}
    container_name: doris-be
    ports:
      - "8040:8040"
      - "9050:9050"
    environment:
      - FE_SERVERS=fe1:172.19.0.2:9010
      - BE_ADDR=172.19.0.3:9050
      - PRIORITY_NETWORKS=172.19.0.0/16
    depends_on:
      - fe
    networks:
      doris-network:
        ipv4_address: 172.19.0.3

networks:
  doris-network:
    external: true
    name: doris-network
EOF

# Start services
echo "Starting Doris cluster with version: ${DORIS_QUICK_START_VERSION}"
if ! $COMPOSE_CMD -f docker-compose-doris-fixed.yaml up -d; then
  echo "Error: Failed to start Doris cluster"
  exit 1
fi

echo "Doris cluster started successfully!"
echo ""
echo "Management commands:"
echo "  Stop cluster: $COMPOSE_CMD -f docker-compose-doris-fixed.yaml down"
echo "  View logs: $COMPOSE_CMD -f docker-compose-doris-fixed.yaml logs -f"
echo "  View container status: docker ps | grep doris"
echo ""
echo "Connection information:"
echo "  MySQL connection: mysql -uroot -P9030 -h127.0.0.1"
echo "  FE Web UI: http://127.0.0.1:8030"
echo "  BE Web UI: http://127.0.0.1:8040"
echo ""
echo "Note: It may take 30-60 seconds for Doris to fully start up."
echo "You can check the logs with: $COMPOSE_CMD -f docker-compose-doris-fixed.yaml logs -f"
