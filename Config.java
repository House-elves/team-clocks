import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** ~/.config/team-clocks/config: KEY=value lines. Elf Desk writes it from the settings in elf.yaml;
 * the tab's Add rewrites only its CITIES line. */
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

    /**
     * Team cities, changed from its tab: only the CITIES line is rewritten, every other line stays as the desk wrote
     * it. Written whole, then moved into place, with the file's own permissions.
     */
    static void setCities(String cities) throws IOException {
        List<String> lines = Files.exists(FILE) ? new ArrayList<>(Files.readAllLines(FILE)) : new ArrayList<>();
        boolean done = false;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i).strip();
            int eq = line.indexOf('=');
            if (!line.startsWith("#") && eq > 0 && line.substring(0, eq).strip().equals("CITIES")) {
                // The first one is the one load() reads; later ones would only confuse, so they go.
                if (done) lines.remove(i--);
                else lines.set(i, "CITIES=" + cities);
                done = true;
            }
        }
        if (!done) lines.add("CITIES=" + cities);
        Files.createDirectories(DIR);
        Path tmp = FILE.resolveSibling("config.new");
        Files.write(tmp, lines);
        if (Files.exists(FILE)) {
            try {
                Files.setPosixFilePermissions(tmp, Files.getPosixFilePermissions(FILE));
            } catch (UnsupportedOperationException e) {
                // Not a POSIX file system: it keeps the default.
            }
        }
        Files.move(tmp, FILE, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
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
