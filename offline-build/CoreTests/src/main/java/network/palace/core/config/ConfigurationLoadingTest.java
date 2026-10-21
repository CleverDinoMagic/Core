package network.palace.core.config;

import org.bukkit.configuration.file.FileConfiguration;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.io.InputStream;

public final class ConfigurationLoadingTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("core-config-test");
        Path file = directory.resolve("config.yml");
        try {
            Files.write(file, "server-type: Legacy\nrabbitmq:\n  host: operator-host\n  username: operator-user\n  password: operator-password\n".getBytes(StandardCharsets.UTF_8));
            byte[] before = Files.readAllBytes(file);
            FileConfiguration config = YAMLConfigurationFile.loadConfiguration(file.toFile(), defaults());
            check(config.getString("rabbitmq.host").equals("operator-host"), "Operator settings preserved");
            check(config.getInt("rabbitmq.port") == 5672 && config.getString("rabbitmq.virtualhost").equals("/"), "JAR supplies non-secret defaults");
            check(config.getString("server-type").equals("Legacy"), "Existing server config preserved");
            check(java.util.Arrays.equals(before, Files.readAllBytes(file)), "Default loading must not overwrite disk config");
            Files.write(file, "server-type: Legacy\n".getBytes(StandardCharsets.UTF_8));
            config = YAMLConfigurationFile.loadConfiguration(file.toFile(), defaults());
            check(config.getConfigurationSection("rabbitmq") != null, "Legacy file receives JAR defaults");
            check(config.getString("rabbitmq.username").isEmpty() && config.getString("rabbitmq.password").isEmpty(), "No broker credentials invented");
            FileConfiguration fresh = YAMLConfigurationFile.loadConfiguration(directory.resolve("new.yml").toFile(), defaults());
            check(fresh.getString("rabbitmq.host").isEmpty() && fresh.getInt("rabbitmq.port") == 5672, "Fresh config resource has expected defaults");
        } finally { Files.deleteIfExists(file); Files.delete(directory); }
        System.out.println("Bundled configuration defaults tests passed");
    }
    private static InputStream defaults() {
        InputStream input = ConfigurationLoadingTest.class.getClassLoader().getResourceAsStream("config.yml");
        if (input == null) throw new AssertionError("Missing bundled Core config.yml");
        return input;
    }
}
