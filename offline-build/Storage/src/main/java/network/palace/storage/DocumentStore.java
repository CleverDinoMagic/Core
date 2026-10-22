package network.palace.storage;

import com.mongodb.Block;
import com.mongodb.MongoClient;
import com.mongodb.client.*;
import com.mongodb.client.model.UpdateOptions;
import com.mongodb.client.result.DeleteResult;
import com.mongodb.client.result.UpdateResult;
import org.bson.*;
import org.bson.conversions.Bson;
import org.bson.types.ObjectId;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.lang.reflect.*;
import java.sql.*;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/** SQL persistence for the document operations used by Palace plugins.
 * Mongo interfaces remain as a source compatibility API; no Mongo server is used.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
public class DocumentStore implements AutoCloseable {
    private final HikariDataSource pool;
    private final MongoDatabase database;
    private final boolean mysql;

    protected DocumentStore(HikariConfig config, String name, boolean mysql) {
        this.mysql = mysql;
        pool = new HikariDataSource(config);
        try (Connection connection = pool.getConnection(); Statement statement = connection.createStatement()) {
            if (mysql) {
                statement.execute("CREATE TABLE IF NOT EXISTS palace_documents (collection VARBINARY(255) NOT NULL, id BINARY(32) NOT NULL, document LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NOT NULL, sequence BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, PRIMARY KEY(collection,id), UNIQUE KEY(sequence)) ENGINE=InnoDB");
                statement.execute("CREATE TABLE IF NOT EXISTS palace_collection_locks (name VARBINARY(255) PRIMARY KEY) ENGINE=InnoDB");
            } else {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("CREATE TABLE IF NOT EXISTS palace_documents (collection TEXT NOT NULL, id TEXT NOT NULL, document TEXT NOT NULL, PRIMARY KEY(collection,id))");
            }
            database = proxy(MongoDatabase.class, (p,m,a) -> {
                switch (m.getName()) {
                    case "getCollection": return collection((String) a[0]);
                    case "getName": return name;
                    case "getCodecRegistry": return MongoClient.getDefaultCodecRegistry();
                    default: return objectMethod(p,m,a);
                }
            });
        } catch (Exception e) {
            pool.close();
            throw new IllegalStateException("Cannot initialize SQL document storage", e);
        }
    }

    public MongoDatabase database() { return database; }
    public void close() { pool.close(); }
    private static RuntimeException failure(Exception e) { return new IllegalStateException("SQL document persistence failed", e); }
    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(DocumentStore.class.getClassLoader(), new Class<?>[]{type}, handler));
    }
    private static Object objectMethod(Object p, Method m, Object[] a) {
        switch (m.getName()) {
            case "toString": return "SQL:" + p.getClass().getInterfaces()[0].getSimpleName();
            case "hashCode": return System.identityHashCode(p);
            case "equals": return p == a[0];
            default: throw new UnsupportedOperationException("Unsupported SQL document operation: " + m);
        }
    }
    private static Document bson(Object value) {
        if (value == null) return new Document();
        if (value instanceof Document) return (Document) normalize(value);
        if (value instanceof Bson) return (Document) normalize(Document.parse(((Bson) value).toBsonDocument(Document.class, MongoClient.getDefaultCodecRegistry()).toJson()));
        throw new IllegalArgumentException("Expected BSON: " + value);
    }
    private static Object normalize(Object value) {
        if (value instanceof UUID) return value.toString();
        if (value instanceof org.bson.types.Binary) {
            org.bson.types.Binary binary = (org.bson.types.Binary) value;
            if ((binary.getType() == 3 || binary.getType() == 4) && binary.getData().length == 16) {
                java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(binary.getData());
                long first = bytes.getLong(), second = bytes.getLong();
                if (binary.getType() == 3) { first = Long.reverseBytes(first); second = Long.reverseBytes(second); }
                return new UUID(first, second).toString();
            }
        }
        if (value instanceof BsonValue) return Document.parse(new BsonDocument("v", (BsonValue)value).toJson()).get("v");
        if (value instanceof Map) {
            Document result = new Document();
            ((Map<?,?>) value).forEach((k,v) -> result.put(k.toString(), normalize(v)));
            return result;
        }
        if (value instanceof Collection) {
            List<Object> result = new ArrayList<>();
            for (Object v : (Collection<?>) value) result.add(normalize(v));
            return result;
        }
        return value;
    }
    private List<Document> read(String name) {
        try (Connection connection = pool.getConnection()) {
            return read(connection, name);
        } catch (SQLException e) { throw failure(e); }
    }
    private List<Document> read(Connection connection, String name) throws SQLException {
        List<Document> result = new ArrayList<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT document FROM palace_documents WHERE collection=? ORDER BY " + (mysql ? "sequence" : "rowid"))) {
            statement.setString(1,name);
            try (ResultSet rows=statement.executeQuery()) { while(rows.next()) result.add(Document.parse(rows.getString(1))); }
            return result;
        } catch(SQLException e) { throw failure(e); }
    }
    private void write(Connection connection, String name, Document document, boolean insert) throws SQLException {
        if (!document.containsKey("_id")) document.put("_id",new ObjectId());
        try (PreparedStatement statement = connection.prepareStatement(insert
                ? "INSERT INTO palace_documents(collection,id,document) VALUES(?,?,?)"
                : "UPDATE palace_documents SET document=? WHERE collection=? AND id=?")) {
            if (insert) { statement.setString(1,name);setId(statement,2,document);statement.setString(3,document.toJson()); }
            else { statement.setString(1,document.toJson());statement.setString(2,name);setId(statement,3,document); }
            statement.executeUpdate();
        }
    }
    private Object mutate(String name,String operation,Object[] args) {
        try (Connection connection = pool.getConnection(); Statement transaction=connection.createStatement()) {
            begin(connection, transaction);
            try {
                lockCollection(connection, name);
            } catch (Exception e) {
                rollback(connection, transaction, e);
                throw e;
            }
            try {
                Object result=null;
                if (operation.equals("insertOne") || operation.equals("insertMany")) {
                    Collection<?> documents=operation.equals("insertOne")?Collections.singletonList(args[0]):(Collection<?>)args[0];
                    for(Object document:documents) write(connection,name,bson(document),true);
                } else {
                    Document filter=bson(args[0]); long matched=0,modified=0;
                    boolean many=operation.endsWith("Many");
                    for(Document document:read(connection,name)) {
                        if(!matches(document,filter)) continue;
                        matched++;
                        if(operation.startsWith("delete")) {
                            try(PreparedStatement statement=connection.prepareStatement("DELETE FROM palace_documents WHERE collection=? AND id=?")) {
                                statement.setString(1,name);setId(statement,2,document);statement.executeUpdate();
                            }
                            modified++;
                        } else {
                            String before=document.toJson(); apply(document,bson(args[1]),filter);
                            if(!before.equals(document.toJson())) { write(connection,name,document,false);modified++; }
                        }
                        if(!many)break;
                    }
                    BsonValue upsertId=null;
                    if(matched==0 && operation.startsWith("update") && args.length>2 && ((UpdateOptions)args[2]).isUpsert()) {
                        Document document=new Document();
                        filter.forEach((k,v)-> { if(!k.startsWith("$") && !(v instanceof Map)) set(document,k,v,false); });
                        apply(document,bson(args[1]),filter);write(connection,name,document,true);
                        upsertId=bson(new Document("v",document.get("_id"))).toBsonDocument(Document.class,MongoClient.getDefaultCodecRegistry()).get("v");
                    }
                    result=operation.startsWith("delete")?DeleteResult.acknowledged(modified):UpdateResult.acknowledged(matched,modified,upsertId);
                }
                commit(connection, transaction);return result;
            } catch(Exception e) { rollback(connection, transaction, e);throw e; }
        } catch(Exception e) { throw failure(e); }
    }
    private void setId(PreparedStatement statement, int index, Document document) throws SQLException {
        String id = new Document("id", document.get("_id")).toJson();
        if (mysql) {
            try { statement.setBytes(index, java.security.MessageDigest.getInstance("SHA-256").digest(id.getBytes(java.nio.charset.StandardCharsets.UTF_8))); }
            catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        } else statement.setString(index, id);
    }
    private void begin(Connection connection, Statement transaction) throws SQLException {
        if (mysql) connection.setAutoCommit(false);
        else transaction.execute("BEGIN IMMEDIATE");
    }
    private void commit(Connection connection, Statement transaction) throws SQLException {
        if (mysql) connection.commit();
        else transaction.execute("COMMIT");
    }
    private void rollback(Connection connection, Statement transaction, Exception original) {
        try {
            if (mysql) connection.rollback();
            else transaction.execute("ROLLBACK");
        } catch (SQLException cleanup) { original.addSuppressed(cleanup); }
    }
    private void lockCollection(Connection connection, String name) throws SQLException {
        if (!mysql) return;
        try (PreparedStatement insert = connection.prepareStatement("INSERT INTO palace_collection_locks(name) VALUES(?) ON DUPLICATE KEY UPDATE name=name")) {
            insert.setString(1, name); insert.executeUpdate();
        }
        try (PreparedStatement lock = connection.prepareStatement("SELECT name FROM palace_collection_locks WHERE name=? FOR UPDATE")) {
            lock.setString(1, name);
            try (ResultSet ignored = lock.executeQuery()) { while (ignored.next()) { } }
        }
    }

    @FunctionalInterface public interface DocumentSink { void insert(String collection, Document document) throws Exception; }
    @FunctionalInterface public interface ImportTask { void run(DocumentSink sink) throws Exception; }

    /** All-or-nothing import into an empty document store. Stop application writers first. */
    public void importDocuments(ImportTask task) throws Exception {
        try (Connection connection = pool.getConnection(); Statement transaction = connection.createStatement()) {
            begin(connection, transaction);
            try {
                try (ResultSet rows = transaction.executeQuery("SELECT id FROM palace_documents" + (mysql ? " FOR UPDATE" : ""))) {
                    if (rows.next()) throw new IllegalStateException("Import requires an empty document store");
                }
                task.run((collection, document) -> write(connection, collection, bson(document), true));
                commit(connection, transaction);
            } catch (Exception e) { rollback(connection, transaction, e); throw e; }
        }
    }
    private MongoCollection<Document> collection(String name) {
        return proxy(MongoCollection.class,(p,m,a)-> {
            switch(m.getName()) {
                case "find": return iterable(FindIterable.class,read(name), a==null || a.length==0?new Document():bson(a[0]));
                case "aggregate": return iterable(AggregateIterable.class,aggregate(read(name),(List<?>)a[0]),new Document());
                case "count": return read(name).stream().filter(d->matches(d,a==null || a.length==0?new Document():bson(a[0]))).count();
                case "insertOne": case "insertMany": case "updateOne": case "updateMany": case "deleteOne": case "deleteMany": return mutate(name,m.getName(),a);
                case "getDocumentClass": return Document.class;
                case "getCodecRegistry": return MongoClient.getDefaultCodecRegistry();
                default: return objectMethod(p,m,a);
            }
        });
    }
    private Object iterable(Class<?> type,List<Document> source,Document filter) {
        InvocationHandler handler=new InvocationHandler() {
            Document sort=new Document(),projection=new Document();int limit=0,skip=0;
            List<Document> rows() {
                List<Document> result=new ArrayList<>();for(Document d:source)if(matches(d,filter))result.add(d);
                result.sort(comparator(sort));
                int from=Math.min(skip,result.size()),to=limit==0?result.size():Math.min(result.size(),from+Math.abs(limit));
                List<Document> selected=new ArrayList<>();for(Document d:result.subList(from,to))selected.add(project(d,projection));return selected;
            }
            public Object invoke(Object p,Method m,Object[] a) {
                switch(m.getName()) {
                    case "sort": sort=bson(a[0]);return p;
                    case "projection":projection=bson(a[0]);return p;
                    case "limit":limit=(Integer)a[0];return p;
                    case "skip":skip=(Integer)a[0];return p;
                    case "batchSize":return p;
                    case "first":List<Document> r=rows();return r.isEmpty()?null:r.get(0);
                    case "into":((Collection)a[0]).addAll(rows());return a[0];
                    case "forEach":for(Document d:rows()) {if(a[0] instanceof Block)((Block)a[0]).apply(d);else ((Consumer)a[0]).accept(d);}return null;
                    case "iterator":return cursor(rows());
                    case "map":return mapped(rows(),(com.mongodb.Function)a[0]);
                    default:return objectMethod(p,m,a);
                }
            }
        };
        return proxy(type,handler);
    }
    private static Object cursor(List<?> rows) {
        Iterator<?> iterator=rows.iterator();
        return proxy(MongoCursor.class,(p,m,a)-> {
            switch(m.getName()) {
                case "hasNext":return iterator.hasNext();
                case "next":return iterator.next();
                case "tryNext":return iterator.hasNext()?iterator.next():null;
                case "close":return null;
                default:return objectMethod(p,m,a);
            }
        });
    }
    private static Object mapped(List<Document> rows, com.mongodb.Function function) {
        List<Object> mapped=new ArrayList<>();for(Document d:rows)mapped.add(function.apply(d));
        return proxy(MongoIterable.class,(p,m,a)-> {
            switch(m.getName()) {
                case "iterator":return cursor(mapped);
                case "first":return mapped.isEmpty()?null:mapped.get(0);
                case "into":((Collection)a[0]).addAll(mapped);return a[0];
                case "forEach":for(Object d:mapped) {if(a[0] instanceof Block)((Block)a[0]).apply(d);else ((Consumer)a[0]).accept(d);}return null;
                default:return objectMethod(p,m,a);
            }
        });
    }
    private static Object get(Object value,String path) {
        if(path.isEmpty())return value;
        String[] parts=path.split("\\.",2);String rest=parts.length==1?"":parts[1];
        if(value instanceof Map)return get(((Map)value).get(parts[0]),rest);
        if(value instanceof List) {
            try {return get(((List)value).get(Integer.parseInt(parts[0])),rest);}catch(NumberFormatException e) {
                List<Object> result=new ArrayList<>();for(Object item:(List)value) { Object v=get(item,path);if(v instanceof List)result.addAll((List)v);else result.add(v); }return result;
            } catch(IndexOutOfBoundsException e) {return null;}
        }
        return null;
    }
    private static boolean exists(Object value, String path) {
        String[] parts = path.split("\\.", 2);
        if (value instanceof Map) {
            Map<?,?> map = (Map<?,?>) value;
            return map.containsKey(parts[0]) && (parts.length == 1 || exists(map.get(parts[0]), parts[1]));
        }
        if (value instanceof List) {
            List<?> list = (List<?>) value;
            try { int index = Integer.parseInt(parts[0]); return index >= 0 && index < list.size() && (parts.length == 1 || exists(list.get(index), parts[1])); }
            catch (NumberFormatException e) { return list.stream().anyMatch(item -> exists(item, path)); }
        }
        return false;
    }
    private static boolean equal(Object actual,Object expected) {
        if(actual instanceof Collection && !(expected instanceof Collection))return ((Collection<?>)actual).stream().anyMatch(v->equal(v,expected));
        if(actual instanceof Number && expected instanceof Number)return Double.compare(((Number)actual).doubleValue(),((Number)expected).doubleValue())==0;
        if(expected instanceof Pattern)return actual instanceof String && ((Pattern)expected).matcher((String)actual).find();
        return Objects.equals(actual,expected);
    }
    private static int compare(Object a,Object b) {
        if(a==null)return b==null?0:-1;if(b==null)return 1;
        if(a instanceof Number && b instanceof Number)return Double.compare(((Number)a).doubleValue(),((Number)b).doubleValue());
        return ((Comparable)a).compareTo(b);
    }
    private static boolean matches(Object actual,Document filter) {
        for(Map.Entry<String,Object> entry:filter.entrySet()) {
            String key=entry.getKey();Object expected=entry.getValue();
            if(key.equals("$and")) {for(Object f:(List<?>)expected)if(!matches(actual,bson(f)))return false;continue;}
            if(key.equals("$or")) {boolean found=false;for(Object f:(List<?>)expected)found|=matches(actual,bson(f));if(!found)return false;continue;}
            Object value=key.startsWith("$")?actual:get(actual,key);
            if (!key.startsWith("$") && expected instanceof Document && ((Document) expected).containsKey("$exists")) {
                Document operators = new Document((Document) expected);
                boolean required = operators.getBoolean("$exists"); operators.remove("$exists");
                if (exists(actual, key) != required || !matches(value, operators)) return false;
                continue;
            }
            if(!key.startsWith("$")) {if(expected instanceof Document && ((Document)expected).keySet().stream().anyMatch(k->k.startsWith("$"))) {if(!matches(value,(Document)expected))return false;}else if(!equal(value,expected))return false;continue;}
            switch(key) {
                case "$eq":if(!equal(value,expected))return false;break;
                case "$ne":if(equal(value,expected))return false;break;
                case "$exists":if((value!=null)!=(Boolean)expected)return false;break;
                case "$gte":if(value==null || compare(value,expected)<0)return false;break;
                case "$gt":if(value==null || compare(value,expected)<=0)return false;break;
                case "$lte":if(value==null || compare(value,expected)>0)return false;break;
                case "$lt":if(value==null || compare(value,expected)>=0)return false;break;
                case "$in":if(((List<?>)expected).stream().noneMatch(v->equal(value,v)))return false;break;
                case "$elemMatch":if(!(value instanceof List) || ((List<?>)value).stream().noneMatch(v->matches(v,bson(expected))))return false;break;
                default:throw new UnsupportedOperationException("Unsupported SQLite filter "+key);
            }
        }
        return true;
    }
    private static void set(Document document,String path,Object value,boolean remove) {
        String[] parts=path.split("\\.");Map map=document;
        for(int i=0;i<parts.length-1;i++) {
            Object next=map.get(parts[i]);
            if(next instanceof List) {
                List list=(List)next;int index=Integer.parseInt(parts[++i]);
                if(i==parts.length-1) {if(remove)list.set(index,null);else list.set(index,value);return;}
                map=(Map)list.get(index);
            } else {if(!(next instanceof Map)){next=new Document();map.put(parts[i],next);}map=(Map)next;}
        }
        if(remove)map.remove(parts[parts.length-1]);else map.put(parts[parts.length-1],normalize(value));
    }
    private static String positional(Document document,String path,Document filter) {
        int pos=path.indexOf(".$.");if(pos<0)return path;
        String array=path.substring(0,pos);List<?> values=(List<?>)get(document,array);
        Document elementFilter=new Document();
        for (Map.Entry<String,Object> e : filter.entrySet()) if(e.getKey().startsWith(array+".")) elementFilter.put(e.getKey().substring(array.length()+1),e.getValue());
        Object direct=filter.get(array);if(direct instanceof Document && ((Document)direct).containsKey("$elemMatch"))elementFilter=bson(((Document)direct).get("$elemMatch"));
        if(elementFilter.isEmpty())throw new IllegalArgumentException("Positional update requires array filter: "+path);
        for(int i=0;i<values.size();i++)if(matches(values.get(i),elementFilter))return array+"."+i+path.substring(pos+2);
        throw new IllegalArgumentException("No matching array element: "+path);
    }
    private static void apply(Document document,Document update,Document filter) {
        for(Map.Entry<String,Object> op:update.entrySet()) {
            for(Map.Entry<String,Object> field:bson(op.getValue()).entrySet()) {
                String path=positional(document,field.getKey(),filter);Object value=field.getValue(),old=get(document,path);
                switch(op.getKey()) {
                    case "$set":set(document,path,value,false);break;
                    case "$unset":set(document,path,null,true);break;
                    case "$inc":Number n=(Number)value;Number previous=old==null?0:(Number)old;
                        Object sum=(previous instanceof Double || n instanceof Double)?(Object)(previous.doubleValue()+n.doubleValue()):(previous instanceof Long || n instanceof Long)?(Object)(previous.longValue()+n.longValue()):(Object)(previous.intValue()+n.intValue());
                        set(document,path,sum,false);break;
                    case "$push":case "$addToSet":case "$pull":
                        List<Object> list=old==null?new ArrayList<>():new ArrayList<>((List<?>)old);
                        if(op.getKey().equals("$pull"))list.removeIf(v->value instanceof Document?matches(v,(Document)value):equal(v,value));
                        else {List<?> additions=value instanceof Document && ((Document)value).containsKey("$each")?(List<?>)((Document)value).get("$each"):Collections.singletonList(value);for(Object v:additions)if(op.getKey().equals("$push") || !list.contains(v))list.add(v);}
                        set(document,path,list,false);break;
                    default:throw new UnsupportedOperationException("Unsupported SQLite update "+op.getKey());
                }
            }
        }
    }
    private static Comparator<Document> comparator(Document sort) {
        return (a,b)-> {for(Map.Entry<String,Object> e:sort.entrySet()){int c=compare(get(a,e.getKey()),get(b,e.getKey()));if(c!=0)return ((Number)e.getValue()).intValue()<0?-c:c;}return 0;};
    }
    private static boolean included(Object v) {return Boolean.TRUE.equals(v) || v instanceof Number && ((Number)v).intValue()!=0;}
    private static Document project(Document document,Document projection) {
        if(projection.isEmpty())return document;
        boolean inclusion=projection.entrySet().stream().anyMatch(e->!e.getKey().equals("_id") && included(e.getValue())) || projection.size()==1 && included(projection.get("_id"));
        Document result=inclusion?new Document():Document.parse(document.toJson());
        projection.forEach((k,v)-> {if(inclusion && included(v)) {Object value=get(document,k);if(value!=null)set(result,k,value,false);}else if(!included(v))set(result,k,null,true);});
        if(inclusion && !projection.containsKey("_id") && document.containsKey("_id"))result.put("_id",document.get("_id"));
        return result;
    }
    private static List<Document> aggregate(List<Document> rows,List<?> pipeline) {
        for(Object stage:pipeline) {
            Document d=bson(stage);
            if(d.containsKey("$match")) {Document filter=bson(d.get("$match"));rows.removeIf(v->!matches(v,filter));}
            else if(d.containsKey("$sort"))rows.sort(comparator(bson(d.get("$sort"))));
            else if(d.containsKey("$limit"))rows=new ArrayList<>(rows.subList(0,Math.min(rows.size(),((Number)d.get("$limit")).intValue())));
            else if(d.containsKey("$group")) {
                Document group=bson(d.get("$group"));Map<Object,Document> groups=new LinkedHashMap<>();
                for(Document row:rows) {
                    Object id=group.get("_id");if(id instanceof String && ((String)id).startsWith("$"))id=get(row,((String)id).substring(1));
                    Document accumulator=groups.computeIfAbsent(id,k->new Document("_id",k));
                    for(String key:group.keySet())if(!key.equals("_id")) {
                        Document expression=bson(group.get(key));if(!expression.containsKey("$sum"))throw new UnsupportedOperationException("Only $sum aggregation supported");
                        Number n=(Number)expression.get("$sum");accumulator.put(key,accumulator.getInteger(key,0)+n.intValue());
                    }
                }
                rows=new ArrayList<>(groups.values());
            } else throw new UnsupportedOperationException("Unsupported SQLite aggregation "+d);
        }
        return rows;
    }
}
