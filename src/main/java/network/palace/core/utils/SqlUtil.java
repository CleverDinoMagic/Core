package network.palace.core.utils;

import com.zaxxer.hikari.HikariDataSource;
import network.palace.core.Core;
import network.palace.storage.MySQLSettings;
import java.sql.Connection;
import java.sql.SQLException;

/** MySQL connections for Pictify and the plugins using Core's SQL API. */
public class SqlUtil implements AutoCloseable {
    private final HikariDataSource pool;

    public SqlUtil() {
        this(MySQLSettings.from(key -> Core.getCoreConfig().get(key),
                Core.getCoreConfig().isConfigurationSection("sql") && Core.getCoreConfig().contains("sql.host") ? "sql" : "mysql",
                "plugins/Core/config.yml"));
    }

    public SqlUtil(MySQLSettings settings) {
        pool = new HikariDataSource(settings.poolConfig());
        try (Connection connection = pool.getConnection(); java.sql.Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS pictify (id INT NOT NULL AUTO_INCREMENT PRIMARY KEY, source TEXT NOT NULL) ENGINE=InnoDB");
        } catch (SQLException e) {
            pool.close();
            throw new IllegalStateException("Cannot initialize MySQL pictify table", e);
        }
    }

    public Connection getConnection() {
        try { return pool.getConnection(); }
        catch (SQLException e) { throw new IllegalStateException("Cannot obtain MySQL connection", e); }
    }

    public void close() { pool.close(); }
}
