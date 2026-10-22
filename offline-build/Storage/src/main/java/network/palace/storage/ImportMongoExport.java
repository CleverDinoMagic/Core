package network.palace.storage;

import org.bson.Document;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Atomic MongoDB JSON-lines import into an empty MySQL document store. */
public final class ImportMongoExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 2) throw new IllegalArgumentException("Usage: ImportMongoExport <mysql.properties> <export-directory>");
        try (MySQLStore store = new MySQLStore(MySQLSettings.fromProperties(Paths.get(args[0])))) {
            importDirectory(store, Paths.get(args[1]));
        }
    }
    public static void importDirectory(DocumentStore store, Path directory) throws Exception {
        List<Path> exports = new ArrayList<>();
        try (java.util.stream.Stream<Path> files = Files.list(directory)) {
            files.filter(Files::isRegularFile).filter(p -> (p.toString().endsWith(".jsonl") || p.toString().endsWith(".json"))
                    && !p.toString().endsWith(".metadata.json")).sorted().forEach(exports::add);
        }
        if (exports.isEmpty()) throw new IOException("No JSON-lines exports found in " + directory);
        store.importDocuments(sink -> {
            for (Path file : exports) {
                String name = file.getFileName().toString().replaceFirst("\\.(jsonl|json)$", "");
                long count = 0;
                try (BufferedReader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.trim().isEmpty()) continue;
                        try { sink.insert(name, Document.parse(line)); }
                        catch (Exception e) { throw new IOException("Invalid document in " + file + " at document " + (count + 1), e); }
                        count++;
                    }
                }
                System.out.println(name + ": " + count + " documents staged");
            }
        });
        System.out.println("MySQL import committed");
    }
}
