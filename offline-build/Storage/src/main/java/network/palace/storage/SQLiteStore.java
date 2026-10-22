package network.palace.storage;

import com.zaxxer.hikari.HikariConfig;
import java.io.File;

/** Legacy SQLite reader used only for offline migration and compatibility tests. */
public final class SQLiteStore extends DocumentStore {
    public SQLiteStore(File file) { super(config(file), file.getName(), false); }
    private static HikariConfig config(File file) {
        File parent = file.getAbsoluteFile().getParentFile();
        if (!parent.isDirectory() && !parent.mkdirs()) throw new IllegalStateException("Cannot create " + parent);
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("org.sqlite.JDBC");
        config.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
        config.setMaximumPoolSize(1);
        config.setConnectionInitSql("PRAGMA busy_timeout=10000");
        return config;
    }
}
