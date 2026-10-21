package network.palace.audio.handlers;
import java.util.List;
import org.bukkit.Location;
import org.bukkit.World;
import network.palace.core.player.CPlayer;
/** Compile-only API contract; never packaged in a plugin JAR. */
public class AudioArea {
    public AudioArea(String name,String path,int fade,double volume,List<String> regions,boolean loop,boolean enabled,World world) { throw new UnsupportedOperationException(); }
    public AudioArea(String name,String path,int fade,double volume,Location min,Location max,boolean loop,boolean enabled,World world) { throw new UnsupportedOperationException(); }
    public String getAreaName() { throw new UnsupportedOperationException(); }
    public String getAudioid() { throw new UnsupportedOperationException(); }
    public String getPath() { throw new UnsupportedOperationException(); }
    public String getRegionNames() { throw new UnsupportedOperationException(); }
    public World getWorld() { throw new UnsupportedOperationException(); }
    public boolean locIsInArea(Location location) { throw new UnsupportedOperationException(); }
    public void triggerPlayer(CPlayer player) { throw new UnsupportedOperationException(); }
    public void addPlayer(CPlayer player) { throw new UnsupportedOperationException(); }
    public void addPlayerIfInside(CPlayer player) { throw new UnsupportedOperationException(); }
    public void removeAllPlayers(boolean stop) { throw new UnsupportedOperationException(); }
    public void setEnabled(boolean enabled) { throw new UnsupportedOperationException(); }
    public void sync(double time,CPlayer player) { throw new UnsupportedOperationException(); }
    public void sync(double time,CPlayer player,double fade) { throw new UnsupportedOperationException(); }
}
