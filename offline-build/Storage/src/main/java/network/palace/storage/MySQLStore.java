package network.palace.storage;

/** Shared network persistence for Core and PalaceBungee document collections. */
public final class MySQLStore extends DocumentStore {
    public MySQLStore(MySQLSettings settings) { super(settings.poolConfig(), settings.database(), true); }
}
