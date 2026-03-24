-- Doris Source 表 DDL 模板
-- 功能：创建 Doris Source 表，用于从 Doris 读取数据（ArrowFlightSQL 高性能模式）
-- 
-- 参数说明：
-- ${tableName} - 表名
-- ${schema} - 字段定义
-- ${feNodes} - FE 节点地址
-- ${tableIdentifier} - 表标识符（database.table）
-- ${username} - 用户名
-- ${password} - 密码
-- ${readFields} - 读取字段列表

CREATE TABLE ${tableName} (
${schema}
) WITH (
    'connector' = 'doris',
    'fenodes' = '${feNodes}',
    'table.identifier' = '${tableIdentifier}',
    'username' = '${username}',
    'password' = '${password}',
    -- ArrowFlightSQL 配置 - 高性能读取模式
    'doris.deserialize.arrow.async' = 'true',
    'doris.deserialize.queue.size' = '64',
    'doris.request.query.timeout.s' = '3600',
    'doris.read.field' = '${readFields}'
)
