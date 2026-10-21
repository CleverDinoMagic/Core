package network.palace.cosmetics;
import network.palace.core.player.CPlayer;
import network.palace.cosmetics.utils.InventoryUtil;
/** Compile-only API contract. Runtime implementation belongs to Cosmetics. */
public class Cosmetics {
    public static Cosmetics getInstance() { throw new UnsupportedOperationException("Cosmetics plugin required"); }
    public static InventoryUtil getInventoryUtil() { throw new UnsupportedOperationException(); }
    public void openCosmeticsInventory(CPlayer player) { throw new UnsupportedOperationException(); }
}
