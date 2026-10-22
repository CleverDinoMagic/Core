package network.palace.storage;

import java.util.*;

public final class MySQLSettingsTest {
    public static void main(String[] args) {
        Map<String,Object> config = new HashMap<>();
        config.put("mysql.host", "db.example"); config.put("mysql.database", "palace");
        config.put("mysql.username", "operator"); config.put("mysql.password", " secret-value ");
        MySQLSettings settings = MySQLSettings.from(config::get, "mysql", "test-config.yml");
        com.zaxxer.hikari.HikariConfig pool = settings.poolConfig();
        if (!pool.getJdbcUrl().equals("jdbc:mysql://db.example:3306/palace") || !pool.getPassword().equals(" secret-value "))
            throw new AssertionError("Configured credentials and default port");
        config.put("mysql.port", 3307);
        if (!MySQLSettings.from(config::get, "mysql", "test-config.yml").poolConfig().getJdbcUrl().contains(":3307/"))
            throw new AssertionError("Custom MySQL port");
        for (Object value : new Object[]{0, 65536, "3306", 12.5}) {
            config.put("mysql.port", value); invalid(config, "mysql.port");
        }
        config.put("mysql.port", 3306); config.put("mysql.host", "db.example/?password=secret-value"); invalid(config, "mysql");
        config.put("mysql.host", "db.example"); config.put("mysql.username", ""); invalid(config, "mysql.username");
        config.put("mysql.username", "operator"); config.put("mysql.ssl-mode", "invalid"); invalid(config, "mysql");
        System.out.println("MySQL configuration tests passed");
    }
    private static void invalid(Map<String,Object> config, String key) {
        try { MySQLSettings.from(config::get, "mysql", "test-config.yml"); throw new AssertionError("Invalid settings accepted"); }
        catch (IllegalArgumentException e) {
            if (!e.getMessage().contains(key) || !e.getMessage().contains("test-config.yml") || e.getMessage().contains("secret-value"))
                throw new AssertionError("Diagnostic must identify keys/file without credentials");
        }
    }
}
