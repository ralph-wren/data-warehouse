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
