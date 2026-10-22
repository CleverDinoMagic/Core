package network.palace.storage;

import com.mongodb.Block;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.nio.file.*;
import java.util.*;

/** Executable integration tests against the actual bundled SQLite JDBC driver. */
public class MySQLStoreTest {
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        String config = System.getenv("PALACE_MYSQL_TEST_CONFIG");
        if (config == null) { System.out.println("MySQL integration tests skipped: set PALACE_MYSQL_TEST_CONFIG to a dedicated test schema"); return; }
        MySQLSettings settings = MySQLSettings.fromProperties(Paths.get(config));
        if (!settings.database().startsWith("palace_test_")) throw new IllegalArgumentException("Use a dedicated schema named palace_test_...");
        try (MySQLStore clean = new MySQLStore(settings)) {
            for (String name : Arrays.asList("players", "parties", "rides", "case", "Case")) clean.database().getCollection(name).deleteMany(new Document());
        }
        UUID uuid=UUID.randomUUID();ObjectId id=new ObjectId();
        try(MySQLStore core=new MySQLStore(settings);MySQLStore proxy=new MySQLStore(settings)) {
            MongoCollection<Document> players=core.database().getCollection("players");
            players.insertOne(new Document("_id",id).append("uuid",uuid).append("rank","Guest").append("tokens",1)
                    .append("lastOnline",1234567890123L).append("nullable",null)
                    .append("bans",new ArrayList<>(Arrays.asList(new Document("active",false),new Document("active",true)))));
            check(players.find(Filters.eq("uuid",uuid.toString())).first()!=null,"UUID must be stored as string");
            check(proxy.database().getCollection("players").find(Filters.eq("_id",id)).first()!=null,"shared database visibility");
            players.updateOne(Filters.eq("_id",id),Updates.inc("tokens",4));
            check(players.find().first().getInteger("tokens")==5,"increment preserves integer type");
            check(players.find().first().getLong("lastOnline")==1234567890123L,"long roundtrip");
            players.updateOne(new Document("_id",id).append("bans.active",true),Updates.set("bans.$.active",false));
            check(players.find(new Document("bans.active",true)).first()==null,"positional moderation update");
            players.updateOne(Filters.eq("_id",id),Updates.addToSet("friends","A"));
            players.updateOne(Filters.eq("_id",id),Updates.addToSet("friends","A"));
            check(((List<?>)players.find().first().get("friends")).size()==1,"set uniqueness");
            players.updateOne(Filters.eq("_id",id),Updates.pull("friends","A"));
            check(((List<?>)players.find().first().get("friends")).isEmpty(),"array pull");
            players.updateOne(Filters.eq("uuid","second"),Updates.set("tokens",20),new UpdateOptions().upsert(true));
            check(players.find().sort(Sorts.descending("tokens")).limit(1).first().getString("uuid").equals("second"),"sort and limit");
            Document projected=players.find(Filters.eq("_id",id)).projection(new Document("uuid",1).append("_id",0)).first();
            check(projected.size()==1 && projected.containsKey("uuid"),"projection");
            check(players.find(Filters.eq("_id",id)).projection(new Document("_id",1)).first().size()==1,"ID-only projection");
            check(players.count(Filters.or(Filters.eq("tokens",5),Filters.eq("tokens",20)))==2,"logical filters");
            check(players.count(Filters.exists("nullable",true))==1,"exists must include explicit null");
            MongoCollection<Document> parties=core.database().getCollection("parties");
            parties.insertOne(new Document("invited",new ArrayList<>(Arrays.asList(new Document("uuid","A").append("expires",10L),new Document("uuid","B").append("expires",30L)))));
            parties.updateMany(Filters.elemMatch("invited",Filters.lte("expires",20L)),Updates.pull("invited",Filters.lte("expires",20L)));
            check(((List<?>)parties.find().first().get("invited")).size()==1,"expiry pull");
            MongoCollection<Document> rides=core.database().getCollection("rides");
            rides.insertMany(Arrays.asList(new Document("uuid","A").append("name","ride"),new Document("uuid","B").append("name","ride"),new Document("uuid","A").append("name","ride")));
            List<Document> leaderboard=new ArrayList<>();
            rides.aggregate(Arrays.asList(Aggregates.match(Filters.eq("name","ride")),Aggregates.group("$uuid",Accumulators.sum("total",1)),Aggregates.sort(Sorts.descending("total")),Aggregates.limit(1)))
                    .forEach((Block<Document>)leaderboard::add);
            check(leaderboard.get(0).getString("_id").equals("A") && leaderboard.get(0).getInteger("total")==2,"ride leaderboard");
            try { players.insertMany(Arrays.asList(new Document("_id",new ObjectId()).append("uuid","rollback"),new Document("_id",id)));throw new AssertionError("duplicate accepted"); }
            catch(IllegalStateException expected) { check(players.find(Filters.eq("uuid","rollback")).first()==null,"batch rollback"); }
            java.util.concurrent.ExecutorService workers = java.util.concurrent.Executors.newFixedThreadPool(2);
            try {
                java.util.concurrent.Future<?> a = workers.submit(() -> { for (int i=0;i<50;i++) players.updateOne(Filters.eq("_id",id),Updates.inc("tokens",1)); });
                java.util.concurrent.Future<?> b = workers.submit(() -> { for (int i=0;i<50;i++) proxy.database().getCollection("players").updateOne(Filters.eq("_id",id),Updates.inc("tokens",1)); });
                a.get(); b.get();
                check(players.find(Filters.eq("_id",id)).first().getInteger("tokens")==105,"concurrent increments cannot lose writes");
                a = workers.submit(() -> { for (int i=0;i<25;i++) players.updateOne(Filters.eq("uuid","upsert-race"),Updates.inc("tokens",1),new UpdateOptions().upsert(true)); });
                b = workers.submit(() -> { for (int i=0;i<25;i++) proxy.database().getCollection("players").updateOne(Filters.eq("uuid","upsert-race"),Updates.inc("tokens",1),new UpdateOptions().upsert(true)); });
                a.get(); b.get();
                check(players.count(Filters.eq("uuid","upsert-race"))==1 && players.find(Filters.eq("uuid","upsert-race")).first().getInteger("tokens")==50,"concurrent upserts must share one record");
                players.deleteOne(Filters.eq("uuid","upsert-race"));
            } finally { workers.shutdownNow(); }
        }
        try(MySQLStore reopened=new MySQLStore(settings)) {
            check(reopened.database().getCollection("players").find(Filters.eq("_id",id)).first()!=null,"restart persistence");
            check(reopened.database().getCollection("players").deleteMany(new Document()).getDeletedCount()==2,"delete count");
        }
        try (MySQLStore store = new MySQLStore(settings)) {
            for (String name : Arrays.asList("parties", "rides")) store.database().getCollection(name).deleteMany(new Document());
            store.database().getCollection("case").insertOne(new Document("_id", "A").append("value", "🎢"));
            store.database().getCollection("case").insertOne(new Document("_id", "a"));
            check(store.database().getCollection("case").count() == 2, "case-sensitive IDs");
            check(store.database().getCollection("Case").count() == 0, "case-sensitive collections");
            check(store.database().getCollection("case").find(Filters.eq("_id", "A")).first().getString("value").equals("🎢"), "utf8mb4 roundtrip");
            store.database().getCollection("case").deleteMany(new Document());
            try {
                store.importDocuments(sink -> {
                    sink.insert("players", new Document("_id", "staged"));
                    throw new java.io.IOException("Simulated invalid export");
                });
                throw new AssertionError("Invalid import accepted");
            } catch (java.io.IOException expected) {
                check(store.database().getCollection("players").count() == 0, "whole-import rollback");
            }
            store.importDocuments(sink -> sink.insert("players", new Document("_id", "imported").append("uuid", uuid)));
            check(store.database().getCollection("players").find().first().getString("uuid").equals(uuid.toString()), "import normalizes UUID");
            try { store.importDocuments(sink -> sink.insert("players", new Document("_id", "overwrite"))); throw new AssertionError("Non-empty import accepted"); }
            catch (IllegalStateException expected) { check(store.database().getCollection("players").count() == 1, "existing destination preserved"); }
            store.database().getCollection("players").deleteMany(new Document());
        }
        Path exports = Files.createTempDirectory("palace-mysql-import");
        try (MySQLStore store = new MySQLStore(settings)) {
            Files.write(exports.resolve("players.json"), Arrays.asList(new Document("_id", id).append("uuid", uuid.toString()).toJson()));
            Files.write(exports.resolve("zzbad.json"), Arrays.asList("invalid JSON"));
            try { ImportMongoExport.importDirectory(store, exports); throw new AssertionError("Invalid JSON import accepted"); }
            catch (java.io.IOException expected) { check(store.database().getCollection("players").count() == 0, "JSON export import rollback"); }
            Files.delete(exports.resolve("zzbad.json"));
            ImportMongoExport.importDirectory(store, exports);
            check(store.database().getCollection("players").find(Filters.eq("_id", id)).first().getString("uuid").equals(uuid.toString()), "Mongo export import preserves BSON IDs");
            store.database().getCollection("players").deleteMany(new Document());
            Path sqlite = exports.resolve("legacy.sqlite");
            try (SQLiteStore legacy = new SQLiteStore(sqlite.toFile())) {
                legacy.database().getCollection("players").insertOne(new Document("uuid", uuid.toString()));
            }
            ImportSQLite.main(new String[]{config, sqlite.toString()});
            check(store.database().getCollection("players").find().first().getString("uuid").equals(uuid.toString()), "SQLite to MySQL migration");
            store.database().getCollection("players").deleteMany(new Document());
        } finally {
            try (java.util.stream.Stream<Path> paths = Files.walk(exports)) {
                for (Path path : (Iterable<Path>) paths.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
        Path relational = Files.createTempFile("palace-relational", ".sqlite");
        try (com.zaxxer.hikari.HikariDataSource pool = new com.zaxxer.hikari.HikariDataSource(settings.poolConfig());
             java.sql.Connection target = pool.getConnection(); java.sql.Statement sql = target.createStatement()) {
            sql.execute("CREATE TABLE IF NOT EXISTS pictify (id INT AUTO_INCREMENT PRIMARY KEY, source TEXT NOT NULL) ENGINE=InnoDB");
            sql.execute("CREATE TABLE IF NOT EXISTS core_members (member_id INT PRIMARY KEY, email VARCHAR(255) UNIQUE, member_group_id INT) ENGINE=InnoDB");
            sql.execute("CREATE TABLE IF NOT EXISTS core_pfields_content (member_id INT PRIMARY KEY, field_2 TEXT, field_3 TEXT, field_4 TEXT) ENGINE=InnoDB");
            for (String table : Arrays.asList("pictify", "core_members", "core_pfields_content")) sql.execute("DELETE FROM " + table);
            try (java.sql.Connection legacy = java.sql.DriverManager.getConnection("jdbc:sqlite:" + relational);
                 java.sql.Statement write = legacy.createStatement()) {
                write.execute("CREATE TABLE pictify(id INTEGER, source TEXT)");
                write.execute("INSERT INTO pictify VALUES(42, 'legacy image')");
                write.execute("CREATE TABLE core_members(member_id INTEGER, email TEXT, member_group_id INTEGER)");
                write.execute("CREATE TABLE core_pfields_content(member_id INTEGER, field_2 TEXT, field_3 TEXT, field_4 TEXT)");
                write.execute("INSERT INTO core_members VALUES(1, 'member@example.test', 3), (2, 'member@example.test', 3)");
                write.execute("INSERT INTO core_pfields_content VALUES(1, 'token', 'uuid', 'username')");
            }
            ImportSQLiteTables.main(new String[]{config, "pictify", relational.toString()});
            try (java.sql.ResultSet rows = sql.executeQuery("SELECT source FROM pictify WHERE id=42")) {
                check(rows.next() && rows.getString(1).equals("legacy image"), "relational Pictify migration");
            }
            try { ImportSQLiteTables.main(new String[]{config, "forum", relational.toString()}); throw new AssertionError("Invalid forum import accepted"); }
            catch (java.sql.SQLException expected) {
                try (java.sql.ResultSet rows = sql.executeQuery("SELECT COUNT(*) FROM core_members")) { rows.next(); check(rows.getInt(1) == 0, "relational import rollback"); }
            }
            try (java.sql.Connection legacy = java.sql.DriverManager.getConnection("jdbc:sqlite:" + relational); java.sql.Statement write = legacy.createStatement()) {
                write.execute("DELETE FROM core_members WHERE member_id=2");
            }
            ImportSQLiteTables.main(new String[]{config, "forum", relational.toString()});
            try (java.sql.ResultSet rows = sql.executeQuery("SELECT field_4 FROM core_pfields_content WHERE member_id=1")) {
                check(rows.next() && rows.getString(1).equals("username"), "forum linking fields preserved");
            }
            for (String table : Arrays.asList("pictify", "core_members", "core_pfields_content")) sql.execute("DELETE FROM " + table);
        } finally { Files.deleteIfExists(relational); }
        System.out.println("MySQL integration tests passed");
    }
}
