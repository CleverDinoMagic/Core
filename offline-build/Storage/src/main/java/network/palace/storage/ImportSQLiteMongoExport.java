package network.palace.storage;

import org.bson.Document;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Offline import of mongoexport JSON-lines files into a new SQLite database. */
public final class ImportSQLiteMongoExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ImportSQLiteMongoExport <new.sqlite> <export-directory>; files must be <collection>.json or .jsonl (JSON lines)");
        importDirectory(Paths.get(args[0]), Paths.get(args[1]));
    }
    public static void importDirectory(Path destination, Path directory) throws Exception {
        destination = destination.toAbsolutePath();
        if (Files.exists(destination)) throw new IOException("Destination already exists; import into a new file: " + destination);
        List<Path> exports = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).filter(p -> p.toString().endsWith(".jsonl") || p.toString().endsWith(".json") && !p.toString().endsWith(".metadata.json")).sorted().forEach(exports::add);
        }
        if (exports.isEmpty()) throw new IOException("No JSON-lines exports found in " + directory);
        Files.createDirectories(destination.getParent());
        Path stage = Files.createTempDirectory(destination.getParent(), "palace-import-");
        Path temporary = stage.resolve("database.sqlite");
        try {
            try (SQLiteStore store = new SQLiteStore(temporary.toFile())) {
                for (Path file : exports) {
                    String name = file.getFileName().toString().replaceFirst("\\.(jsonl|json)$", "");
                    long count = 0;
                    try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.trim().isEmpty()) continue;
                            try { store.database().getCollection(name).insertOne(Document.parse(line)); }
                            catch (RuntimeException e) { throw new IOException("Invalid document in " + file + " at document " + (count + 1), e); }
                            count++;
                        }
                    }
                    System.out.println(name + ": " + count + " documents");
                }
            }
            // Same filesystem, no replacement: a failed import never changes the live DB.
            Files.move(temporary, destination);
            System.out.println("Imported SQLite database: " + destination);
        } finally {
            try (java.util.stream.Stream<Path> files = Files.walk(stage)) {
                for (Path file : (Iterable<Path>) files.sorted(Comparator.reverseOrder())::iterator) Files.deleteIfExists(file);
            }
        }
    }
}
