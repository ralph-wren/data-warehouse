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
     * 
     * 优先级：System Property > Environment Variable > 默认值
     * 这样可以支持：
     * 1. 环境变量：export APP_ENV=docker
     * 2. JVM 参数：-DAPP_ENV=docker
     * 3. StreamPark Program Args：--APP_ENV docker
     * 
     * 注意：每个环境配置文件（application-{env}.yml）都是完整独立的配置，
     *      不再需要加载基础配置文件（application.yml）并合并
     */
    private void loadConfig() {
        // 优先从 System Property 读取（支持 StreamPark Program Args）
        String env = System.getProperty("APP_ENV");
        
        // 如果 System Property 没有，再从环境变量读取
        if (env == null || env.isEmpty()) {
            env = System.getenv("APP_ENV");
        }
        
        // 如果都没有，使用默认值
        if (env == null || env.isEmpty()) {
            env = "dev";
        }
        
        log.info("Loading configuration for environment: " + env);
        log.info("  System Property APP_ENV: " + System.getProperty("APP_ENV"));
        log.info("  Environment Variable APP_ENV: " + System.getenv("APP_ENV"));
        
        // 直接加载环境特定配置文件（完整独立的配置）
        String envConfigFile = "application-" + env + ".yml";
        config = loadYamlFile(envConfigFile);
        
        if (config.isEmpty()) {
            log.error("Error: Environment-specific config file not found: " + envConfigFile);
            log.error("Please ensure the config file exists in src/main/resources/config/");
            // 使用空配置，避免 NPE
            config = new HashMap<>();
        } else {
            log.info("Configuration loaded successfully from: " + envConfigFile);
        }
        
        // 解析环境变量引用（如 ${OKX_API_KEY}）
        config = resolveEnvVars(config);
        
        log.info("Total config keys: " + countKeys(config));
    }
    
    /**
     * 加载 YAML 文件
     * 支持从 classpath 加载配置文件
     */
    private Map<String, Object> loadYamlFile(String filename) {
        try {
            Yaml yaml = new Yaml();
            
            // 尝试多种路径加载配置文件
            InputStream inputStream = null;
            String[] paths = {
                "config/" + filename,  // 标准路径
                filename,              // 根路径
                "/" + filename,        // 绝对路径
                "/config/" + filename  // 绝对路径 + config
            };
            
            for (String path : paths) {
                inputStream = getClass().getClassLoader().getResourceAsStream(path);
                if (inputStream != null) {
                    log.info("Successfully loaded config file from path: " + path);
                    break;
                }
            }
            
            if (inputStream == null) {
                log.info("Warning: Config file not found: " + filename);
                log.info("Tried paths: " + String.join(", ", paths));
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
