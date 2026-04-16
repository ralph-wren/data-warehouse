#!/usr/bin/env bash
#
# 从 Kafka topic 消费，只输出 value 中包含指定子串（或正则）的消息，默认按行输出。
# 依赖（二选一）：kcat（推荐，brew install kcat）或 Kafka 发行版中的 kafka-console-consumer。
#
# Bootstrap 不设代码内默认值：请用 -b 或环境变量 KAFKA_BOOTSTRAP_SERVERS / BOOTSTRAP_SERVERS。
#
# 示例：
#   export KAFKA_BOOTSTRAP_SERVERS=localhost:9093
#   ./scripts/kafka-filter-consume.sh -t qdc.binance.um.klines --from-beginning -c 1 BTCUSDT
#   ./scripts/kafka-filter-consume.sh -t qdc.binance.um.klines --from-beginning -c 1 --all BTCUSDT 1m
#   ./scripts/kafka-filter-consume.sh -t my.topic -e 'foo|bar' --regex
#
set -euo pipefail

TOPIC=""
BOOTSTRAP="${KAFKA_BOOTSTRAP_SERVERS:-${BOOTSTRAP_SERVERS:-}}"
FROM_BEGINNING=0
MAX_MSGS=""
CONSUMER_GROUP=""
USE_REGEX=0
MATCH_ALL=0
VERBOSE=0
PATTERNS=()
EPHEMERAL_GROUP=""
__EPHEMERAL_CLEANED=0

usage() {
  cat <<'EOF'
用法:
  kafka-filter-consume.sh -t <topic> [-b <bootstrap>] [选项] <子串或正则> [更多 ...]

选项:
  -t, --topic <name>        必填，topic
  -b, --bootstrap <hosts>   bootstrap；可改用环境变量 KAFKA_BOOTSTRAP_SERVERS
  --from-beginning          每次运行都从最早 offset 读（临时消费组 + earliest；退出时尝试 kafka-consumer-groups --delete）
  -c, --max-messages <n>    最多拉取 n 条 Kafka 消息后退出（过滤后输出行数 ≤ n）
  -g, --group <id>          仅在不加 --from-beginning 时生效；与 --from-beginning 同用时只作临时组名前缀（仍每次唯一）
  --all                     多个模式为 AND（默认任意命中即 OR）
  -e, --regexp <pattern>    追加一条模式（可多次）；与位置参数一起参与匹配
  --regex                   所有模式按 grep 扩展正则处理（默认固定子串）
  -v, --verbose             打印实际调用的消费命令
  -h, --help                帮助

说明:
  - 默认子串为「包含即命中」（固定字符串，非整行匹配）。
  - 需要 SASL/SSL 时用 kcat 的 -X key=val（可自行改脚本拼接）。
  - kcat 使用 -G 时，选项须全部在「-G 组名 topic」之前，脚本已按此拼装。
  - --from-beginning 结束时尝试删除临时消费组（需 kafka-consumer-groups 在 PATH 或 KAFKA_HOME/bin）。
EOF
}

logv() {
  if [[ "$VERBOSE" -eq 1 ]]; then
    printf '[kafka-filter-consume] %s\n' "$*" >&2
  fi
}

# --from-beginning：每次新组 + earliest，避免沿用已提交位移；可选前缀便于在集群里辨认来源
unique_beginning_group() {
  local prefix="${1:-qdc-filter}"
  printf '%s-scan-%s-%s%05d\n' "$prefix" "$$" "$(date +%s)" "${RANDOM}"
}

detect_consumer() {
  if command -v kcat >/dev/null 2>&1; then
    echo "kcat"
    return
  fi
  if command -v kafkacat >/dev/null 2>&1; then
    echo "kafkacat"
    return
  fi
  if [[ -n "${KAFKA_HOME:-}" && -x "${KAFKA_HOME}/bin/kafka-console-consumer.sh" ]]; then
    echo "${KAFKA_HOME}/bin/kafka-console-consumer.sh"
    return
  fi
  if command -v kafka-console-consumer.sh >/dev/null 2>&1; then
    echo "kafka-console-consumer.sh"
    return
  fi
  echo ""
}

detect_kafka_consumer_groups() {
  if [[ -n "${KAFKA_HOME:-}" && -x "${KAFKA_HOME}/bin/kafka-consumer-groups.sh" ]]; then
    echo "${KAFKA_HOME}/bin/kafka-consumer-groups.sh"
    return
  fi
  if command -v kafka-consumer-groups.sh >/dev/null 2>&1; then
    echo "kafka-consumer-groups.sh"
    return
  fi
  if command -v kafka-consumer-groups >/dev/null 2>&1; then
    echo "kafka-consumer-groups"
    return
  fi
  echo ""
}

# 删除 --from-beginning 生成的临时组；依赖 kafka-consumer-groups（无则仅 verbose 提示）
cleanup_ephemeral_consumer_group() {
  [[ "$__EPHEMERAL_CLEANED" -eq 1 ]] && return 0
  local g="${EPHEMERAL_GROUP:-}"
  [[ -z "$g" ]] && return 0
  __EPHEMERAL_CLEANED=1

  local bs="${BOOTSTRAP:-}"
  [[ -z "$bs" ]] && return 0

  local kg
  kg="$(detect_kafka_consumer_groups)"
  if [[ -z "$kg" ]]; then
    printf '[kafka-filter-consume] 未找到 kafka-consumer-groups（PATH 或 KAFKA_HOME），临时组未删除: %s\n' "$g" >&2
    logv "可安装 Kafka 客户端或设置 KAFKA_HOME 后重试"
    return 0
  fi

  # 等待协调器释放本进程 consumer，避免 Deletion of some consumer groups failed
  sleep 0.5 2>/dev/null || sleep 1

  local out ec
  out="$("$kg" --bootstrap-server "$bs" --delete --group "$g" 2>&1)" && ec=0 || ec=$?
  if [[ "$ec" -eq 0 ]]; then
    logv "已删除临时消费组: $g"
  else
    logv "删除临时消费组失败（可忽略）: $g — $out"
  fi
}

# stdin -> stdout：按行过滤
filter_stream() {
  local use_fixed="$1"
  local match_all="$2"
  shift 2
  local -a pats=("$@")

  if [[ ${#pats[@]} -eq 0 ]]; then
    cat
    return
  fi

  if [[ "$match_all" -eq 0 ]]; then
    # OR：任一命中
    if [[ "$use_fixed" -eq 1 ]]; then
      while IFS= read -r line || [[ -n "${line:-}" ]]; do
        for p in "${pats[@]}"; do
          if [[ "$line" == *"$p"* ]]; then
            printf '%s\n' "$line"
            break
          fi
        done
      done
    else
      local ere=""
      local first=1
      local p
      for p in "${pats[@]}"; do
        if [[ "$first" -eq 1 ]]; then
          ere="$p"
          first=0
        else
          ere="$ere|$p"
        fi
      done
      while IFS= read -r line || [[ -n "${line:-}" ]]; do
        if printf '%s\n' "$line" | grep -q -E "$ere" 2>/dev/null; then
          printf '%s\n' "$line"
        fi
      done
    fi
  else
    # AND：全部命中
    if [[ "$use_fixed" -eq 1 ]]; then
      while IFS= read -r line || [[ -n "${line:-}" ]]; do
        local ok=1
        for p in "${pats[@]}"; do
          if [[ "$line" != *"$p"* ]]; then
            ok=0
            break
          fi
        done
        [[ "$ok" -eq 1 ]] && printf '%s\n' "$line"
      done
    else
      while IFS= read -r line || [[ -n "${line:-}" ]]; do
        local ok=1
        for p in "${pats[@]}"; do
          if ! printf '%s\n' "$line" | grep -q -E "$p" 2>/dev/null; then
            ok=0
            break
          fi
        done
        [[ "$ok" -eq 1 ]] && printf '%s\n' "$line"
      done
    fi
  fi
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -t|--topic)
      TOPIC="${2:?}"
      shift 2
      ;;
    -b|--bootstrap)
      BOOTSTRAP="${2:?}"
      shift 2
      ;;
    --from-beginning)
      FROM_BEGINNING=1
      shift
      ;;
    -c|--max-messages)
      MAX_MSGS="${2:?}"
      shift 2
      ;;
    -g|--group)
      CONSUMER_GROUP="${2:?}"
      shift 2
      ;;
    --all)
      MATCH_ALL=1
      shift
      ;;
    -e|--regexp)
      PATTERNS+=("${2:?}")
      shift 2
      ;;
    --regex)
      USE_REGEX=1
      shift
      ;;
    -v|--verbose)
      VERBOSE=1
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    --)
      shift
      break
      ;;
    -*)
      echo "未知参数: $1" >&2
      usage >&2
      exit 1
      ;;
    *)
      PATTERNS+=("$1")
      shift
      ;;
  esac
done

while [[ $# -gt 0 ]]; do
  PATTERNS+=("$1")
  shift
done

if [[ -z "$TOPIC" ]]; then
  echo "错误: 必须指定 -t <topic>" >&2
  exit 1
fi
if [[ -z "$BOOTSTRAP" ]]; then
  echo "错误: 未设置 bootstrap，请使用 -b 或环境变量 KAFKA_BOOTSTRAP_SERVERS / BOOTSTRAP_SERVERS" >&2
  exit 1
fi
if [[ ${#PATTERNS[@]} -eq 0 ]]; then
  echo "错误: 请至少提供一个子串或 -e 模式" >&2
  exit 1
fi

BACKEND="$(detect_consumer)"
if [[ -z "$BACKEND" ]]; then
  echo "错误: 未找到 kcat / kafkacat / kafka-console-consumer.sh（可 brew install kcat 或设置 KAFKA_HOME）" >&2
  exit 1
fi

USE_FIXED=1
if [[ "$USE_REGEX" -eq 1 ]]; then
  USE_FIXED=0
fi

if [[ "$FROM_BEGINNING" -eq 1 ]]; then
  EPHEMERAL_GROUP="$(unique_beginning_group "${CONSUMER_GROUP:-qdc-filter}")"
  logv "from-beginning: ephemeral consumer group=$EPHEMERAL_GROUP"
fi

trap cleanup_ephemeral_consumer_group EXIT
trap 'cleanup_ephemeral_consumer_group; exit 130' INT
trap 'cleanup_ephemeral_consumer_group; exit 143' TERM

if [[ "$BACKEND" == "kcat" || "$BACKEND" == "kafkacat" ]]; then
  KC="$BACKEND"
  # kcat 在 -G <group> <topic> 之后会把后续参数都当作 topic 名，故 -q/-c/-X/-o 等必须全部放在 -G 之前
  if [[ "$FROM_BEGINNING" -eq 1 ]]; then
    kcat_args=(-b "$BOOTSTRAP" -q -X auto.offset.reset=earliest -o beginning)
    if [[ -n "$MAX_MSGS" ]]; then
      kcat_args+=(-c "$MAX_MSGS")
    fi
    kcat_args+=(-G "$EPHEMERAL_GROUP" "$TOPIC")
  elif [[ -n "$CONSUMER_GROUP" ]]; then
    kcat_args=(-b "$BOOTSTRAP" -q)
    if [[ -n "$MAX_MSGS" ]]; then
      kcat_args+=(-c "$MAX_MSGS")
    fi
    kcat_args+=(-G "$CONSUMER_GROUP" "$TOPIC")
  else
    kcat_args=(-b "$BOOTSTRAP" -t "$TOPIC" -C -q)
    if [[ -n "$MAX_MSGS" ]]; then
      kcat_args+=(-c "$MAX_MSGS")
    fi
  fi
  logv "$KC ${kcat_args[*]}"
  # shellcheck disable=SC2068
  "$KC" ${kcat_args[@]} | filter_stream "$USE_FIXED" "$MATCH_ALL" "${PATTERNS[@]}"
else
  cc_args=("--bootstrap-server" "$BOOTSTRAP" "--topic" "$TOPIC" \
    "--property" "print.timestamp=false" \
    "--property" "print.key=false" \
    "--property" "print.headers=false")
  if [[ "$FROM_BEGINNING" -eq 1 ]]; then
    cc_args+=(--from-beginning --group "$EPHEMERAL_GROUP" --consumer-property auto.offset.reset=earliest)
  else
    if [[ -n "$CONSUMER_GROUP" ]]; then
      cc_args+=(--group "$CONSUMER_GROUP")
    fi
  fi
  if [[ -n "$MAX_MSGS" ]]; then
    cc_args+=(--max-messages "$MAX_MSGS")
  fi
  logv "$BACKEND ${cc_args[*]}"
  "$BACKEND" "${cc_args[@]}" | filter_stream "$USE_FIXED" "$MATCH_ALL" "${PATTERNS[@]}"
fi
