package com.crypto.dw.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 配置加载器
 * 支持从 YAML 文件加载配置，并解析环境变量引用
 ***
 * @Description:
 * @Param:
 * @return:
 * @Author: Gang
 * @Date: 2026/3/21
 */
@Slf4j
public class ConfigLoader {
    
    private static final Pattern ENV_VAR_PATTERN = Pattern.compile("\\$\\{([^}]+)\\}");
    private static ConfigLoader instance;
    private Map<String, Object> config;
    
    private ConfigLoader() {
        loadConfig();
    }
    
    public static synchronized ConfigLoader getInstance() {
        if (instance == null) {
            instance = new ConfigLoader();
        }
        return instance;
    }
    
    /**
     * 加载配置文件
     */
    private void loadConfig() {
        String env = System.getenv("APP_ENV");
        if (env == null || env.isEmpty()) {
            env = "dev";
        }
        
        log.info("Loading configuration for environment: " + env);
        
        // 加载基础配置
        Map<String, Object> baseConfig = loadYamlFile("application.yml");
        if (baseConfig.isEmpty()) {
            log.error("ERROR: Failed to load base configuration file: application.yml");
            log.error("Please ensure the file exists in src/main/resources/config/");
            // 不要调用 System.exit()，而是使用空配置继续
        }
        
        // 加载环境特定配置
        String envConfigFile = "application-" + env + ".yml";
        Map<String, Object> envConfig = loadYamlFile(envConfigFile);
        if (envConfig.isEmpty()) {
            log.info("Warning: Environment-specific config file not found: " + envConfigFile);
            log.info("Using base configuration only");
        }
        
        // 合并配置
        config = mergeConfig(baseConfig, envConfig);
        
        // 解析环境变量
        config = resolveEnvVars(config);
        
        log.info("Configuration loaded successfully");
        log.info("Total config keys: " + countKeys(config));
    }
    
    /**
     * 加载 YAML 文件
     */
    private Map<String, Object> loadYamlFile(String filename) {
        try {
            Yaml yaml = new Yaml();
            InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("config/" + filename);
            
            if (inputStream == null) {
                log.info("Warning: Config file not found: " + filename);
                return new HashMap<>();
            }
            
            Map<String, Object> data = yaml.load(inputStream);
            return data != null ? data : new HashMap<>();
        } catch (Exception e) {
            log.error("Error loading config file: " + filename);
            e.printStackTrace();
            return new HashMap<>();
        }
    }
    
    /**
     * 合并配置（envConfig 覆盖 baseConfig）
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> mergeConfig(Map<String, Object> base, Map<String, Object> override) {
        Map<String, Object> result = new HashMap<>(base);
        
        for (Map.Entry<String, Object> entry : override.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map && result.get(key) instanceof Map) {
                // 递归合并嵌套 Map
                result.put(key, mergeConfig(
                    (Map<String, Object>) result.get(key),
                    (Map<String, Object>) value
                ));
            } else {
                result.put(key, value);
            }
        }
        
        return result;
    }
    
    /**
     * 解析环境变量引用
     */
    @SuppressWarnings("unchecked")
    private Map<String, Object> resolveEnvVars(Map<String, Object> config) {
        Map<String, Object> result = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : config.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof String) {
                result.put(key, resolveEnvVar((String) value));
            } else if (value instanceof Map) {
                result.put(key, resolveEnvVars((Map<String, Object>) value));
            } else {
                result.put(key, value);
            }
        }
        
        return result;
    }
    
    /**
     * 解析单个环境变量引用
     */
    private String resolveEnvVar(String value) {
        Matcher matcher = ENV_VAR_PATTERN.matcher(value);
        StringBuffer result = new StringBuffer();
        
        while (matcher.find()) {
            String envVarName = matcher.group(1);
            String envVarValue = System.getenv(envVarName);
            
            if (envVarValue == null) {
                // 对于可选的环境变量（如 OKX API 密钥），使用空字符串
                // 对于必需的环境变量，应该在使用时检查
                log.info("Info: Environment variable not set: " + envVarName + " (using empty string)");
                envVarValue = "";
            }
            
            matcher.appendReplacement(result, Matcher.quoteReplacement(envVarValue));
        }
        
        matcher.appendTail(result);
        return result.toString();
    }
    
    /**
     * 计算配置项数量（用于调试）
     */
    @SuppressWarnings("unchecked")
    private int countKeys(Map<String, Object> map) {
        int count = 0;
        for (Object value : map.values()) {
            if (value instanceof Map) {
                count += countKeys((Map<String, Object>) value);
            } else {
                count++;
            }
        }
        return count;
    }
    
    /**
     * 获取配置值
     */
    public Object get(String path) {
        return getNestedValue(config, path.split("\\."));
    }
    
    /**
     * 获取字符串配置值
     */
    public String getString(String path) {
        Object value = get(path);
        return value != null ? value.toString() : null;
    }
    
    /**
     * 获取字符串配置值（带默认值）
     */
    public String getString(String path, String defaultValue) {
        String value = getString(path);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取整数配置值
     */
    public Integer getInt(String path) {
        Object value = get(path);
        if (value instanceof Integer) {
            return (Integer) value;
        } else if (value instanceof String) {
            try {
                return Integer.parseInt((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 获取整数配置值（带默认值）
     */
    public int getInt(String path, int defaultValue) {
        Integer value = getInt(path);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取长整数配置值
     */
    public Long getLong(String path) {
        Object value = get(path);
        if (value instanceof Long) {
            return (Long) value;
        } else if (value instanceof Integer) {
            return ((Integer) value).longValue();
        } else if (value instanceof String) {
            try {
                return Long.parseLong((String) value);
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }
    
    /**
     * 获取长整数配置值（带默认值）
     */
    public long getLong(String path, long defaultValue) {
        Long value = getLong(path);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取布尔配置值
     */
    public Boolean getBoolean(String path) {
        Object value = get(path);
        if (value instanceof Boolean) {
            return (Boolean) value;
        } else if (value instanceof String) {
            return Boolean.parseBoolean((String) value);
        }
        return null;
    }
    
    /**
     * 获取布尔配置值（带默认值）
     */
    public boolean getBoolean(String path, boolean defaultValue) {
        Boolean value = getBoolean(path);
        return value != null ? value : defaultValue;
    }
    
    /**
     * 获取嵌套配置值
     */
    @SuppressWarnings("unchecked")
    private Object getNestedValue(Map<String, Object> map, String[] keys) {
        Object current = map;
        
        for (String key : keys) {
            if (!(current instanceof Map)) {
                return null;
            }
            current = ((Map<String, Object>) current).get(key);
            if (current == null) {
                return null;
            }
        }
        
        return current;
    }
    
    /**
     * 打印所有配置（用于调试）
     */
    public void printConfig() {
        log.info("=== Configuration ===");
        printMap(config, "");
    }
    
    @SuppressWarnings("unchecked")
    private void printMap(Map<String, Object> map, String prefix) {
        for (Map.Entry<String, Object> entry : map.entrySet()) {
            String key = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
            Object value = entry.getValue();
            
            if (value instanceof Map) {
                printMap((Map<String, Object>) value, key);
            } else {
                // 隐藏敏感信息
                if (key.contains("password") || key.contains("secret") || key.contains("key")) {
                    log.info(key + " = ******");
                } else {
                    log.info(key + " = " + value);
                }
            }
        }
    }
}
