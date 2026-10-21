package network.palace.core.utils;

import network.palace.core.Core;
import org.bukkit.ChatColor;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

/**
 * Utility functions for interacting with the local SQLite database.
 */
public class SqlUtil {
    private String url = "";

    /**
     * Instantiates a new Sql util.
     */
    public SqlUtil() {
        loadLogin();
    }

    /**
     * Load login.
     */
    private void loadLogin() {
        java.io.File file = new java.io.File(Core.getCoreConfig().getString("sql.path", "palace-sql.sqlite")).getAbsoluteFile();
        java.io.File parent = file.getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create " + parent);
        url = "jdbc:sqlite:" + file.getAbsolutePath();
        try { Class.forName("org.sqlite.JDBC"); } catch (ClassNotFoundException e) { throw new IllegalStateException(e); }

    }

    /**
     * Gets sql connection.
     *
     * @return the connection
     */
    public Connection getConnection() {
        try {
            Connection connection = DriverManager.getConnection(url);
            try (java.sql.Statement statement = connection.createStatement()) {
                statement.execute("PRAGMA busy_timeout=10000");
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("CREATE TABLE IF NOT EXISTS pictify (id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL)");
            }
            return connection;
        } catch (SQLException e) {
            if (!Core.isDashboardAndSqlDisabled()) {
                Core.logMessage("Core", ChatColor.RED + "Could not connect to database!");
                ErrorUtil.displayError(e);
            }
            return null;
        }
    }
}