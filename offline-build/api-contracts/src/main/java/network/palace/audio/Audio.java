package network.palace.audio;
import java.util.List;
import network.palace.audio.handlers.AudioArea;
/** Compile-only API contract. The Audio plugin provides the implementation. */
public class Audio {
    public static Audio getInstance() { throw new UnsupportedOperationException("Audio plugin required"); }
    public AudioArea getByName(String name) { throw new UnsupportedOperationException(); }
    public List<AudioArea> getAudioAreas() { throw new UnsupportedOperationException(); }
    public void addArea(AudioArea area) { throw new UnsupportedOperationException(); }
    public void removeArea(AudioArea area) { throw new UnsupportedOperationException(); }
}
