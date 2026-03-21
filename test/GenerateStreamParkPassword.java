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
        
        System.out.println("========================================");
        System.out.println("StreamPark 密码生成");
        System.out.println("========================================");
        System.out.println();
        System.out.println("输入:");
        System.out.println("  密码: " + password);
        System.out.println("  Salt: " + salt);
        System.out.println("  算法: SHA-256");
        System.out.println("  迭代: 1024");
        System.out.println();
        System.out.println("输出:");
        System.out.println("  Password Hash: " + hashedPassword);
        System.out.println();
        System.out.println("SQL 更新语句:");
        System.out.println("  UPDATE t_user SET");
        System.out.println("    salt = '" + salt + "',");
        System.out.println("    password = '" + hashedPassword + "'");
        System.out.println("  WHERE username = 'admin';");
        System.out.println();
    }
}
