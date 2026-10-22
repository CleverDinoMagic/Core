package network.palace.storage;

import com.zaxxer.hikari.HikariConfig;
import java.util.*;
import java.util.function.Function;

/** Shared, validated MySQL settings. Credentials are never placed in JDBC URLs. */
public final class MySQLSettings {
    private final String host, database, username, password, sslMode;
    private final int port;

    public MySQLSettings(String host, int port, String database, String username, String password, String sslMode) {
        if (host == null || !host.matches("[A-Za-z0-9_.:-]+")) throw new IllegalArgumentException("Invalid MySQL host");
        if (port < 1 || port > 65535) throw new IllegalArgumentException("Invalid MySQL port");
        if (database == null || !database.matches("[A-Za-z0-9_]+")) throw new IllegalArgumentException("Invalid MySQL database name");
        if (username == null || username.trim().isEmpty()) throw new IllegalArgumentException("MySQL username is required");
        if (password == null || password.trim().isEmpty()) throw new IllegalArgumentException("MySQL password is required");
        if (!Arrays.asList("DISABLED", "PREFERRED", "REQUIRED", "VERIFY_CA", "VERIFY_IDENTITY").contains(sslMode))
            throw new IllegalArgumentException("Invalid MySQL ssl-mode");
        this.host=host;this.port=port;this.database=database;this.username=username;this.password=password;this.sslMode=sslMode;
    }

    public static MySQLSettings from(Function<String,Object> config, String section, String file) {
        List<String> errors = new ArrayList<>();
        String host = text(config, section, "host", errors);
        String database = text(config, section, "database", errors);
        String username = text(config, section, "username", errors);
        String password = text(config, section, "password", errors);
        Object portValue = config.apply(section + ".port");
        int port = portValue == null ? 3306 : portValue instanceof Integer ? (Integer) portValue : -1;
        if (port < 1 || port > 65535) errors.add(section + ".port must be an integer between 1 and 65535");
        Object sslValue = config.apply(section + ".ssl-mode");
        String ssl = sslValue == null ? "PREFERRED" : sslValue instanceof String ? (String) sslValue : "";
        if (!errors.isEmpty()) throw invalid(file, String.join("; ", errors));
        try { return new MySQLSettings(host, port, database, username, password, ssl); }
        catch (IllegalArgumentException e) { throw invalid(file, section + ": " + e.getMessage()); }
    }
    private static String text(Function<String,Object> config, String section, String key, List<String> errors) {
        Object value = config.apply(section + "." + key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            errors.add(section + "." + key + " must be an explicitly configured, non-blank string");
            return null;
        }
        return (String) value;
    }
    private static IllegalArgumentException invalid(String file, String reason) {
        return new IllegalArgumentException(reason + ". Edit " + file + " with your MySQL server settings; SQLite paths and MongoDB credentials are not used.");
    }
    public static MySQLSettings fromProperties(java.nio.file.Path file) throws java.io.IOException {
        Properties properties = new Properties();
        try (java.io.Reader reader = java.nio.file.Files.newBufferedReader(file, java.nio.charset.StandardCharsets.UTF_8)) {
            properties.load(reader);
        }
        return from(key -> {
            String value = properties.getProperty(key);
            if (key.endsWith(".port") && value != null) {
                try { return Integer.valueOf(value); } catch (NumberFormatException ignored) { return value; }
            }
            return value;
        }, "mysql", file.toString());
    }
    public String database() { return database; }
    public HikariConfig poolConfig() {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        String address = host.contains(":") ? "[" + host + "]" : host;
        config.setJdbcUrl("jdbc:mysql://" + address + ":" + port + "/" + database);
        config.setUsername(username);
        config.setPassword(password);
        config.addDataSourceProperty("sslMode", sslMode);
        config.addDataSourceProperty("characterEncoding", "UTF-8");
        config.addDataSourceProperty("connectTimeout", "10000");
        config.addDataSourceProperty("socketTimeout", "30000");
        config.setMaximumPoolSize(4);
        config.setMinimumIdle(0);
        config.setConnectionTimeout(10000);
        return config;
    }
}
