package network.palace.storage;

import com.mongodb.Block;
import com.mongodb.client.MongoCollection;
import com.mongodb.client.model.*;
import org.bson.Document;
import org.bson.types.ObjectId;
import java.nio.file.*;
import java.util.*;

/** Executable integration tests against the actual bundled SQLite JDBC driver. */
public class SQLiteStoreTest {
    private static void check(boolean condition,String message) { if(!condition)throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path folder=Files.createTempDirectory("palace-sqlite-test");
        java.io.File file=folder.resolve("shared.sqlite").toFile();
        UUID uuid=UUID.randomUUID();ObjectId id=new ObjectId();
        try(SQLiteStore core=new SQLiteStore(file);SQLiteStore proxy=new SQLiteStore(file)) {
            MongoCollection<Document> players=core.database().getCollection("players");
            players.insertOne(new Document("_id",id).append("uuid",uuid).append("rank","Guest").append("tokens",1)
                    .append("lastOnline",1234567890123L).append("nullable",null)
                    .append("bans",new ArrayList<>(Arrays.asList(new Document("active",false),new Document("active",true)))));
            check(players.find(Filters.eq("uuid",uuid.toString())).first()!=null,"UUID must be stored as string");
            check(proxy.database().getCollection("players").find(Filters.eq("_id",id)).first()!=null,"shared file visibility");
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
            Thread a=new Thread(()-> {for(int i=0;i<50;i++)players.updateOne(Filters.eq("_id",id),Updates.inc("tokens",1));});
            Thread b=new Thread(()-> {for(int i=0;i<50;i++)proxy.database().getCollection("players").updateOne(Filters.eq("_id",id),Updates.inc("tokens",1));});
            a.start();b.start();a.join();b.join();
            check(players.find(Filters.eq("_id",id)).first().getInteger("tokens")==105,"concurrent increments cannot lose writes");
        }
        try(SQLiteStore reopened=new SQLiteStore(file)) {
            check(reopened.database().getCollection("players").find(Filters.eq("_id",id)).first()!=null,"restart persistence");
            check(reopened.database().getCollection("players").deleteMany(new Document()).getDeletedCount()==2,"delete count");
        }
        try(java.util.stream.Stream<Path> paths=Files.walk(folder)) { paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}}); }
        Path importFolder=Files.createTempDirectory("palace-import-test");
        Path exports=Files.createDirectory(importFolder.resolve("exports"));
        Files.write(exports.resolve("players.json"),Arrays.asList(new Document("_id",id).append("uuid",uuid.toString()).toJson()));
        Path imported=importFolder.resolve("imported.sqlite");
        ImportMongoExport.importDirectory(imported,exports);
        try(SQLiteStore store=new SQLiteStore(imported.toFile())) {
            check(store.database().getCollection("players").find(Filters.eq("_id",id)).first().getString("uuid").equals(uuid.toString()),"import preserves ObjectId and player data");
        }
        try { ImportMongoExport.importDirectory(imported,exports);throw new AssertionError("import overwrote destination"); }
        catch(java.io.IOException expected) { check(Files.exists(imported),"existing DB preserved"); }
        Files.write(exports.resolve("bad.json"),Arrays.asList("invalid JSON"));
        Path failed=importFolder.resolve("failed.sqlite");
        try { ImportMongoExport.importDirectory(failed,exports);throw new AssertionError("invalid export accepted"); }
        catch(java.io.IOException expected) { check(!Files.exists(failed),"failed import must not publish DB"); }
        try(java.util.stream.Stream<Path> paths=Files.walk(importFolder)) { paths.sorted(Comparator.reverseOrder()).forEach(p->{try{Files.delete(p);}catch(Exception e){throw new RuntimeException(e);}}); }
        System.out.println("SQLite integration tests passed");
    }
}
