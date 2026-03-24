-- Doris Sink 表 DDL 模板
-- 功能：创建 Doris Sink 表，用于向 Doris 写入数据
-- 
-- 参数说明：
-- ${tableName} - 表名
-- ${schema} - 字段定义
-- ${feNodes} - FE 节点地址
-- ${beNodes} - BE 节点地址
-- ${tableIdentifier} - 表标识符（database.table）
-- ${username} - 用户名
-- ${password} - 密码
-- ${labelPrefix} - Label 前缀（用于去重）

CREATE TABLE ${tableName} (
${schema}
) WITH (
    'connector' = 'doris',
    'fenodes' = '${feNodes}',
    'benodes' = '${beNodes}',
    'table.identifier' = '${tableIdentifier}',
    'username' = '${username}',
    'password' = '${password}',
    'sink.label-prefix' = '${labelPrefix}',
    'sink.properties.format' = 'json',
    'sink.properties.read_json_by_line' = 'true',
    'sink.enable-2pc' = 'true'
)
