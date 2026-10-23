import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/** ~/.config/team-clocks/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml. */
public class Config {

    static final Path DIR = Path.of(System.getProperty("user.home"), ".config", "team-clocks");
    static final Path FILE = DIR.resolve("config");

    /** As written in the settings: the tab compares them with what was matched, to know when to match again. */
    String cities;
    String home;
    int workStart;
    int workEnd;
    boolean hour24;

    static Config load() throws IOException {
        Map<String, String> raw = new HashMap<>();
        if (Files.exists(FILE)) {
            for (String line : Files.readAllLines(FILE)) {
                line = line.strip();
                int eq = line.indexOf('=');
                if (!line.startsWith("#") && eq > 0) raw.putIfAbsent(line.substring(0, eq).strip(), line.substring(eq + 1).strip());
            }
        }
        Config c = new Config();
        c.cities = raw.getOrDefault("CITIES", "London, New York, Tokyo");
        c.home = raw.getOrDefault("HOME", "");
        c.workStart = hour(raw.getOrDefault("WORK_START", "9"), 0, 23, "Working day starts");
        c.workEnd = hour(raw.getOrDefault("WORK_END", "17"), 1, 24, "Working day ends");
        c.hour24 = !raw.getOrDefault("HOUR_24", "true").equalsIgnoreCase("false");
        return c;
    }

    static int hour(String value, int min, int max, String label) {
        try {
            int h = Integer.parseInt(value.strip());
            if (h >= min && h <= max) return h;
        } catch (NumberFormatException e) {
            // said below
        }
        throw new IllegalStateException("Set " + label + " in Team clocks' settings to an hour from " + min + " to " + max);
    }
}
