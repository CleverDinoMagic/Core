package network.palace.core.mongo;

import org.bson.Document;
import java.nio.file.*;
import java.util.*;

/** Exercises the pre-login query against the real SQL storage layer. */
public final class ParkJoinDataTest {
    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("park-login-test");
        String mysqlConfig = System.getenv("PALACE_MYSQL_TEST_CONFIG");
        network.palace.storage.DocumentStore store;
        if (mysqlConfig == null) store = new network.palace.storage.SQLiteStore(directory.resolve("palace.sqlite").toFile());
        else {
            network.palace.storage.MySQLSettings settings = network.palace.storage.MySQLSettings.fromProperties(Paths.get(mysqlConfig));
            if (!settings.database().startsWith("palace_test_")) throw new IllegalArgumentException("Use a dedicated schema named palace_test_...");
            store = new network.palace.storage.MySQLStore(settings);
            store.database().getCollection("players").deleteMany(new Document());
        }
        MongoHandler handler = new MongoHandler(store);
        try {
            UUID uuid = UUID.randomUUID();
            String[] fields = {"buildmode", "settings", "magicband", "fastpass", "outfit", "outfitPurchases"};
            Document fresh = handler.getParkJoinData(uuid, fields);
            for (String section : Arrays.asList("settings", "magicband", "fastpass")) {
                check(fresh.get(section) instanceof Document, "Missing default " + section);
            }
            check(!fresh.getBoolean("buildmode"), "New players must not get build mode");
            check(fresh.getString("outfit").equals("0,0,0,0"), "Default outfit");
            check(fresh.get("outfitPurchases", ArrayList.class).isEmpty(), "Default purchases");
            check(handler.getFriendList(uuid).isEmpty(), "New player friends");
            check(handler.getDatabase().getCollection("players").count() == 0, "Read must not create partial player");
            handler.getDatabase().getCollection("players").insertOne(new Document("uuid", uuid.toString()));
            check(handler.getParkJoinData(uuid, fields).get("settings") instanceof Document, "Missing parks section");
            Document saved = new Document("buildmode", true).append("settings", new Document("pack", "enabled"))
                    .append("outfit", "1,2,3,4").append("privateField", "excluded");
            handler.getDatabase().getCollection("players").updateOne(new Document("uuid", uuid.toString()),
                    new Document("$set", new Document("parks", saved)));
            Document existing = handler.getParkJoinData(uuid, fields);
            check(existing.getBoolean("buildmode"), "Saved build mode preserved");
            check(existing.getString("outfit").equals("1,2,3,4"), "Saved outfit preserved");
            check(existing.get("settings", Document.class).getString("pack").equals("enabled"), "Saved settings preserved");
            check(!existing.containsKey("privateField"), "Projection preserved");
            check(!handler.getDatabase().getCollection("players").find().first().get("parks", Document.class).containsKey("magicband"),
                    "Defaults must not overwrite stored data");
        } finally {
            handler.getDatabase().getCollection("players").deleteMany(new Document());
            handler.close();
            try (java.util.stream.Stream<Path> paths = Files.walk(directory)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        System.out.println("SQL park pre-login tests passed");
    }
}
