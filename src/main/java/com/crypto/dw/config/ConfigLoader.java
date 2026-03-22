package com.crypto.dw.config;

import lombok.extern.slf4j.Slf4j;
import org.yaml.snakeyaml.Yaml;
import java.io.InputStream;
import java.io.Serializable;
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
public class ConfigLoader implements Serializable {
    
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
     * 
     * 注意：添加了详细的调试信息和多种加载方式，以适应不同的运行环境（本地、StreamPark、Docker 等）
     */
    /**
     * 加载 YAML 文件
     * 支持从 classpath 加载配置文件
     * 
     * 注意：针对 Flink ChildFirstClassLoader 优化，使用多种加载方式
     * 方法 1: 标准 ClassLoader 方式
     * 方法 2: URL + openStream 方式
     * 方法 3: JAR 文件系统方式（适用于 ChildFirstClassLoader）
     */
    private Map<String, Object> loadYamlFile(String filename) {
        try {
            Yaml yaml = new Yaml();

            // 打印调试信息
            log.info("=== 配置文件加载调试信息 ===");
            log.info("目标文件: " + filename);
            log.info("当前工作目录: " + System.getProperty("user.dir"));
            log.info("ClassLoader: " + getClass().getClassLoader().getClass().getName());

            // 尝试多种路径加载配置文件
            InputStream inputStream = null;
            String[] paths = {
                "config/" + filename,  // 标准路径（推荐）
                filename,              // 根路径
                "/" + filename,        // 绝对路径
                "/config/" + filename  // 绝对路径 + config
            };

            String successPath = null;

            // 方法 1: 使用 getResourceAsStream（标准方式）
            for (String path : paths) {
                log.info("尝试路径 (getResourceAsStream): " + path);
                inputStream = getClass().getClassLoader().getResourceAsStream(path);
                if (inputStream != null) {
                    successPath = path;
                    log.info("✅ 成功加载配置文件 (getResourceAsStream): " + path);
                    break;
                } else {
                    log.info("❌ 路径不存在: " + path);
                }
            }

            // 方法 2: 如果方法 1 失败，尝试使用 getResource + openStream（适用于 ChildFirstClassLoader）
            if (inputStream == null) {
                log.info("尝试使用 getResource + openStream 方式...");
                for (String path : paths) {
                    log.info("尝试路径 (getResource): " + path);
                    try {
                        java.net.URL resource = getClass().getClassLoader().getResource(path);
                        if (resource != null) {
                            log.info("找到资源 URL: " + resource);
                            inputStream = resource.openStream();
                            if (inputStream != null) {
                                successPath = path;
                                log.info("✅ 成功加载配置文件 (getResource): " + path);
                                break;
                            }
                        } else {
                            log.info("❌ 资源不存在: " + path);
                        }
                    } catch (Exception e) {
                        log.info("❌ 加载失败: " + path + " - " + e.getMessage());
                    }
                }
            }

            // 方法 3: 如果方法 2 失败，尝试使用 JAR 文件系统方式（适用于 Flink ChildFirstClassLoader）
            if (inputStream == null) {
                log.info("尝试使用 JAR 文件系统方式...");
                inputStream = loadFromJarFileSystem(filename);
                if (inputStream != null) {
                    log.info("✅ 成功加载配置文件 (JAR FileSystem)");
                }
            }

            if (inputStream == null) {
                log.error("Error: Config file not found: " + filename);
                log.error("Tried all methods: getResourceAsStream, getResource, JAR FileSystem");

                // 尝试列出 classpath 中的资源（调试用）
                try {
                    java.net.URL resource = getClass().getClassLoader().getResource("config/");
                    if (resource != null) {
                        log.info("Found config/ directory at: " + resource);
                    } else {
                        log.info("config/ directory not found in classpath");
                    }
                } catch (Exception e) {
                    log.info("Cannot list classpath resources: " + e.getMessage());
                }

                return new HashMap<>();
            }

            log.info("开始解析 YAML 文件...");
            Map<String, Object> data = yaml.load(inputStream);
            inputStream.close();

            if (data == null || data.isEmpty()) {
                log.error("Warning: Config file is empty: " + filename);
                return new HashMap<>();
            }

            log.info("✅ 配置文件解析成功，包含 " + data.size() + " 个顶级配置项");
            log.info("=== 配置文件加载完成 ===");

            return data;
        } catch (Exception e) {
            log.error("Error loading config file: " + filename);
            log.error("Exception: " + e.getClass().getName() + ": " + e.getMessage());
            e.printStackTrace();
            return new HashMap<>();
        }
    }

    /**
     * 使用 JAR 文件系统方式加载配置文件
     * 这种方式可以绕过 ChildFirstClassLoader 的限制
     * 
     * 注意：必须在关闭文件系统之前将内容读取到内存中，
     *      否则会出现 "Inflater has been closed" 错误
     * 
     * @param filename 配置文件名
     * @return InputStream 或 null
     */
    private InputStream loadFromJarFileSystem(String filename) {
        try {
            // 获取当前类的 CodeSource，找到 JAR 文件位置
            java.security.ProtectionDomain pd = getClass().getProtectionDomain();
            java.security.CodeSource cs = pd.getCodeSource();
            if (cs == null) {
                log.info("CodeSource is null, cannot use JAR FileSystem method");
                return null;
            }

            java.net.URL location = cs.getLocation();
            log.info("CodeSource location: " + location);

            // 如果是 JAR 文件，使用 JAR 文件系统读取
            if (location.getProtocol().equals("file") && location.getPath().endsWith(".jar")) {
                String jarPath = location.getPath();
                log.info("JAR file path: " + jarPath);

                // 创建 JAR 文件系统
                java.nio.file.FileSystem fs = null;
                try {
                    java.net.URI jarUri = new java.net.URI("jar:file:" + jarPath);
                    fs = java.nio.file.FileSystems.newFileSystem(jarUri, new HashMap<>());

                    // 尝试多种路径
                    String[] paths = {
                        "/config/" + filename,
                        "/" + filename
                    };

                    for (String path : paths) {
                        log.info("尝试 JAR 内路径: " + path);
                        java.nio.file.Path configPath = fs.getPath(path);
                        if (java.nio.file.Files.exists(configPath)) {
                            log.info("✅ 找到配置文件: " + path);
                            
                            // 关键修复：在关闭文件系统之前，将文件内容读取到内存中
                            // 否则会出现 "Inflater has been closed" 错误
                            byte[] fileContent = java.nio.file.Files.readAllBytes(configPath);
                            log.info("✅ 成功读取配置文件内容，大小: " + fileContent.length + " 字节");
                            
                            // 返回基于字节数组的 InputStream，不依赖文件系统
                            return new java.io.ByteArrayInputStream(fileContent);
                        } else {
                            log.info("❌ 文件不存在: " + path);
                        }
                    }
                } finally {
                    if (fs != null) {
                        try {
                            fs.close();
                        } catch (Exception e) {
                            // Ignore close errors
                        }
                    }
                }
            } else {
                log.info("Not a JAR file or not file protocol: " + location);
            }

            return null;
        } catch (Exception e) {
            log.info("JAR FileSystem method failed: " + e.getMessage());
            return null;
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
