package network.palace.core.messagequeue;

import com.rabbitmq.client.*;
import org.bukkit.configuration.file.YamlConfiguration;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.*;

/** Exercises the real RabbitMQ factory settings and protocol setup with an in-memory transport. */
public final class MessageHandlerTest {
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static YamlConfiguration valid() {
        YamlConfiguration config = new YamlConfiguration();
        config.set("rabbitmq.host", "broker.example.invalid");
        config.set("rabbitmq.username", "test-user");
        config.set("rabbitmq.password", " test-password ");
        config.set("rabbitmq.virtualhost", "/network");
        config.set("rabbitmq.port", 5673);
        return config;
    }
    private static void invalid(YamlConfiguration config, String expected) throws Exception {
        FakeFactory factory = new FakeFactory();
        try { new MessageHandler(config, factory); throw new AssertionError("Invalid settings accepted"); }
        catch (IllegalArgumentException e) {
            check(e.getMessage().contains(expected), "Diagnostic must name " + expected);
            check(e.getMessage().contains("plugins/Core/config.yml"), "Diagnostic must name config path");
            check(!e.getMessage().contains("test-password"), "Diagnostic must not expose password");
            check(!e.getMessage().contains("test-user"), "Diagnostic must not expose username");
        }
        check(factory.calls == 0, "Validation must happen before connections");
    }
    public static void main(String[] args) throws Exception {
        invalid(new YamlConfiguration(), "'rabbitmq'");
        YamlConfiguration config = valid(); config.set("rabbitmq", "not-a-section"); invalid(config, "'rabbitmq'");
        for (String key : Arrays.asList("host", "username", "password")) {
            for (Object bad : Arrays.asList("", "  ", 123)) {
                config = valid(); config.set("rabbitmq." + key, bad); invalid(config, "rabbitmq." + key);
            }
            config = valid(); config.set("rabbitmq." + key, null); invalid(config, "rabbitmq." + key);
        }
        for (Object bad : Arrays.asList(0, 65536, "5672", 5672.5)) {
            config = valid(); config.set("rabbitmq.port", bad); invalid(config, "rabbitmq.port");
        }
        config = valid(); config.set("rabbitmq.virtualhost", ""); invalid(config, "rabbitmq.virtualhost");
        config = valid(); config.set("rabbitmq.virtualhost", 12); invalid(config, "rabbitmq.virtualhost");
        FakeFactory factory = new FakeFactory();
        MessageHandler handler = new MessageHandler(valid(), factory);
        check(factory.calls == 2, "Publishing and consuming connections required");
        check(factory.getHost().equals("broker.example.invalid") && factory.getPort() == 5673, "Configured endpoint applied");
        check(factory.getUsername().equals("test-user") && factory.getPassword().equals(" test-password "), "Credentials passed unchanged");
        check(factory.getVirtualHost().equals("/network"), "Configured vhost applied");
        handler.initialize("PaperTest");
        check(handler.ALL_MC != null && handler.BOT != null && handler.MC_DIRECT != null, "Essential clients initialise");
        check(factory.exchanges.containsAll(Arrays.asList("all_proxies", "all_mc", "proxy_direct", "mc_direct")), "Required exchanges declared");
        check(factory.durableQueues.containsAll(Arrays.asList("statistics", "bot-networking")), "Durable queues declared");
        check(factory.bindings.contains("mc_direct:PaperTest") && factory.bindings.contains("all_mc:"), "Consumer bindings use instance routing key");
        check(factory.consumers == 2, "Both consumers registered");
        handler.shutdown();
        handler.shutdown();
        check(factory.closed == 2 && factory.calls == 2, "Shutdown closes both connections without reconnecting");

        config = valid(); config.set("rabbitmq.port", null); config.set("rabbitmq.virtualhost", null);
        factory = new FakeFactory(); handler = new MessageHandler(config, factory);
        check(factory.getPort() == 5672 && factory.getVirtualHost().equals("/"), "Legacy config gets standard non-secret defaults");
        handler.shutdown();

        factory = new FakeFactory(); factory.failConnection = 2;
        try { new MessageHandler(valid(), factory); throw new AssertionError("Connection failure swallowed"); }
        catch (IOException expected) { check(factory.closed == 1, "Partial startup closes publishing connection"); }
        factory = new FakeFactory(); factory.failDeclarations = true; handler = new MessageHandler(valid(), factory);
        try { handler.initialize("PaperTest"); throw new AssertionError("Queue declaration failure swallowed"); }
        catch (IOException expected) { check(expected.getMessage().contains("required RabbitMQ"), "Queue failure diagnostic"); }
        finally { handler.shutdown(); }
        System.out.println("MessageHandler configuration and initialisation tests passed");
    }

    private static final class FakeFactory extends ConnectionFactory {
        int calls, closed, consumers, generatedQueues, failConnection;
        boolean failDeclarations;
        final List<String> exchanges = new ArrayList<>(), durableQueues = new ArrayList<>(), bindings = new ArrayList<>();
        @Override public Connection newConnection() throws IOException {
            if (++calls == failConnection) throw new IOException("Broker connection rejected");
            final boolean[] open = {true};
            List<ShutdownListener> listeners = new ArrayList<>();
            return (Connection) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Connection.class}, (p,m,a) -> {
                switch (m.getName()) {
                    case "createChannel": return channel((Connection)p);
                    case "addShutdownListener": listeners.add((ShutdownListener)a[0]); return null;
                    case "isOpen": return open[0];
                    case "close": if (open[0]) { open[0] = false; closed++; for (ShutdownListener listener : listeners) listener.shutdownCompleted(new ShutdownSignalException(true, true, null, p)); } return null;
                    default: throw new UnsupportedOperationException(m.toString());
                }
            });
        }
        private Channel channel(Connection connection) {
            return (Channel) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[]{Channel.class}, (p,m,a) -> {
                switch (m.getName()) {
                    case "exchangeDeclare": if (failDeclarations) throw new IOException("Permission denied"); exchanges.add((String)a[0]); return null;
                    case "queueDeclare":
                        if (a != null && a.length > 0) { durableQueues.add((String)a[0]); return null; }
                        return new AMQP.Queue.DeclareOk.Builder().queue("generated-" + ++generatedQueues).messageCount(0).consumerCount(0).build();
                    case "queueBind": bindings.add(a[1] + ":" + a[2]); return null;
                    case "basicConsume": consumers++; return "consumer-" + consumers;
                    case "getConnection": return connection;
                    case "close": return null;
                    default: throw new UnsupportedOperationException(m.toString());
                }
            });
        }
    }
}
