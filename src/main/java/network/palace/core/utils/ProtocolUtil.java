package network.palace.core.utils;

import com.comphenix.protocol.ProtocolLibrary;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;

public class ProtocolUtil {

    public static int getProtocolVersion(Player player) {
        if (Bukkit.getPluginManager().getPlugin("ViaVersion") != null) {
            try {
                Class<?> via;
                try { via = Class.forName("com.viaversion.viaversion.api.Via"); }
                catch (ClassNotFoundException legacy) { via = Class.forName("us.myles.ViaVersion.api.Via"); }
                Object api = via.getMethod("getAPI").invoke(null);
                return ((Number) api.getClass().getMethod("getPlayerVersion", java.util.UUID.class)
                        .invoke(api, player.getUniqueId())).intValue();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException("Cannot read ViaVersion protocol", e);
            }
        } else {
            return ProtocolLibrary.getProtocolManager().getProtocolVersion(player);
        }
    }
}
