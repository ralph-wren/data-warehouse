CREATE TABLE ${tableName} (
${schema}${watermark}
) WITH (
    'connector' = 'kafka',
    'topic' = '${topic}',
    'properties.bootstrap.servers' = '${bootstrapServers}',
    'properties.group.id' = '${groupId}',
    'scan.startup.mode' = '${startupMode}',
    'format' = 'json',
    'json.fail-on-missing-field' = 'false',
    'json.ignore-parse-errors' = 'true'
)
