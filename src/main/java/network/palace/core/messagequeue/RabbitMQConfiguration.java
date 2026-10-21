package network.palace.core.messagequeue;

import com.rabbitmq.client.ConnectionFactory;
import org.bukkit.configuration.ConfigurationSection;

import java.util.ArrayList;
import java.util.List;

/** Validates operator-supplied broker settings without connecting or logging secrets. */
final class RabbitMQConfiguration {
    private RabbitMQConfiguration() { }

    static void configure(ConfigurationSection config, ConnectionFactory factory) {
        ConfigurationSection section = config.getConfigurationSection("rabbitmq");
        if (section == null) {
            throw invalid("Missing or invalid 'rabbitmq' configuration section");
        }
        List<String> errors = new ArrayList<>();
        String host = required(section, "host", errors);
        String username = required(section, "username", errors);
        String password = required(section, "password", errors);
        Object virtualHostValue = section.get("virtualhost", "/");
        String virtualHost = virtualHostValue instanceof String ? (String) virtualHostValue : null;
        if (virtualHost == null || virtualHost.trim().isEmpty()) errors.add("rabbitmq.virtualhost must be a non-blank string");
        Object portValue = section.get("port", 5672);
        int port = portValue instanceof Integer ? (Integer) portValue : -1;
        if (port < 1 || port > 65535) errors.add("rabbitmq.port must be an integer between 1 and 65535");
        if (!errors.isEmpty()) throw invalid(String.join("; ", errors));

        factory.setHost(host);
        factory.setPort(port);
        factory.setVirtualHost(virtualHost);
        factory.setUsername(username);
        factory.setPassword(password);
    }

    private static String required(ConfigurationSection section, String key, List<String> errors) {
        Object value = section.get(key);
        if (!(value instanceof String) || ((String) value).trim().isEmpty()) {
            errors.add("rabbitmq." + key + " must be an explicitly configured, non-blank string");
            return null;
        }
        // Do not trim passwords or other credentials: their exact bytes matter.
        return (String) value;
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason + ". Edit plugins/Core/config.yml and configure rabbitmq.host, "
                + "rabbitmq.username and rabbitmq.password for your existing RabbitMQ broker; verify rabbitmq.port "
                + "and rabbitmq.virtualhost. Messaging is required; no broker credentials are supplied by Core.");
    }
}
