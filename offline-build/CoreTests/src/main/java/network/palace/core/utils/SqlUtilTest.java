package network.palace.core.utils;

import network.palace.storage.MySQLSettings;
import java.nio.file.Paths;
import java.sql.*;

public final class SqlUtilTest {
    public static void main(String[] args) throws Exception {
        String file = System.getenv("PALACE_MYSQL_TEST_CONFIG");
        if (file == null) { System.out.println("MySQL Pictify tests skipped: set PALACE_MYSQL_TEST_CONFIG"); return; }
        MySQLSettings settings = MySQLSettings.fromProperties(Paths.get(file));
        if (!settings.database().startsWith("palace_test_")) throw new IllegalArgumentException("Use a dedicated schema named palace_test_...");
        try (SqlUtil sql = new SqlUtil(settings); Connection connection = sql.getConnection(); Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM pictify");
            try {
                try (PreparedStatement insert = connection.prepareStatement("INSERT INTO pictify(source) VALUES(?)")) {
                    insert.setString(1, "test-image-🎢"); insert.executeUpdate();
                }
                try (ResultSet rows = statement.executeQuery("SELECT id,source FROM pictify")) {
                    if (!rows.next() || rows.getInt("id") < 1 || !rows.getString("source").equals("test-image-🎢"))
                        throw new AssertionError("Pictify MySQL auto-increment and unicode persistence");
                }
            } finally { statement.executeUpdate("DELETE FROM pictify"); }
        }
        System.out.println("MySQL Pictify SQL API tests passed");
    }
}
