package network.palace.storage;

import com.zaxxer.hikari.HikariDataSource;
import java.nio.file.*;
import java.sql.*;
import java.util.*;

/** Copy allowlisted legacy relational tables into existing empty InnoDB tables. */
public final class ImportSQLiteTables {
    public static void main(String[] args) throws Exception {
        if (args.length != 3) throw new IllegalArgumentException("Usage: ImportSQLiteTables <mysql.properties> <pictify|forum> <legacy.sqlite>");
        MySQLSettings settings = MySQLSettings.fromProperties(Paths.get(args[0]));
        Map<String,String> tables = new LinkedHashMap<>();
        if (args[1].equals("pictify")) tables.put("pictify", "id,source");
        else if (args[1].equals("forum")) {
            tables.put("core_members", "member_id,email,member_group_id");
            tables.put("core_pfields_content", "member_id,field_2,field_3,field_4");
        } else throw new IllegalArgumentException("Expected pictify or forum");
        Path file = Paths.get(args[2]).toAbsolutePath();
        if (!Files.isRegularFile(file)) throw new IllegalArgumentException("SQLite source file does not exist");
        Class.forName("org.sqlite.JDBC");
        try (HikariDataSource pool = new HikariDataSource(settings.poolConfig());
             Connection target = pool.getConnection();
             Connection source = DriverManager.getConnection("jdbc:sqlite:" + file.toUri() + "?mode=ro")) {
            for (String table : tables.keySet()) {
                try (PreparedStatement statement = target.prepareStatement("SELECT ENGINE FROM information_schema.TABLES WHERE TABLE_SCHEMA=DATABASE() AND TABLE_NAME=?")) {
                    statement.setString(1, table);
                    try (ResultSet engine = statement.executeQuery()) {
                        if (!engine.next() || !"InnoDB".equalsIgnoreCase(engine.getString(1)))
                            throw new IllegalStateException("Target table " + table + " must already exist and use InnoDB");
                    }
                }
            }
            target.setAutoCommit(false);
            try {
                // Lock and validate all destinations before writing any rows.
                for (String table : tables.keySet()) {
                    try (Statement statement = target.createStatement(); ResultSet rows = statement.executeQuery("SELECT * FROM " + table + " FOR UPDATE")) {
                        if (rows.next()) throw new IllegalStateException("Target table " + table + " must be empty");
                    }
                }
                for (Map.Entry<String,String> table : tables.entrySet()) {
                    int columns = table.getValue().split(",").length;
                    String placeholders = String.join(",", Collections.nCopies(columns, "?"));
                    try (Statement read = source.createStatement();
                         ResultSet rows = read.executeQuery("SELECT " + table.getValue() + " FROM " + table.getKey());
                         PreparedStatement insert = target.prepareStatement("INSERT INTO " + table.getKey() + "(" + table.getValue() + ") VALUES(" + placeholders + ")")) {
                        while (rows.next()) {
                            for (int column = 1; column <= columns; column++) insert.setObject(column, rows.getObject(column));
                            insert.executeUpdate();
                        }
                    }
                }
                target.commit();
            } catch (Exception e) {
                try { target.rollback(); } catch (SQLException cleanup) { e.addSuppressed(cleanup); }
                throw e;
            }
        }
        System.out.println("Legacy relational data committed to MySQL; SQLite source unchanged");
    }
}
