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
