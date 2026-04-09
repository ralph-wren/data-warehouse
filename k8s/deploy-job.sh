#!/bin/bash

################################################################################
# Flink Job 通用部署脚本
# 
# 使用方法:
#   ./deploy-job.sh deploy <job-name>     # 部署任务
#   ./deploy-job.sh delete <job-name>     # 删除任务
#   ./deploy-job.sh restart <job-name>    # 重启任务
#   ./deploy-job.sh status <job-name>     # 查看状态
#   ./deploy-job.sh logs <job-name>       # 查看日志
#   ./deploy-job.sh list                  # 列出所有任务
# 
# 示例:
#   ./deploy-job.sh deploy ticker-collector
#   ./deploy-job.sh status ticker-collector
################################################################################

set -e

# 颜色输出
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

NAMESPACE="flink"
K8S_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
JOBS_DIR="${K8S_DIR}/jobs"
TEMPLATE_FILE="${K8S_DIR}/flink-job-template.yaml"

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; }

# 加载任务配置
load_job_config() {
    local job_name=$1
    local config_file="${JOBS_DIR}/${job_name}.yaml"
    
    if [ ! -f "$config_file" ]; then
        log_error "任务配置文件不存在: ${config_file}"
        log_info "可用的任务配置:"
        ls -1 "${JOBS_DIR}"/*.yaml 2>/dev/null | xargs -n1 basename | sed 's/.yaml$//' || echo "  (无)"
        exit 1
    fi
    
    log_info "加载任务配置: ${config_file}"
    
    # 读取配置文件并导出为环境变量
    while IFS= read -r line; do
        # 跳过注释和空行
        [[ $line =~ ^#.*$ ]] && continue
        [[ -z $line ]] && continue
        
        # 解析 key: value 格式
        if [[ $line =~ ^([A-Z_]+):\ *(.+)$ ]]; then
            key="${BASH_REMATCH[1]}"
            value="${BASH_REMATCH[2]}"
            
            # 去除前后空格和引号
            value=$(echo "$value" | xargs | sed 's/^["'\'']\|["'\'']$//g')
            
            # 导出环境变量
            export "$key"="$value"
            
            # 调试输出（可选）
            # echo "  $key = $value"
        fi
    done < "$config_file"
    
    log_success "配置加载完成"
    
    # 验证必需的变量
    if [ -z "$JOB_NAME" ] || [ -z "$JOB_CLASS" ]; then
        log_error "配置文件缺少必需的字段: JOB_NAME 或 JOB_CLASS"
        exit 1
    fi
}

# 替换模板变量
render_template() {
    local template=$1
    
    # 使用 envsubst 替换变量（如果可用）
    if command -v envsubst &> /dev/null; then
        echo "$template" | envsubst
    else
        # 手动替换变量
        echo "$template" | sed \
            -e "s/\${JOB_NAME}/${JOB_NAME}/g" \
            -e "s/\${JOB_CLASS}/${JOB_CLASS}/g" \
            -e "s/\${JM_MEMORY}/${JM_MEMORY}/g" \
            -e "s/\${JM_MEMORY_REQUEST}/${JM_MEMORY_REQUEST}/g" \
            -e "s/\${JM_MEMORY_LIMIT}/${JM_MEMORY_LIMIT}/g" \
            -e "s/\${JM_CPU_REQUEST}/${JM_CPU_REQUEST}/g" \
            -e "s/\${JM_CPU_LIMIT}/${JM_CPU_LIMIT}/g" \
            -e "s/\${TM_MEMORY}/${TM_MEMORY}/g" \
            -e "s/\${TM_MEMORY_REQUEST}/${TM_MEMORY_REQUEST}/g" \
            -e "s/\${TM_MEMORY_LIMIT}/${TM_MEMORY_LIMIT}/g" \
            -e "s/\${TM_CPU_REQUEST}/${TM_CPU_REQUEST}/g" \
            -e "s/\${TM_CPU_LIMIT}/${TM_CPU_LIMIT}/g" \
            -e "s/\${TM_REPLICAS}/${TM_REPLICAS}/g" \
            -e "s/\${TM_SLOTS}/${TM_SLOTS}/g" \
            -e "s/\${PARALLELISM}/${PARALLELISM}/g" \
            -e "s/\${CHECKPOINT_INTERVAL}/${CHECKPOINT_INTERVAL}/g" \
            -e "s|\${DOCKER_REGISTRY}|${DOCKER_REGISTRY}|g" \
            -e "s/\${VERSION}/${VERSION}/g"
    fi
}

# 部署任务
deploy_job() {
    local job_name=$1
    
    log_info "开始部署任务: ${job_name}"
    
    # 加载配置
    load_job_config "$job_name"
    
    # 渲染模板
    local rendered=$(render_template "$(cat ${TEMPLATE_FILE})")
    
    # 应用到 K8s
    echo "$rendered" | kubectl apply -f -
    
    log_success "任务部署成功: ${job_name}"
    log_info "等待 Pod 启动..."
    sleep 3
    show_status "$job_name"
}

# 删除任务
delete_job() {
    local job_name=$1
    
    log_warn "开始删除任务: ${job_name}"
    
    read -p "确认删除任务 ${job_name} 吗? (y/N): " confirm
    if [ "$confirm" != "y" ] && [ "$confirm" != "Y" ]; then
        log_info "取消删除"
        exit 0
    fi
    
    kubectl delete deployment flink-${job_name}-jobmanager -n ${NAMESPACE} --ignore-not-found=true
    kubectl delete deployment flink-${job_name}-taskmanager -n ${NAMESPACE} --ignore-not-found=true
    kubectl delete service flink-${job_name}-jobmanager -n ${NAMESPACE} --ignore-not-found=true
    kubectl delete configmap flink-${job_name}-config -n ${NAMESPACE} --ignore-not-found=true
    
    log_success "任务删除完成: ${job_name}"
}

# 重启任务
restart_job() {
    local job_name=$1
    
    log_info "重启任务: ${job_name}"
    
    kubectl rollout restart deployment/flink-${job_name}-jobmanager -n ${NAMESPACE}
    kubectl rollout restart deployment/flink-${job_name}-taskmanager -n ${NAMESPACE}
    
    log_success "重启命令已发送"
    
    kubectl rollout status deployment/flink-${job_name}-jobmanager -n ${NAMESPACE}
    kubectl rollout status deployment/flink-${job_name}-taskmanager -n ${NAMESPACE}
    
    log_success "任务重启完成"
}

# 查看状态
show_status() {
    local job_name=$1
    
    log_info "查看任务状态: ${job_name}"
    echo ""
    
    echo "=== Pods ==="
    kubectl get pods -n ${NAMESPACE} -l app=flink-${job_name}
    echo ""
    
    echo "=== Services ==="
    kubectl get svc -n ${NAMESPACE} -l app=flink-${job_name}
    echo ""
}

# 查看日志
show_logs() {
    local job_name=$1
    
    log_info "查看任务日志: ${job_name}"
    
    local pod=$(kubectl get pods -n ${NAMESPACE} -l app=flink-${job_name},component=jobmanager -o jsonpath='{.items[0].metadata.name}')
    
    if [ -z "$pod" ]; then
        log_error "未找到 JobManager Pod"
        exit 1
    fi
    
    log_info "JobManager Pod: ${pod}"
    kubectl logs -f ${pod} -n ${NAMESPACE}
}

# 列出所有任务
list_jobs() {
    log_info "可用的任务配置:"
    echo ""
    
    for config in "${JOBS_DIR}"/*.yaml; do
        if [ -f "$config" ]; then
            local job_name=$(basename "$config" .yaml)
            local job_class=$(grep "JOB_CLASS:" "$config" | cut -d: -f2 | xargs)
            echo "  📦 ${job_name}"
            echo "     类: ${job_class}"
            echo ""
        fi
    done
    
    echo ""
    log_info "已部署的任务:"
    echo ""
    kubectl get deployments -n ${NAMESPACE} -l component=jobmanager -o custom-columns=NAME:.metadata.name,READY:.status.readyReplicas,AVAILABLE:.status.availableReplicas,AGE:.metadata.creationTimestamp
}

# 扩缩容
scale_job() {
    local job_name=$1
    local replicas=$2
    
    if [ -z "$replicas" ]; then
        log_error "请指定副本数"
        exit 1
    fi
    
    log_info "扩缩容任务: ${job_name} -> ${replicas} 副本"
    
    kubectl scale deployment flink-${job_name}-taskmanager --replicas=${replicas} -n ${NAMESPACE}
    
    log_success "扩缩容完成"
}

# 主函数
main() {
    local action=$1
    local job_name=$2
    
    case "$action" in
        deploy)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                list_jobs
                exit 1
            fi
            deploy_job "$job_name"
            ;;
        delete)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                exit 1
            fi
            delete_job "$job_name"
            ;;
        restart)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                exit 1
            fi
            restart_job "$job_name"
            ;;
        status)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                exit 1
            fi
            show_status "$job_name"
            ;;
        logs)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                exit 1
            fi
            show_logs "$job_name"
            ;;
        scale)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                exit 1
            fi
            scale_job "$job_name" "$3"
            ;;
        list)
            list_jobs
            ;;
        test)
            if [ -z "$job_name" ]; then
                log_error "请指定任务名称"
                exit 1
            fi
            # 测试配置加载
            load_job_config "$job_name"
            echo ""
            log_info "配置变量:"
            echo "  JOB_NAME: $JOB_NAME"
            echo "  JOB_CLASS: $JOB_CLASS"
            echo "  JM_MEMORY: $JM_MEMORY"
            echo "  TM_MEMORY: $TM_MEMORY"
            echo "  TM_REPLICAS: $TM_REPLICAS"
            echo "  DOCKER_REGISTRY: $DOCKER_REGISTRY"
            echo "  VERSION: $VERSION"
            ;;
        *)
            echo "使用方法: $0 {deploy|delete|restart|status|logs|scale|list|test} <job-name> [replicas]"
            echo ""
            echo "命令说明:"
            echo "  deploy <job-name>           - 部署任务"
            echo "  delete <job-name>           - 删除任务"
            echo "  restart <job-name>          - 重启任务"
            echo "  status <job-name>           - 查看任务状态"
            echo "  logs <job-name>             - 查看任务日志"
            echo "  scale <job-name> <replicas> - 扩缩容 TaskManager"
            echo "  list                        - 列出所有任务"
            echo "  test <job-name>             - 测试配置加载"
            echo ""
            echo "示例:"
            echo "  $0 deploy ticker-collector"
            echo "  $0 status ticker-collector"
            echo "  $0 scale ticker-collector 3"
            echo "  $0 list"
            exit 1
            ;;
    esac
}

main "$@"
