package network.palace.storage;

import org.bson.Document;
import java.nio.file.*;
import java.sql.*;

/** Transfer the legacy document file to an empty MySQL store without modifying it. */
public final class ImportSQLite {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ImportSQLite <mysql.properties> <legacy.sqlite>");
        Path source = Paths.get(args[1]).toAbsolutePath();
        if (!Files.isRegularFile(source)) throw new IllegalArgumentException("SQLite source file does not exist");
        Class.forName("org.sqlite.JDBC");
        try (MySQLStore store = new MySQLStore(MySQLSettings.fromProperties(Paths.get(args[0])));
             Connection legacy = DriverManager.getConnection("jdbc:sqlite:" + source.toUri() + "?mode=ro");
             Statement statement = legacy.createStatement();
             ResultSet rows = statement.executeQuery("SELECT collection,document FROM palace_documents ORDER BY rowid")) {
            store.importDocuments(sink -> {
                while (rows.next()) sink.insert(rows.getString(1), Document.parse(rows.getString(2)));
            });
        }
        System.out.println("SQLite document data committed to MySQL; source file unchanged");
    }
}
