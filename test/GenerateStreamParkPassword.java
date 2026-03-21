import org.apache.shiro.crypto.hash.Sha256Hash;

/**
 * StreamPark 密码生成工具
 * 使用 Shiro 的 SHA-256 加密算法生成密码哈希
 */
public class GenerateStreamParkPassword {
    
    public static void main(String[] args) {
        // 密码
        String password = "streampark";
        
        // Salt (26 字符)
        String salt = "26f87aee40e022f38e8ca2d8c9";
        
        // 使用 Shiro SimpleHash 生成密码哈希
        // 算法: SHA-256
        // 迭代次数: 1024
        Sha256Hash hash = new Sha256Hash(password, salt, 1024);
        String hashedPassword = hash.toHex();
        
        log.info("========================================");
        log.info("StreamPark 密码生成");
        log.info("========================================");
        
        log.info("输入:");
        log.info("  密码: " + password);
        log.info("  Salt: " + salt);
        log.info("  算法: SHA-256");
        log.info("  迭代: 1024");
        
        log.info("输出:");
        log.info("  Password Hash: " + hashedPassword);
        
        log.info("SQL 更新语句:");
        log.info("  UPDATE t_user SET");
        log.info("    salt = '" + salt + "',");
        log.info("    password = '" + hashedPassword + "'");
        log.info("  WHERE username = 'admin';");
        
    }
}
