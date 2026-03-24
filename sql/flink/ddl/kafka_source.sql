-- Kafka Source 表 DDL 模板
-- 功能：创建 Kafka Source 表，用于从 Kafka 读取数据
-- 
-- 参数说明：
-- ${tableName} - 表名
-- ${schema} - 字段定义
-- ${watermark} - Watermark 定义（可选）
-- ${bootstrapServers} - Kafka 服务器地址
-- ${topic} - Kafka Topic
-- ${groupId} - Consumer Group ID
-- ${startupMode} - 启动模式（earliest-offset/latest-offset/group-offsets）

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
